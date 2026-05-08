package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DescriptionChecker {

    private static final String OUTPUT_PREFIX = "DataDescription_MB_";
    private static final String CHECK_OK = "OK";
    private static final String CHECK_NOT_OK = "Mismatch";
    private static final List<String> BLOCKED_DESCRIPTION_TEXT = Arrays.asList("-CO-", "-AG-", "-AC-", "TO_AN");
    private static final List<String> REQUIRED_DESCRIPTION_TEXT = Arrays.asList("L21", "L23", "U21", "L18", "2G", "3G", "5G", "LTE");
    private static final Pattern ALNUM_TOKEN = Pattern.compile("[A-Za-z0-9]+");

    private DescriptionChecker() {
    }

    static File process(File lldpFile, File outputFolder, String timestamp) throws IOException {
        if (lldpFile == null || !lldpFile.isFile()) {
            throw new IOException("LLDP file not found: " + lldpFile);
        }
        if (outputFolder == null) {
            throw new IOException("Output folder is not set");
        }

        Path outputDir = outputFolder.toPath();
        Files.createDirectories(outputDir);

        Path outputFile = outputDir.resolve(OUTPUT_PREFIX + timestamp + ".csv");
        ProcessStats stats;
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write('\ufeff');
            writeCsvRow(writer, Arrays.asList(
                    "Site code",
                    "IP loopback",
                    "Port",
                    "Home",
                    "Current State",
                    "Description",
                    "Site code 7 digits",
                    "Desc site digit",
                    "Description Check"
            ));

            stats = processFile(lldpFile.toPath(), writer);
        }

        System.out.println("[INFO] Description source      : " + lldpFile.getName());
        System.out.println("[INFO] Description rows read   : " + stats.rowsRead);
        System.out.println("[INFO] Description rows up     : " + stats.upRows);
        System.out.println("[INFO] Description rows matched: " + stats.filteredRows);
        System.out.println("[INFO] Description rows written: " + stats.rowsWritten);
        System.out.println("[INFO] Description mismatch rows: " + stats.mismatchSiteCodeRows);
        System.out.println("[INFO] Generated " + outputFile.getFileName());
        return outputFile.toFile();
    }

    private static ProcessStats processFile(Path inputFile, BufferedWriter writer) throws IOException {
        ProcessStats stats = new ProcessStats();

        try (BufferedReader reader = newCsvReader(inputFile)) {
            String headerRecord = readCsvRecord(reader);
            if (headerRecord == null) {
                return stats;
            }

            List<String> headers = parseCsvRecord(headerRecord);
            Map<String, Integer> headerIndex = buildHeaderIndex(headers);

            int siteCodeIndex = requiredColumn(headerIndex, "Site code", inputFile);
            int ipLoopbackIndex = requiredColumn(headerIndex, "IP loopback", inputFile);
            int interfaceIndex = requiredColumn(headerIndex, "Interface", inputFile);
            int currentStateIndex = requiredColumn(headerIndex, "Current State", inputFile);
            int descriptionIndex = requiredColumn(headerIndex, "Description", inputFile);

            String record;
            while ((record = readCsvRecord(reader)) != null) {
                stats.rowsRead++;
                List<String> row = parseCsvRecord(record);

                String currentState = getField(row, currentStateIndex).trim();
                if (!"up".equalsIgnoreCase(currentState)) {
                    continue;
                }
                stats.upRows++;

                String description = getField(row, descriptionIndex);
                if (!passesDescriptionFilter(description)) {
                    continue;
                }
                stats.filteredRows++;

                String siteCode = getField(row, siteCodeIndex).trim();
                String siteCodeSevenDigits = extractSiteCodeSevenDigits(siteCode);
                String descSiteDigit = extractSiteCodeSevenDigits(description);
                String home = getHome(siteCode);
                boolean siteCodeFound = !siteCodeSevenDigits.isEmpty()
                        && containsIgnoreCase(description, siteCodeSevenDigits);
                if (siteCodeFound) {
                    descSiteDigit = siteCodeSevenDigits;
                }

                if (!siteCodeFound) {
                    stats.mismatchSiteCodeRows++;
                }

                writeCsvRow(writer, Arrays.asList(
                        siteCode,
                        getField(row, ipLoopbackIndex).trim(),
                        formatPort(getField(row, interfaceIndex)),
                        home,
                        currentState,
                        description,
                        siteCodeSevenDigits,
                        descSiteDigit,
                        siteCodeFound ? CHECK_OK : CHECK_NOT_OK
                ));
                stats.rowsWritten++;
            }
        }

        return stats;
    }

    private static String getHome(String siteCode) {
        String upperSiteCode = siteCode.toUpperCase(Locale.ROOT);
        if (upperSiteCode.contains("-AC-")
                || upperSiteCode.contains("-AG-")
                || upperSiteCode.contains("-CO-")) {
            return "D";
        }
        return "T";
    }

    private static BufferedReader newCsvReader(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder), 1024 * 1024);
    }

    private static Map<String, Integer> buildHeaderIndex(List<String> headers) {
        Map<String, Integer> headerIndex = new HashMap<String, Integer>();
        for (int i = 0; i < headers.size(); i++) {
            headerIndex.put(normalizeHeader(headers.get(i)), i);
        }
        return headerIndex;
    }

    private static String normalizeHeader(String header) {
        return header
                .replace("\ufeff", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static int requiredColumn(Map<String, Integer> headerIndex, String columnName, Path inputFile) {
        Integer index = headerIndex.get(normalizeHeader(columnName));
        if (index == null) {
            throw new IllegalArgumentException("Required column '" + columnName + "' not found in " + inputFile.getFileName());
        }
        return index;
    }

    private static String getField(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return "";
        }
        return row.get(index);
    }

    private static String formatPort(String port) {
        if (port == null) {
            return "";
        }
        String value = port.trim();
        if (value.startsWith("'")) {
            return value;
        }
        if (value.matches("^[0-9/:]+$")) {
            return "'" + value;
        }
        return value;
    }

    private static boolean passesDescriptionFilter(String description) {
        String upperDescription = description.toUpperCase(Locale.ROOT);

        for (String blockedText : BLOCKED_DESCRIPTION_TEXT) {
            if (upperDescription.contains(blockedText)) {
                return false;
            }
        }

        for (String requiredText : REQUIRED_DESCRIPTION_TEXT) {
            if (containsRequiredBand(upperDescription, requiredText)) {
                return true;
            }
        }

        return upperDescription.contains("NB0");
    }

    private static boolean containsRequiredBand(String description, String band) {
        int fromIndex = 0;

        while (fromIndex < description.length()) {
            int index = description.indexOf(band, fromIndex);
            if (index < 0) {
                return false;
            }

            int afterIndex = index + band.length();
            if (afterIndex >= description.length() || !Character.isDigit(description.charAt(afterIndex))) {
                return true;
            }

            fromIndex = index + 1;
        }

        return false;
    }

    private static String extractSiteCodeSevenDigits(String siteCode) {
        Matcher matcher = ALNUM_TOKEN.matcher(siteCode.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            String siteDigit = extractSiteDigitFromToken(matcher.group());
            if (!siteDigit.isEmpty()) {
                return siteDigit;
            }
        }

        return "";
    }

    private static String extractSiteDigitFromToken(String token) {
        for (int start = 0; start <= token.length() - 7; start++) {
            String candidate = token.substring(start, start + 7);
            if (isSiteDigit(candidate)) {
                return candidate;
            }
        }

        return "";
    }

    private static boolean isSiteDigit(String value) {
        if (value.length() != 7) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                return false;
            }
        }

        return hasLetter && hasDigit;
    }

    private static boolean containsIgnoreCase(String text, String keyword) {
        return text.toUpperCase(Locale.ROOT).contains(keyword.toUpperCase(Locale.ROOT));
    }

    private static String readCsvRecord(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }

        StringBuilder record = new StringBuilder(line);
        while (hasOpenQuote(record)) {
            String nextLine = reader.readLine();
            if (nextLine == null) {
                break;
            }
            record.append('\n').append(nextLine);
        }

        return record.toString();
    }

    private static boolean hasOpenQuote(CharSequence text) {
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            }
        }

        return inQuotes;
    }

    private static List<String> parseCsvRecord(String record) {
        List<String> values = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean atStartOfField = true;

        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == ',') {
                values.add(field.toString());
                field.setLength(0);
                atStartOfField = true;
            } else if (c == '"' && atStartOfField) {
                inQuotes = true;
                atStartOfField = false;
            } else if (c != '\r') {
                field.append(c);
                atStartOfField = false;
            }
        }

        values.add(field.toString());
        return values;
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(values.get(i)));
        }
        writer.newLine();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        boolean needQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;

        if (!needQuotes) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static class ProcessStats {

        ProcessStats() {
        }

        long rowsRead;
        long upRows;
        long filteredRows;
        long rowsWritten;
        long mismatchSiteCodeRows;
    }
}
