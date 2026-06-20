package com.java.tools.segmentroutingprefix;

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

public class SegmentRoutingPrefix {

    private static final String TOOL_NAME = "Segment Routing Prefix";
    private static final String CMDSET_MARKER = "_HW-SID_";
    private static final String CSV_HEADER = "Node,NodeIP,Prefix,Label,OutLabel,Interface,NextHop,Role,MPLSMtu,Mtu,State";
    private static final Pattern LOG_FILE_PATTERN = Pattern.compile(
            "^\\[\\d+]([^_]+)_(.+)_([A-Z]+)-SID_\\d{4}-\\d{2}-\\d{2}\\.txt$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HW_ROW_PATTERN = Pattern.compile(
            "^\\s*(\\d{1,3}(?:\\.\\d{1,3}){3}/\\d+)\\s+"
            + "(\\S+)\\s+"
            + "(\\S+)\\s+"
            + "(\\S+)\\s+"
            + "(\\d{1,3}(?:\\.\\d{1,3}){3})\\s+"
            + "(\\S+)\\s+"
            + "(\\S+)\\s+"
            + "(\\S+)\\s+"
            + "(\\S+)\\s*$");
    private static final Pattern PROMPT_NODE_PATTERN = Pattern.compile("(?m)^<([^>]+)>");

    public static void main(String[] args) {
        AppConsole.install("Segment Routing Prefix Console", "Segment Routing Prefix - Console");
        ToolDialogHelper.setWindowsLookAndFeel();
        System.out.println("[INFO] Segment Routing Prefix started");

        try {
            File appDir = new File(".").getCanonicalFile();
            File inputDir = new File(appDir, "_output\\Total_Log");
            File outputDir = new File(appDir, "_output\\Segment_Routing_Prefix");
            inputDir.mkdirs();
            outputDir.mkdirs();

            System.out.println("[PATH] Input : " + inputDir.getAbsolutePath());
            System.out.println("[PATH] Output: " + outputDir.getAbsolutePath());

            File[] files = inputDir.listFiles((dir, name)
                    -> name != null
                    && name.toUpperCase(Locale.ROOT).contains(CMDSET_MARKER.toUpperCase(Locale.ROOT))
                    && name.toLowerCase(Locale.ROOT).endsWith(".txt"));
            if (files == null || files.length == 0) {
                String message = "No HW-SID input files found in _output\\Total_Log";
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
            int processedFiles = 0;
            for (File file : files) {
                processedFiles++;
                System.out.println("[PROCESS] Reading " + file.getName());
                List<CsvRow> rows = parseHuaweiLogFile(file);
                if (rows.isEmpty()) {
                    System.out.println("[WARN] No Segment Routing Prefix rows parsed from " + file.getName());
                    continue;
                }
                for (CsvRow row : rows) {
                    csvLines.add(row.toCsvLine());
                    parsedRows++;
                }
            }

            if (parsedRows == 0) {
                String message = "No Segment Routing Prefix rows were parsed from the input file(s)";
                System.out.println("[WARN] " + message);
                ToolDialogHelper.showInfo(TOOL_NAME, message);
                return;
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            String outputFileName = "DataSegment_Routing_Prefix_" + timestamp + ".csv";
            File outputFile = new File(outputDir, outputFileName);
            Files.write(outputFile.toPath(), csvLines, StandardCharsets.UTF_8);

            String summary = "Generated 1 Segment Routing Prefix file\n"
                    + outputFileName + "\n"
                    + "Rows: " + parsedRows;
            System.out.println("[INFO] Generated " + outputFile.getAbsolutePath());
            System.out.println("[INFO] Rows: " + parsedRows);
            ToolDialogHelper.showSuccess(TOOL_NAME, summary, processedFiles);
        } catch (Exception ex) {
            System.out.println("[ERROR] Segment Routing Prefix failed: " + ex.getMessage());
            ToolDialogHelper.showInfo(TOOL_NAME, "Segment Routing Prefix failed\n" + ex.getMessage());
        }
    }

    static List<CsvRow> parseHuaweiLogFile(File file) throws Exception {
        Metadata metadata = Metadata.fromFileName(file.getName());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String promptNode = extractPromptNode(text);
        if (!promptNode.isEmpty()) {
            metadata = metadata.withPreferredNode(promptNode);
        }

        List<CsvRow> rows = new ArrayList<CsvRow>();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = HW_ROW_PATTERN.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            rows.add(new CsvRow(
                    metadata.node,
                    metadata.nodeIp,
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4),
                    matcher.group(5),
                    matcher.group(6),
                    matcher.group(7),
                    matcher.group(8),
                    matcher.group(9)));
        }
        return rows;
    }

    private static String extractPromptNode(String text) {
        Matcher matcher = PROMPT_NODE_PATTERN.matcher(text == null ? "" : text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
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
        final String nodeIp;

        Metadata(String node, String nodeIp) {
            this.node = node == null ? "" : node.trim();
            this.nodeIp = nodeIp == null ? "" : nodeIp.trim();
        }

        Metadata withPreferredNode(String preferredNode) {
            if (preferredNode == null || preferredNode.trim().isEmpty()) {
                return this;
            }
            return new Metadata(preferredNode, nodeIp);
        }

        static Metadata fromFileName(String fileName) {
            Matcher matcher = LOG_FILE_PATTERN.matcher(fileName == null ? "" : fileName);
            if (matcher.matches()) {
                return new Metadata(matcher.group(2), matcher.group(1));
            }
            return new Metadata("", "");
        }
    }

    static final class CsvRow {
        final String node;
        final String nodeIp;
        final String prefix;
        final String label;
        final String outLabel;
        final String interfaceName;
        final String nextHop;
        final String role;
        final String mplsMtu;
        final String mtu;
        final String state;

        CsvRow(String node, String nodeIp, String prefix, String label, String outLabel,
                String interfaceName, String nextHop, String role, String mplsMtu, String mtu, String state) {
            this.node = node;
            this.nodeIp = nodeIp;
            this.prefix = prefix;
            this.label = label;
            this.outLabel = outLabel;
            this.interfaceName = interfaceName;
            this.nextHop = nextHop;
            this.role = role;
            this.mplsMtu = mplsMtu;
            this.mtu = mtu;
            this.state = state;
        }

        String toCsvLine() {
            return csvEscape(node)
                    + "," + csvEscape(nodeIp)
                    + "," + csvEscape(prefix)
                    + "," + csvEscape(label)
                    + "," + csvEscape(outLabel)
                    + "," + csvEscape(interfaceName)
                    + "," + csvEscape(nextHop)
                    + "," + csvEscape(role)
                    + "," + csvEscape(mplsMtu)
                    + "," + csvEscape(mtu)
                    + "," + csvEscape(state);
        }
    }
}
