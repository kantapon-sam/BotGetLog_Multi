package com.java.tools.linkoptical;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the node-level CPU and memory snapshot that accompanies the
 * standard Link Optical CSV files.
 */
public final class CpuMemoryExporter {

    private static final DateTimeFormatter OUTPUT_TIMESTAMP
            = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final Pattern CMD_SET_SUFFIX = Pattern.compile(
            "_([^_]+-LLDP-Link_OPTIC)_\\d{4}-\\d{2}-\\d{2}\\.txt$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IP_FROM_FILE = Pattern.compile("^\\[\\d+]([^_]+)_");
    private static final Pattern NOKIA_PROMPT = Pattern.compile(
            "(?mi)^(?:\\*?[A-Z]:)?([^#\\r\\n]+)#show\\s+system\\s+cpu\\s*$");
    private static final Pattern ZTE_PROMPT = Pattern.compile(
            "(?mi)^([^#\\r\\n]+)#show\\s+processor\\s*$");
    private static final Pattern HUAWEI_PROMPT = Pattern.compile(
            "(?mi)^<([^>\\r\\n]+)>display\\s+cpu-usage\\s*$");
    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "[-+]?\\d[\\d,]*(?:\\.\\d+)?");
    private static final String[] HEADER = {
        "Site code", "IP loopback",
        "CPU Current (%)", "CPU Idle (%)",
        "Memory Total (MB)", "Memory Used (MB)", "Memory Free (MB)",
        "Memory Used (%)", "Memory Free (%)"
    };

    private CpuMemoryExporter() {
    }

    /**
     * Parse a live command transcript without creating any output file.
     * This is shared by the MapViewer Live Node Monitor and the normal CSV
     * exporter so both paths use exactly the same vendor rules.
     */
    public static CpuMemorySnapshot parseSnapshot(String siteCode, String ipLoopback,
            String content) {
        CpuMemoryRow row = new CpuMemoryRow();
        row.siteCode = siteCode == null ? "" : siteCode.trim();
        row.ipLoopback = ipLoopback == null ? "" : ipLoopback.trim();
        parseContent(content, row);
        if (row.siteCode == null || row.siteCode.trim().isEmpty()) {
            row.siteCode = siteCode == null ? "" : siteCode.trim();
        }
        row.siteCode = row.siteCode == null ? "" : row.siteCode.trim();
        row.siteCode = row.siteCode.replaceFirst("^\\*?[A-Za-z]:", "");
        return CpuMemorySnapshot.from(row);
    }

    public static ExportResult export(List<File> inputFiles, File outputDir,
            String requestedTimestamp) throws IOException {
        if (outputDir == null) {
            outputDir = new File(".\\_output\\LLDP_Neighbor");
        }
        outputDir.mkdirs();
        File outputFile = nextOutputFile(outputDir, requestedTimestamp);
        ExportResult result = new ExportResult(outputFile);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {
            writeCsvRow(writer, Arrays.asList(HEADER));
            if (inputFiles == null) {
                return result;
            }
            for (File file : inputFiles) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                result.total++;
                CpuMemoryRow row;
                try {
                    String content = new String(
                            Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    row = parse(file, content);
                } catch (Exception ex) {
                    result.partial++;
                    continue;
                }
                if (!row.hasAnyCpuOrMemoryData()) {
                    result.noData++;
                    continue;
                }
                if (!row.isComplete()) {
                    result.partial++;
                    continue;
                }
                writeCsvRow(writer, row.toCsvValues());
                result.exported++;
            }
        }
        return result;
    }

    private static CpuMemoryRow parse(File file, String content) {
        CpuMemoryRow row = CpuMemoryRow.fromFile(file);
        parseContent(content, row);
        row.normalizeIdentity(file);
        return row;
    }

    private static void parseContent(String content, CpuMemoryRow row) {
        if (containsIgnoreCase(content, "show system cpu")) {
            row.siteCode = firstGroup(NOKIA_PROMPT, content);
            parseNokia(content, row);
        } else if (containsIgnoreCase(content, "show processor")) {
            row.siteCode = firstGroup(ZTE_PROMPT, content);
            parseZte(content, row);
        } else if (containsIgnoreCase(content, "display cpu-usage")) {
            row.siteCode = firstGroup(HUAWEI_PROMPT, content);
            parseHuawei(content, row);
        }
    }

    private static void parseNokia(String content, CpuMemoryRow row) {
        row.cpuCurrentPct = matchNumber(content,
                "(?mi)^\\s*Usage\\s+[\\d,]+\\s+([~\\d.]+)%\\s*$", 1);
        row.cpuIdlePct = matchNumber(content,
                "(?mi)^\\s*Idle\\s+[\\d,]+\\s+([~\\d.]+)%\\s*$", 1);
        Double usedBytes = matchNumber(content,
                "(?mi)^\\s*Total In Use\\s*:\\s*([\\d,]+)\\s+bytes\\s*$", 1);
        Double freeBytes = matchNumber(content,
                "(?mi)^\\s*Available Memory\\s*:\\s*([\\d,]+)\\s+bytes\\s*$", 1);
        row.memoryUsedMb = bytesToMb(usedBytes);
        row.memoryFreeMb = bytesToMb(freeBytes);
        if (usedBytes != null && freeBytes != null) {
            Double totalBytes = usedBytes + freeBytes;
            row.memoryTotalMb = bytesToMb(totalBytes);
            row.memoryUsedPct = percent(usedBytes, totalBytes);
            row.memoryFreePct = percent(freeBytes, totalBytes);
        }
    }

    private static void parseZte(String content, CpuMemoryRow row) {
        Pattern processorRow = Pattern.compile(
                "(?mi)^\\s*(\\S+)\\s+(MSC|SSC|N/A)\\s+"
                + "([\\d.]+)%\\s+([\\d.]+)%\\s+([\\d.]+)%\\s+([\\d.]+)%\\s+"
                + "([\\d,]+)\\s+([\\d,]+)\\s+([\\d.]+)%\\s*$");
        Matcher matcher = processorRow.matcher(content);
        ZteProcessor selected = null;
        while (matcher.find()) {
            ZteProcessor candidate = new ZteProcessor(
                    matcher.group(2), parseNumber(matcher.group(3)),
                    parseNumber(matcher.group(7)), parseNumber(matcher.group(8)),
                    parseNumber(matcher.group(9)));
            if (selected == null || candidate.isPreferredOver(selected)) {
                selected = candidate;
            }
        }
        if (selected == null) {
            return;
        }
        row.cpuCurrentPct = selected.cpuCurrentPct;
        row.cpuIdlePct = selected.cpuCurrentPct == null
                ? null : 100.0d - selected.cpuCurrentPct;
        row.memoryTotalMb = selected.memoryTotalMb;
        row.memoryFreeMb = selected.memoryFreeMb;
        if (selected.memoryTotalMb != null && selected.memoryFreeMb != null) {
            row.memoryUsedMb = Math.max(
                    0.0d, selected.memoryTotalMb - selected.memoryFreeMb);
        }
        row.memoryUsedPct = selected.memoryUsedPct;
        row.memoryFreePct = selected.memoryUsedPct == null
                ? null : 100.0d - selected.memoryUsedPct;
    }

    private static void parseHuawei(String content, CpuMemoryRow row) {
        row.cpuCurrentPct = matchNumber(content,
                "(?mi)^\\s*System cpu use rate is\\s*:\\s*([\\d.]+)%\\s*$", 1);
        row.cpuIdlePct = row.cpuCurrentPct == null
                ? null : 100.0d - row.cpuCurrentPct;
        Double totalKb = matchNumber(content,
                "(?mi)^\\s*System Total Memory Is\\s*:\\s*([\\d,]+)\\s+Kbytes\\s*$", 1);
        Double usedKb = matchNumber(content,
                "(?mi)^\\s*Total Memory Used Is\\s*:\\s*([\\d,]+)\\s+Kbytes\\s*$", 1);
        row.memoryUsedPct = matchNumber(content,
                "(?mi)^\\s*Memory Using Percentage Is\\s*:\\s*([\\d.]+)%\\s*$", 1);
        row.memoryTotalMb = kbToMb(totalKb);
        row.memoryUsedMb = kbToMb(usedKb);
        if (totalKb != null && usedKb != null) {
            Double freeKb = Math.max(0.0d, totalKb - usedKb);
            row.memoryFreeMb = kbToMb(freeKb);
            if (row.memoryUsedPct == null) {
                row.memoryUsedPct = percent(usedKb, totalKb);
            }
            row.memoryFreePct = row.memoryUsedPct == null
                    ? percent(freeKb, totalKb)
                    : 100.0d - row.memoryUsedPct;
        }
    }

    private static File nextOutputFile(File outputDir, String requestedTimestamp) {
        LocalDateTime stamp;
        try {
            stamp = LocalDateTime.parse(requestedTimestamp, OUTPUT_TIMESTAMP);
        } catch (Exception ex) {
            stamp = LocalDateTime.now();
        }
        File candidate;
        do {
            candidate = new File(outputDir,
                    "DataCPU_Memory_" + stamp.format(OUTPUT_TIMESTAMP) + ".csv");
            stamp = stamp.plusSeconds(1);
        } while (candidate.exists());
        return candidate;
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values)
            throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(values.get(i)));
        }
        writer.newLine();
    }

    private static String escapeCsv(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    private static String firstGroup(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content == null ? "" : content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Double matchNumber(String content, String regex, int group) {
        Matcher matcher = Pattern.compile(regex).matcher(content == null ? "" : content);
        return matcher.find() ? parseNumber(matcher.group(group)) : null;
    }

    private static Double parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = NUMBER_TOKEN.matcher(raw.replace("~", ""));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group().replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double bytesToMb(Double bytes) {
        return bytes == null ? null : bytes / (1024.0d * 1024.0d);
    }

    private static Double kbToMb(Double kb) {
        return kb == null ? null : kb / 1024.0d;
    }

    private static Double percent(Double part, Double total) {
        if (part == null || total == null || total <= 0.0d) {
            return null;
        }
        return part * 100.0d / total;
    }

    private static boolean containsIgnoreCase(String text, String token) {
        return text != null && token != null
                && text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private static String formatNumber(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return "";
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private static String siteCodeFromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        Matcher suffix = CMD_SET_SUFFIX.matcher(fileName);
        int suffixStart = suffix.find() ? suffix.start() : fileName.length();
        Matcher ip = IP_FROM_FILE.matcher(fileName);
        int siteStart = ip.find() ? ip.end() : 0;
        if (siteStart >= suffixStart) {
            return "";
        }
        return fileName.substring(siteStart, suffixStart).trim();
    }

    public static final class ExportResult {

        private final File outputFile;
        private int total;
        private int exported;
        private int partial;
        private int noData;

        private ExportResult(File outputFile) {
            this.outputFile = outputFile;
        }

        public File getOutputFile() {
            return outputFile;
        }

        public int getTotal() {
            return total;
        }

        public int getExported() {
            return exported;
        }

        public int getPartial() {
            return partial;
        }

        public int getNoData() {
            return noData;
        }
    }

    public static final class CpuMemorySnapshot {

        public final String siteCode;
        public final String ipLoopback;
        public final Double cpuCurrentPercent;
        public final Double cpuIdlePercent;
        public final Double memoryTotalMb;
        public final Double memoryUsedMb;
        public final Double memoryFreeMb;
        public final Double memoryUsedPercent;
        public final Double memoryFreePercent;

        private CpuMemorySnapshot(CpuMemoryRow row) {
            this.siteCode = row.siteCode == null ? "" : row.siteCode;
            this.ipLoopback = row.ipLoopback == null ? "" : row.ipLoopback;
            this.cpuCurrentPercent = row.cpuCurrentPct;
            this.cpuIdlePercent = row.cpuIdlePct;
            this.memoryTotalMb = row.memoryTotalMb;
            this.memoryUsedMb = row.memoryUsedMb;
            this.memoryFreeMb = row.memoryFreeMb;
            this.memoryUsedPercent = row.memoryUsedPct;
            this.memoryFreePercent = row.memoryFreePct;
        }

        private static CpuMemorySnapshot from(CpuMemoryRow row) {
            return new CpuMemorySnapshot(row);
        }

        public boolean hasAnyData() {
            return cpuCurrentPercent != null || cpuIdlePercent != null
                    || memoryTotalMb != null || memoryUsedMb != null
                    || memoryFreeMb != null || memoryUsedPercent != null
                    || memoryFreePercent != null;
        }
    }

    private static final class CpuMemoryRow {

        String siteCode = "";
        String ipLoopback = "";
        Double cpuCurrentPct;
        Double cpuIdlePct;
        Double memoryTotalMb;
        Double memoryUsedMb;
        Double memoryFreeMb;
        Double memoryUsedPct;
        Double memoryFreePct;

        static CpuMemoryRow fromFile(File file) {
            CpuMemoryRow row = new CpuMemoryRow();
            Matcher ip = IP_FROM_FILE.matcher(file == null ? "" : file.getName());
            if (ip.find()) {
                row.ipLoopback = ip.group(1).trim();
            }
            return row;
        }

        void normalizeIdentity(File file) {
            if (siteCode == null || siteCode.trim().isEmpty()) {
                siteCode = siteCodeFromFileName(file == null ? "" : file.getName());
            }
            siteCode = siteCode == null ? "" : siteCode.trim();
            siteCode = siteCode.replaceFirst("^\\*?[A-Za-z]:", "");
        }

        boolean hasAnyCpuOrMemoryData() {
            return cpuCurrentPct != null || cpuIdlePct != null
                    || memoryTotalMb != null || memoryUsedMb != null
                    || memoryFreeMb != null || memoryUsedPct != null
                    || memoryFreePct != null;
        }

        boolean isComplete() {
            return siteCode != null && !siteCode.isEmpty()
                    && ipLoopback != null && !ipLoopback.isEmpty()
                    && cpuCurrentPct != null && cpuIdlePct != null
                    && memoryTotalMb != null && memoryUsedMb != null
                    && memoryFreeMb != null && memoryUsedPct != null
                    && memoryFreePct != null;
        }

        List<String> toCsvValues() {
            return Arrays.asList(
                    siteCode, ipLoopback,
                    formatNumber(cpuCurrentPct), formatNumber(cpuIdlePct),
                    formatNumber(memoryTotalMb), formatNumber(memoryUsedMb),
                    formatNumber(memoryFreeMb), formatNumber(memoryUsedPct),
                    formatNumber(memoryFreePct));
        }
    }

    private static final class ZteProcessor {

        final String role;
        final Double cpuCurrentPct;
        final Double memoryTotalMb;
        final Double memoryFreeMb;
        final Double memoryUsedPct;

        ZteProcessor(String role, Double cpuCurrentPct, Double memoryTotalMb,
                Double memoryFreeMb, Double memoryUsedPct) {
            this.role = role;
            this.cpuCurrentPct = cpuCurrentPct;
            this.memoryTotalMb = memoryTotalMb;
            this.memoryFreeMb = memoryFreeMb;
            this.memoryUsedPct = memoryUsedPct;
        }

        boolean isPreferredOver(ZteProcessor other) {
            int thisPriority = rolePriority(role);
            int otherPriority = rolePriority(other == null ? "" : other.role);
            if (thisPriority != otherPriority) {
                return thisPriority < otherPriority;
            }
            double thisCpu = cpuCurrentPct == null ? -1.0d : cpuCurrentPct;
            double otherCpu = other == null || other.cpuCurrentPct == null
                    ? -1.0d : other.cpuCurrentPct;
            return thisCpu > otherCpu;
        }

        private static int rolePriority(String role) {
            if ("MSC".equalsIgnoreCase(role)) {
                return 0;
            }
            if ("SSC".equalsIgnoreCase(role)) {
                return 1;
            }
            return 2;
        }
    }
}
