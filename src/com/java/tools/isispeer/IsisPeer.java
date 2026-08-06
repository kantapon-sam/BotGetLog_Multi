package com.java.tools.isispeer;

import com.java.shared.AppConsole;
import com.java.shared.ToolDialogHelper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IsisPeer {

    private static final String TOOL_NAME = "ISIS Peer";
    private static final String CMDSET_MARKER = "_HW-ISIS_Peer_";
    private static final String CSV_HEADER =
            "site,Loopback,ISIS Process,System Id,Interface,Circuit Id,State,HoldTime,Type,PRI,Cost";
    private static final Pattern LOG_FILE_PATTERN = Pattern.compile(
            "^\\[\\d+]([^_]+)_(.+?)_HW-ISIS_Peer_\\d{4}-\\d{2}-\\d{2}\\.txt$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_SITE_PATTERN = Pattern.compile("(?m)^\\s*<([^>]+)>");
    private static final Pattern INTERFACE_SECTION_PATTERN = Pattern.compile(
            "(?i)Interface\\s+information\\s+for\\s+ISIS\\(([^)]+)\\)");
    private static final Pattern PEER_SECTION_PATTERN = Pattern.compile(
            "(?i)Peer\\s+information\\s+for\\s+ISIS\\(([^)]+)\\)");
    private static final Pattern COST_LINE_PATTERN = Pattern.compile("(?i)^\\s*Cost\\s*:\\s*(.+?)\\s*$");
    private static final Pattern LEVEL_COST_PATTERN = Pattern.compile("(?i)\\b(L[12])\\s+(-?\\d+)\\b");
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;\\d?]*[ -/]*[@-~]");

    public static void main(String[] args) {
        AppConsole.install("ISIS Peer Console", "ISIS Peer - Console");
        ToolDialogHelper.setWindowsLookAndFeel();
        System.out.println("[INFO] ISIS Peer started");

        try {
            File appDir = new File(".").getCanonicalFile();
            File inputDir = new File(appDir, "_output\\Total_Log");
            File outputDir = new File(appDir, "_output\\ISIS_Peer");
            inputDir.mkdirs();
            outputDir.mkdirs();

            System.out.println("[PATH] Input : " + inputDir.getAbsolutePath());
            System.out.println("[PATH] Output: " + outputDir.getAbsolutePath());

            File[] files = inputDir.listFiles((dir, name)
                    -> name != null
                    && name.toUpperCase(Locale.ROOT).contains(CMDSET_MARKER.toUpperCase(Locale.ROOT))
                    && name.toLowerCase(Locale.ROOT).endsWith(".txt"));
            if (files == null || files.length == 0) {
                String message = "No HW-ISIS_Peer input files found in _output\\Total_Log";
                System.out.println("[WARN] " + message);
                ToolDialogHelper.showInfo(TOOL_NAME, message);
                return;
            }

            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    return Long.compare(left.lastModified(), right.lastModified());
                }
            });

            List<String> csvLines = new ArrayList<String>();
            csvLines.add(CSV_HEADER);
            int parsedRows = 0;
            int missingCostRows = 0;
            for (File file : files) {
                System.out.println("[PROCESS] Reading " + file.getName());
                List<CsvRow> rows = parseLogFile(file);
                if (rows.isEmpty()) {
                    System.out.println("[WARN] No ISIS peer rows parsed from " + file.getName());
                    continue;
                }
                for (CsvRow row : rows) {
                    csvLines.add(row.toCsvLine());
                    parsedRows++;
                    if (row.cost.isEmpty()) {
                        missingCostRows++;
                    }
                }
            }

            if (parsedRows == 0) {
                String message = "No ISIS peer rows were parsed from the input file(s)";
                System.out.println("[WARN] " + message);
                ToolDialogHelper.showInfo(TOOL_NAME, message);
                return;
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            String outputFileName = "DataISIS_Peer_" + timestamp + ".csv";
            File outputFile = new File(outputDir, outputFileName);
            Files.write(outputFile.toPath(), csvLines, StandardCharsets.UTF_8);

            String summary = "Generated 1 ISIS Peer file\n"
                    + outputFileName + "\n"
                    + "Rows: " + parsedRows
                    + (missingCostRows > 0 ? "\nRows without Cost: " + missingCostRows : "");
            System.out.println("[INFO] Generated " + outputFile.getAbsolutePath());
            System.out.println("[INFO] Rows: " + parsedRows);
            if (missingCostRows > 0) {
                System.out.println("[WARN] Rows without matching interface Cost: " + missingCostRows);
            }
            ToolDialogHelper.showSuccess(TOOL_NAME, summary, files.length);
        } catch (Exception ex) {
            System.out.println("[ERROR] ISIS Peer failed: " + ex.getMessage());
            ToolDialogHelper.showInfo(TOOL_NAME, "ISIS Peer failed\n" + ex.getMessage());
        }
    }

    static List<CsvRow> parseLogFile(File file) throws Exception {
        Metadata metadata = Metadata.fromFileName(file.getName());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        text = ANSI_ESCAPE_PATTERN.matcher(text).replaceAll("");

        String promptSite = extractPromptSite(text);
        if (!promptSite.isEmpty()) {
            metadata = metadata.withPreferredSite(promptSite);
        }

        Map<String, CostValues> interfaceCosts = extractInterfaceCosts(text);
        return extractPeerRows(text, metadata, interfaceCosts);
    }

    private static Map<String, CostValues> extractInterfaceCosts(String text) {
        Map<String, CostValues> costs = new LinkedHashMap<String, CostValues>();
        String process = "";
        String interfaceName = "";
        boolean inInterfaceSection = false;
        boolean awaitingInterfaceRow = false;

        String[] lines = safeLines(text);
        for (String line : lines) {
            Matcher interfaceSectionMatcher = INTERFACE_SECTION_PATTERN.matcher(line);
            if (interfaceSectionMatcher.find()) {
                process = interfaceSectionMatcher.group(1).trim();
                interfaceName = "";
                inInterfaceSection = true;
                awaitingInterfaceRow = false;
                continue;
            }

            if (PEER_SECTION_PATTERN.matcher(line).find()) {
                inInterfaceSection = false;
                awaitingInterfaceRow = false;
                interfaceName = "";
                continue;
            }

            if (!inInterfaceSection) {
                continue;
            }

            String trimmed = line.trim();
            if (isInterfaceTableHeader(trimmed)) {
                awaitingInterfaceRow = true;
                interfaceName = "";
                continue;
            }

            if (awaitingInterfaceRow) {
                if (trimmed.isEmpty() || isSeparatorLine(trimmed)) {
                    continue;
                }
                String[] fields = trimmed.split("\\s+");
                if (fields.length >= 7) {
                    interfaceName = fields[0];
                    awaitingInterfaceRow = false;
                }
                continue;
            }

            if (interfaceName.isEmpty()) {
                continue;
            }

            Matcher costLineMatcher = COST_LINE_PATTERN.matcher(line);
            if (!costLineMatcher.matches()) {
                continue;
            }

            CostValues values = CostValues.fromCostText(costLineMatcher.group(1));
            if (values.hasValue()) {
                costs.put(costKey(process, interfaceName), values);
            }
        }
        return costs;
    }

    private static List<CsvRow> extractPeerRows(String text, Metadata metadata,
            Map<String, CostValues> interfaceCosts) {
        List<CsvRow> rows = new ArrayList<CsvRow>();
        String process = "";
        boolean inPeerSection = false;
        boolean peerHeaderSeen = false;

        String[] lines = safeLines(text);
        for (String line : lines) {
            Matcher peerSectionMatcher = PEER_SECTION_PATTERN.matcher(line);
            if (peerSectionMatcher.find()) {
                process = peerSectionMatcher.group(1).trim();
                inPeerSection = true;
                peerHeaderSeen = false;
                continue;
            }

            if (INTERFACE_SECTION_PATTERN.matcher(line).find()) {
                inPeerSection = false;
                peerHeaderSeen = false;
                continue;
            }

            if (!inPeerSection) {
                continue;
            }

            String trimmed = line.trim();
            if (isPeerTableHeader(trimmed)) {
                peerHeaderSeen = true;
                continue;
            }
            if (!peerHeaderSeen || trimmed.isEmpty() || isSeparatorLine(trimmed)) {
                continue;
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("total peer")) {
                inPeerSection = false;
                peerHeaderSeen = false;
                continue;
            }
            if (trimmed.startsWith("<") || trimmed.startsWith("[")) {
                inPeerSection = false;
                peerHeaderSeen = false;
                continue;
            }

            String[] fields = trimmed.split("\\s+");
            if (fields.length < 6) {
                continue;
            }

            String systemId = fields[0];
            String interfaceName = fields[1];
            String circuitId = fields[2];
            String state = fields[3];
            String holdTime = fields[4];
            String type = fields[5];
            String pri = fields.length >= 7 ? fields[6] : "";
            CostValues values = interfaceCosts.get(costKey(process, interfaceName));
            String cost = values == null ? "" : values.forPeerType(type);

            rows.add(new CsvRow(metadata.site, metadata.loopback, process, systemId, interfaceName,
                    circuitId, state, holdTime, type, pri, cost));
        }
        return rows;
    }

    private static String[] safeLines(String text) {
        return (text == null ? "" : text).split("\\r?\\n");
    }

    private static boolean isInterfaceTableHeader(String trimmedLine) {
        String normalized = trimmedLine.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.startsWith("interface id ")
                && normalized.contains("ipv4.state")
                && normalized.contains("type");
    }

    private static boolean isPeerTableHeader(String trimmedLine) {
        String normalized = trimmedLine.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.startsWith("system id interface ")
                && normalized.contains("circuit id")
                && normalized.contains("holdtime")
                && normalized.endsWith("pri");
    }

    private static boolean isSeparatorLine(String trimmedLine) {
        return !trimmedLine.isEmpty() && trimmedLine.matches("[-=]{3,}");
    }

    private static String extractPromptSite(String text) {
        Matcher matcher = PROMPT_SITE_PATTERN.matcher(text == null ? "" : text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static String costKey(String process, String interfaceName) {
        return safeValue(process).toUpperCase(Locale.ROOT)
                + "\u0000"
                + safeValue(interfaceName).toLowerCase(Locale.ROOT);
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String csvEscape(String value) {
        String safe = value == null ? "" : value;
        boolean needsQuote = safe.contains(",")
                || safe.contains("\"")
                || safe.contains("\r")
                || safe.contains("\n");
        if (!needsQuote) {
            return safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String excelTextForLeadingZeroDigits(String value) {
        String safe = safeValue(value);
        if (safe.matches("0\\d+")) {
            return "=\"" + safe + "\"";
        }
        return safe;
    }

    private static final class Metadata {
        final String site;
        final String loopback;

        Metadata(String site, String loopback) {
            this.site = safeValue(site);
            this.loopback = safeValue(loopback);
        }

        Metadata withPreferredSite(String preferredSite) {
            return safeValue(preferredSite).isEmpty() ? this : new Metadata(preferredSite, loopback);
        }

        static Metadata fromFileName(String fileName) {
            Matcher matcher = LOG_FILE_PATTERN.matcher(fileName == null ? "" : fileName);
            if (matcher.matches()) {
                return new Metadata(matcher.group(2), matcher.group(1));
            }
            return new Metadata("", "");
        }
    }

    private static final class CostValues {
        final String l1;
        final String l2;

        CostValues(String l1, String l2) {
            this.l1 = safeValue(l1);
            this.l2 = safeValue(l2);
        }

        static CostValues fromCostText(String costText) {
            String l1 = "";
            String l2 = "";
            Matcher matcher = LEVEL_COST_PATTERN.matcher(costText == null ? "" : costText);
            while (matcher.find()) {
                if ("L1".equalsIgnoreCase(matcher.group(1))) {
                    l1 = matcher.group(2);
                } else if ("L2".equalsIgnoreCase(matcher.group(1))) {
                    l2 = matcher.group(2);
                }
            }
            return new CostValues(l1, l2);
        }

        boolean hasValue() {
            return !l1.isEmpty() || !l2.isEmpty();
        }

        String forPeerType(String peerType) {
            String normalizedType = safeValue(peerType).toUpperCase(Locale.ROOT);
            boolean usesL1 = normalizedType.contains("L1");
            boolean usesL2 = normalizedType.contains("L2");

            if (usesL1 && !usesL2) {
                return !l1.isEmpty() ? l1 : l2;
            }
            if (usesL2 && !usesL1) {
                return !l2.isEmpty() ? l2 : l1;
            }
            if (usesL1 && usesL2) {
                if (l1.equals(l2)) {
                    return l1;
                }
                if (!l1.isEmpty() && !l2.isEmpty()) {
                    return "L1=" + l1 + ";L2=" + l2;
                }
            }
            return !l1.isEmpty() ? l1 : l2;
        }
    }

    static final class CsvRow {
        final String site;
        final String loopback;
        final String process;
        final String systemId;
        final String interfaceName;
        final String circuitId;
        final String state;
        final String holdTime;
        final String type;
        final String pri;
        final String cost;

        CsvRow(String site, String loopback, String process, String systemId, String interfaceName,
                String circuitId, String state, String holdTime, String type, String pri, String cost) {
            this.site = safeValue(site);
            this.loopback = safeValue(loopback);
            this.process = safeValue(process);
            this.systemId = safeValue(systemId);
            this.interfaceName = safeValue(interfaceName);
            this.circuitId = safeValue(circuitId);
            this.state = safeValue(state);
            this.holdTime = safeValue(holdTime);
            this.type = safeValue(type);
            this.pri = safeValue(pri);
            this.cost = safeValue(cost);
        }

        String toCsvLine() {
            return csvEscape(site)
                    + "," + csvEscape(loopback)
                    + "," + csvEscape(process)
                    + "," + csvEscape(systemId)
                    + "," + csvEscape(interfaceName)
                    + "," + csvEscape(excelTextForLeadingZeroDigits(circuitId))
                    + "," + csvEscape(state)
                    + "," + csvEscape(holdTime)
                    + "," + csvEscape(type)
                    + "," + csvEscape(pri)
                    + "," + csvEscape(cost);
        }
    }
}
