package com.java.tools.mplslsp;

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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MplsLsp {

    private static final String TOOL_NAME = "MPLS LSP";
    private static final String CMDSET_MARKER = "_N-MPLS_LSP_";
    private static final String CSV_HEADER = "Node,Loopback,LSP Name,Actual Hops";
    private static final Pattern LOG_FILE_PATTERN = Pattern.compile(
            "^\\[\\d+]([^_]+)_(.+)_N-MPLS_LSP_\\d{4}-\\d{2}-\\d{2}\\.txt$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DETAIL_COMMAND_PATTERN = Pattern.compile(
            "(?im)^.*\\bshow\\s+router\\s+mpls\\s+lsp\\s+\"([^\"]+)\"\\s+path\\s+detail\\s*$");
    private static final Pattern ACTUAL_HOP_IP_PATTERN = Pattern.compile(
            "\\((\\d{1,3}(?:\\.\\d{1,3}){3})\\)");

    public static void main(String[] args) {
        AppConsole.install("MPLS LSP Console", "MPLS LSP - Console");
        ToolDialogHelper.setWindowsLookAndFeel();
        System.out.println("[INFO] MPLS LSP started");

        try {
            File appDir = new File(".").getCanonicalFile();
            File inputDir = new File(appDir, "_output\\Total_Log");
            File outputDir = new File(appDir, "_output\\MPLS_LSP");
            inputDir.mkdirs();
            outputDir.mkdirs();

            System.out.println("[PATH] Input : " + inputDir.getAbsolutePath());
            System.out.println("[PATH] Output: " + outputDir.getAbsolutePath());

            File[] files = inputDir.listFiles((dir, name)
                    -> name != null
                    && name.toUpperCase(Locale.ROOT).contains(CMDSET_MARKER)
                    && name.toLowerCase(Locale.ROOT).endsWith(".txt"));
            if (files == null || files.length == 0) {
                String message = "No N-MPLS_LSP input files found in _output\\Total_Log";
                System.out.println("[WARN] " + message);
                ToolDialogHelper.showInfo(TOOL_NAME, message);
                return;
            }

            Arrays.sort(files, new Comparator<File>() {
                public int compare(File left, File right) {
                    return Long.compare(left.lastModified(), right.lastModified());
                }
            });

            List<String> csvLines = new ArrayList<String>();
            csvLines.add(CSV_HEADER);
            int parsedRows = 0;
            for (File file : files) {
                System.out.println("[PROCESS] Reading " + file.getName());
                List<CsvRow> rows = parseLogFile(file);
                if (rows.isEmpty()) {
                    System.out.println("[WARN] No MPLS LSP path rows parsed from " + file.getName());
                    continue;
                }
                for (CsvRow row : rows) {
                    csvLines.add(row.toCsvLine());
                    parsedRows++;
                }
            }

            if (parsedRows == 0) {
                String message = "No MPLS LSP rows were parsed from the input file(s)";
                System.out.println("[WARN] " + message);
                ToolDialogHelper.showInfo(TOOL_NAME, message);
                return;
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            String outputFileName = "DataMPLS_LSP_" + timestamp + ".csv";
            File outputFile = new File(outputDir, outputFileName);
            Files.write(outputFile.toPath(), csvLines, StandardCharsets.UTF_8);

            String summary = "Generated 1 MPLS LSP file\n"
                    + outputFileName + "\n"
                    + "Rows: " + parsedRows;
            System.out.println("[INFO] Generated " + outputFile.getAbsolutePath());
            System.out.println("[INFO] Rows: " + parsedRows);
            ToolDialogHelper.showSuccess(TOOL_NAME, summary, files.length);
        } catch (Exception ex) {
            System.out.println("[ERROR] MPLS LSP failed: " + ex.getMessage());
            ToolDialogHelper.showInfo(TOOL_NAME, "MPLS LSP failed\n" + ex.getMessage());
        }
    }

    static List<CsvRow> parseLogFile(File file) throws Exception {
        Metadata metadata = Metadata.fromFileName(file.getName());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        List<DetailCommand> commands = new ArrayList<DetailCommand>();
        Matcher matcher = DETAIL_COMMAND_PATTERN.matcher(text);
        while (matcher.find()) {
            commands.add(new DetailCommand(matcher.group(1).trim(), matcher.start(), matcher.end()));
        }

        List<CsvRow> rows = new ArrayList<CsvRow>();
        for (int i = 0; i < commands.size(); i++) {
            DetailCommand command = commands.get(i);
            int sectionEnd = (i + 1 < commands.size()) ? commands.get(i + 1).commandStart : text.length();
            String section = text.substring(command.commandEnd, sectionEnd);
            List<String> actualHops = extractActualHops(section);
            if (actualHops.isEmpty()) {
                continue;
            }
            rows.add(new CsvRow(metadata.node, metadata.loopback, command.lspName, join(actualHops, ",")));
        }
        return rows;
    }

    private static List<String> extractActualHops(String section) {
        List<String> hops = new ArrayList<String>();
        if (section == null || section.isEmpty()) {
            return hops;
        }

        String lower = section.toLowerCase(Locale.ROOT);
        int actualIndex = lower.indexOf("actual hops");
        if (actualIndex < 0) {
            return hops;
        }

        int blockStart = section.indexOf('\n', actualIndex);
        if (blockStart < 0) {
            blockStart = actualIndex;
        }

        int computedIndex = lower.indexOf("computed hops", blockStart);
        int blockEnd = computedIndex >= 0 ? computedIndex : section.length();
        String actualBlock = section.substring(blockStart, blockEnd);

        Matcher hopMatcher = ACTUAL_HOP_IP_PATTERN.matcher(actualBlock);
        while (hopMatcher.find()) {
            hops.add(hopMatcher.group(1));
        }
        return hops;
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(delimiter);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
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

    private static final class Metadata {
        final String node;
        final String loopback;

        Metadata(String node, String loopback) {
            this.node = node == null ? "" : node.trim();
            this.loopback = loopback == null ? "" : loopback.trim();
        }

        static Metadata fromFileName(String fileName) {
            Matcher matcher = LOG_FILE_PATTERN.matcher(fileName == null ? "" : fileName);
            if (matcher.matches()) {
                return new Metadata(matcher.group(2), matcher.group(1));
            }
            return new Metadata("", "");
        }
    }

    private static final class DetailCommand {
        final String lspName;
        final int commandStart;
        final int commandEnd;

        DetailCommand(String lspName, int commandStart, int commandEnd) {
            this.lspName = lspName;
            this.commandStart = commandStart;
            this.commandEnd = commandEnd;
        }
    }

    static final class CsvRow {
        final String node;
        final String loopback;
        final String lspName;
        final String actualHops;

        CsvRow(String node, String loopback, String lspName, String actualHops) {
            this.node = node;
            this.loopback = loopback;
            this.lspName = lspName;
            this.actualHops = actualHops;
        }

        String toCsvLine() {
            return csvEscape(node)
                    + "," + csvEscape(loopback)
                    + "," + csvEscape(lspName)
                    + "," + csvEscape(actualHops);
        }
    }
}
