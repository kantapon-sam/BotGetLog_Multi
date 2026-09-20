package com.java.tools.sid;

import com.java.shared.AppConsole;
import com.java.shared.ToolDialogHelper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates one multi-vendor Loopback/SID row for every verified *-SID log. */
public final class SidReport {

    private static final String TOOL_NAME = "SID";
    public static final String CSV_HEADER = "Node,loopback11,SID,loopback15,SID,loopback19,SID,loopback20,SID,loopback99,loopback98,loopback1023";
    private static final List<String> LOOPBACKS = Collections.unmodifiableList(
            Arrays.asList("11", "15", "19", "20", "99", "98", "1023"));
    private static final List<String> SID_LOOPBACKS = Collections.unmodifiableList(
            Arrays.asList("11", "15", "19", "20"));
    private static final Pattern LOG_FILE_PATTERN = Pattern.compile(
            "^\\[\\d+]([^_]+)_(.+)_(HW|ZTE|N)-SID_\\d{4}-\\d{2}-\\d{2}\\.txt$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![0-9.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?:/\\d{1,2})?(?![0-9.])");
    private static final Pattern SID_PATTERN = Pattern.compile(
            "(?i)\\b(?:node-sid|prefix-sid)\\b(?:\\s+(?:index|label))?\\s+(\\d+)\\b");
    private static final Pattern INDEX_PATTERN = Pattern.compile("(?i)\\b(?:index|label)\\s+(\\d+)\\b");
    private static final Pattern PROMPT_PATTERN = Pattern.compile(
            "(?m)^<([^>\\r\\n]+)>[ \\t]*(?:display|screen-length|$)"
            + "|^(?:\\*?[A-Za-z]:)?([^\\s#<>]+)[#>][ \\t]*(?:show|admin display-config|environment|terminal|$)");

    private SidReport() {
    }

    public static void main(String[] args) {
        AppConsole.install("SID Console", "SID - Console");
        ToolDialogHelper.setWindowsLookAndFeel();
        System.out.println("[INFO] SID report started");
        try {
            File appDir = new File(".").getCanonicalFile();
            File inputDir = new File(appDir, "_output\\Total_Log");
            File outputDir = new File(appDir, "_output\\SID");
            inputDir.mkdirs();
            outputDir.mkdirs();
            File[] files = inputDir.listFiles((dir, name) -> name != null
                    && name.toUpperCase(Locale.ROOT).matches(".*_(?:HW|ZTE|N)-SID_.*")
                    && name.toLowerCase(Locale.ROOT).endsWith(".txt"));
            if (files == null || files.length == 0) {
                String message = "No HW-SID, ZTE-SID or N-SID input files found in _output\\Total_Log";
                System.out.println("[WARN] " + message);
                ToolDialogHelper.showInfo(TOOL_NAME, message);
                return;
            }
            ProcessResult result = processFiles(files, outputDir);
            String summary = "Generated " + result.outputFiles.size() + " SID file\n"
                    + result.outputFiles.get(0).getName() + "\nRows: " + result.rows;
            System.out.println("[INFO] Generated " + result.outputFiles.get(0).getAbsolutePath());
            System.out.println("[INFO] Rows: " + result.rows);
            ToolDialogHelper.showSuccess(TOOL_NAME, summary, files.length);
        } catch (Exception ex) {
            System.out.println("[ERROR] SID report failed: " + ex.getMessage());
            ToolDialogHelper.showInfo(TOOL_NAME, "SID report failed\n" + ex.getMessage());
        }
    }

    public static ProcessResult processFiles(File[] inputs, File outputDir) throws Exception {
        if (inputs == null || inputs.length == 0) {
            throw new IllegalArgumentException("At least one SID log is required");
        }
        File[] ordered = inputs.clone();
        Arrays.sort(ordered, Comparator.comparingLong(File::lastModified)
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        List<String> csv = new ArrayList<String>();
        csv.add(CSV_HEADER);
        int rows = 0;
        for (File input : ordered) {
            SidRow row = parseLogFile(input);
            csv.add(row.toCsvLine());
            rows++;
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IllegalStateException("Unable to create SID output directory");
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        File output = new File(outputDir, "DataSID_" + timestamp + ".csv");
        Files.write(output.toPath(), csv, StandardCharsets.UTF_8);
        return new ProcessResult(Collections.singletonList(output), rows);
    }

    public static SidRow parseLogFile(File file) throws Exception {
        Metadata metadata = Metadata.fromFileName(file.getName());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String promptNode = promptNode(text);
        String node = promptNode.isEmpty() ? metadata.node : promptNode;
        Map<String, String> addresses = new LinkedHashMap<String, String>();
        Map<String, String> sids = new LinkedHashMap<String, String>();
        Map<String, String> sidByAddress = new LinkedHashMap<String, String>();
        String currentLoopback = "";

        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            String loopback = loopbackId(line);
            if (!loopback.isEmpty()) {
                currentLoopback = loopback;
            }

            String address = firstIpv4(line);
            if (!address.isEmpty()) {
                String owner = !loopback.isEmpty() ? loopback : currentLoopback;
                if (!owner.isEmpty() && LOOPBACKS.contains(owner)) {
                    addresses.put(owner, address);
                }
            }

            String sid = sidValue(line);
            if (!sid.isEmpty()) {
                String owner = !loopback.isEmpty() ? loopback : currentLoopback;
                if (!owner.isEmpty() && SID_LOOPBACKS.contains(owner)) {
                    sids.put(owner, sid);
                } else if (!address.isEmpty()) {
                    sidByAddress.put(address, sid);
                }
            }

            if (line.equals("#") || isPromptOnly(line)) {
                currentLoopback = "";
            }
        }

        for (String id : SID_LOOPBACKS) {
            String address = addresses.get(id);
            if (!sids.containsKey(id) && address != null && sidByAddress.containsKey(address)) {
                sids.put(id, sidByAddress.get(address));
            }
        }
        return new SidRow(node, addresses, sids);
    }

    private static String loopbackId(String line) {
        if (line.matches("(?i)^(?:show|display|admin)\\b.*")) {
            return "";
        }
        Matcher numbered = Pattern.compile("(?i)(?:^|[^A-Za-z0-9])(?:loopback|lo|lb)[ \\t_-]*(11|15|19|20|99|98|1023)(?!\\d)")
                .matcher(line);
        if (numbered.find()) {
            return numbered.group(1);
        }
        if (Pattern.compile("(?i)(?:^|[\"' \\t])system(?:[\"' \\t:]|$)").matcher(line).find()) {
            // Nokia exposes its node loopback as the "system" interface; in the
            // requested report this is Loopback20. Loopback99 remains reserved
            // for an explicitly named Loopback99 interface.
            return "20";
        }
        return "";
    }

    private static boolean containsSid(String line) {
        return Pattern.compile("(?i)\\b(?:node-sid|prefix-sid)\\b").matcher(line).find();
    }

    private static String firstIpv4(String line) {
        Matcher matcher = IPV4_PATTERN.matcher(line);
        while (matcher.find()) {
            if (validIpv4(matcher.group(1))) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static boolean validIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("\\d{1,3}") || Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String sidValue(String line) {
        if (!containsSid(line)) {
            return "";
        }
        Matcher indexed = INDEX_PATTERN.matcher(line);
        if (indexed.find()) {
            return indexed.group(1);
        }
        Matcher direct = SID_PATTERN.matcher(line);
        if (direct.find()) {
            return direct.group(1);
        }
        Matcher number = Pattern.compile("(?<![./\\d])(\\d{2,})(?![./\\d])").matcher(line);
        String last = "";
        while (number.find()) {
            last = number.group(1);
        }
        return last;
    }

    private static boolean isPromptOnly(String line) {
        return line.matches("^(?:<[^>]+>|(?:\\*?[A-Za-z]:)?[^\\s#<>]+[#>])$");
    }

    private static String promptNode(String text) {
        Matcher prompt = PROMPT_PATTERN.matcher(text == null ? "" : text);
        while (prompt.find()) {
            String value = prompt.group(1) == null ? prompt.group(2) : prompt.group(1);
            if (value != null && !value.trim().isEmpty() && !"LIVE".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return "";
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("^[=+@-].*")) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static final class Metadata {
        final String node;

        Metadata(String node) {
            this.node = node == null ? "" : node.trim();
        }

        static Metadata fromFileName(String fileName) {
            Matcher matcher = LOG_FILE_PATTERN.matcher(fileName == null ? "" : fileName);
            return matcher.matches() ? new Metadata(matcher.group(2)) : new Metadata("");
        }
    }

    public static final class SidRow {
        private final String node;
        private final Map<String, String> addresses;
        private final Map<String, String> sids;

        SidRow(String node, Map<String, String> addresses, Map<String, String> sids) {
            this.node = node == null ? "" : node;
            this.addresses = new LinkedHashMap<String, String>(addresses);
            this.sids = new LinkedHashMap<String, String>(sids);
        }

        public String getNode() {
            return node;
        }

        public String getAddress(String loopback) {
            return value(addresses, loopback);
        }

        public String getSid(String loopback) {
            return value(sids, loopback);
        }

        private static String value(Map<String, String> values, String key) {
            String result = values.get(key);
            return result == null ? "" : result;
        }

        public String toCsvLine() {
            List<String> values = new ArrayList<String>();
            values.add(node);
            for (String id : LOOPBACKS) {
                values.add(getAddress(id));
                if (SID_LOOPBACKS.contains(id)) {
                    values.add(getSid(id));
                }
            }
            List<String> escaped = new ArrayList<String>();
            for (String value : values) {
                escaped.add(csv(value));
            }
            return String.join(",", escaped);
        }
    }

    public static final class ProcessResult {
        private final List<File> outputFiles;
        private final int rows;

        ProcessResult(List<File> outputFiles, int rows) {
            this.outputFiles = new ArrayList<File>(outputFiles);
            this.rows = rows;
        }

        public List<File> getOutputFiles() {
            return Collections.unmodifiableList(outputFiles);
        }

        public int getRows() {
            return rows;
        }
    }
}
