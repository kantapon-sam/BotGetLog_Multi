package com.java.botgetlog.truecorp;

import com.java.tools.linkoptical.Link_Optical;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

final class TrueLinkOpticalInputUpdater {

    private static final String DEVICE_SHEET = "deviceList_TRUE";
    private static final String DEFAULT_RUN = "Y";
    private static final String DEFAULT_CMDSET = "N-LLDP-Link_OPTIC";
    private static final String HUAWEI_CMDSET = "HW-LLDP-Link_OPTIC";
    private static final String ZTE_CMDSET = "ZTE-LLDP-Link_OPTIC";
    // Descriptions commonly put the destination IP after an underscore. An
    // underscore is a regex word character, so a leading \\b misses that IP.
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "(?<![0-9.])(\\d{1,3}(?:\\.\\d{1,3}){3})(?![0-9.])");
    private static final Pattern HUAWEI_HINT_PATTERN = Pattern.compile(
            "(?i)(?:HUAWEI|H910C(?:-A)?|(?:^|[^A-Z0-9])ATN(?:[-_ ]?9\\d{2,3})?(?:$|[^A-Z0-9]))");
    private static final Pattern ZTE_HINT_PATTERN = Pattern.compile(
            "(?i)(?:ZTE|ZXCTN|M6000|ZXR10)");
    private static final Pattern GROUP_PATTERN = Pattern.compile(
            "^(CPE|AGN|AN|DN|PN|RN|GM|GRD|GRP|SD|GA|SA)(?:[-_]|\\d|$)");
    private static final Pattern MPLS_TOKEN_PATTERN = Pattern.compile("(^|[-_])MPLS($|[-_])");
    private static final Pattern MPLS_ALIAS_PATTERN = Pattern.compile("^W\\d{5}[A-Z]?$");
    private static final Pattern SWITCH_MODEL_TOKEN_PATTERN
            = Pattern.compile("(^|[-_])S\\d{4}[A-Z0-9-]*(?=$|[-_])");
    private static final Pattern INFRASTRUCTURE_NEIGHBOR_ALIAS_PATTERN = Pattern.compile(
            "^(?:RN|PN|DN|AN)\\d?-[A-Z0-9]+-(?:AC|AG|CO)-[A-Z0-9-]+$");
    private static final Pattern TRUE_FORBIDDEN_NODE_TOKEN_PATTERN = Pattern.compile(
            "-(?:AG|CO|AC)-");
    private static final Pattern TRUE_BLOCKED_NAME_FRAGMENT_PATTERN = Pattern.compile(
            "(?:NWCN|CGN|MBCN)");
    private static final Pattern TRUE_SITE_TOKEN_PATTERN = Pattern.compile(
            "[A-Z]{3,5}\\d{3,5}[A-Z]?");
    private static final String[] TRUE_AUTO_ADD_IP_PREFIXES = new String[]{
        "10.185.",
        "10.85.",
        "10.150.",
        "10.163.",
        "10.167.",
        "10.207.",
        "10.165."
    };
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private TrueLinkOpticalInputUpdater() {
    }

    static UpdateResult updateFromLinkOptical(PathFile fileInput, Link_Optical.ProcessResult exportResult) {
        if (fileInput == null || exportResult == null || exportResult.getNeighborFile() == null) {
            return UpdateResult.empty();
        }
        File neighborFile = exportResult.getNeighborFile();
        if (!neighborFile.isFile()) {
            return UpdateResult.empty();
        }

        try {
            File excelFile = new File(fileInput.getUserInterface_Input());
            if (!excelFile.isFile()) {
                System.out.println("[AUTO-INPUT] UserInterface_Input.xlsx not found: " + excelFile.getAbsolutePath());
                return UpdateResult.empty();
            }

            List<DiscoveredNode> discovered = readDiscoveredNodes(neighborFile);
            if (discovered.isEmpty()) {
                System.out.println("[AUTO-INPUT] No neighbor node with usable destination IP was found.");
                return UpdateResult.noChanges();
            }

            return writeMissingNodes(fileInput, excelFile, discovered);
        } catch (Exception e) {
            System.out.println("[AUTO-INPUT] Failed to update UserInterface_Input.xlsx: " + e.getMessage());
            return UpdateResult.empty();
        }
    }

    static UpdateResult updateFromDataQualityFile(PathFile fileInput, File dataQualityFile) {
        if (fileInput == null || dataQualityFile == null || !dataQualityFile.isFile()) {
            return UpdateResult.empty();
        }
        try {
            File excelFile = new File(fileInput.getUserInterface_Input());
            if (!excelFile.isFile()) {
                System.out.println("[AUTO-INPUT] UserInterface_Input.xlsx not found: " + excelFile.getAbsolutePath());
                return UpdateResult.empty();
            }

            List<DiscoveredNode> discovered = readDiscoveredNodesFromDataQuality(dataQualityFile);
            if (discovered.isEmpty()) {
                System.out.println("[AUTO-INPUT] No usable missing destination node found in "
                        + dataQualityFile.getAbsolutePath());
                return UpdateResult.noChanges();
            }

            return writeMissingNodes(fileInput, excelFile, discovered, "lldp-data-quality");
        } catch (Exception e) {
            System.out.println("[AUTO-INPUT] Failed to import LLDP data quality file: " + e.getMessage());
            return UpdateResult.empty();
        }
    }

    private static List<DiscoveredNode> readDiscoveredNodes(File neighborFile) throws Exception {
        List<DiscoveredNode> nodes = new ArrayList<DiscoveredNode>();
        try (BufferedReader reader = new BufferedReader(new FileReader(neighborFile))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return nodes;
            }
            Map<String, Integer> header = headerIndex(splitCsvLine(headerLine));
            int sourceIpIdx = findIndex(header, "IP loopback");
            int descIdx = findIndex(header, "Description");
            int neighborIdx = findIndex(header, "Neighbor SysName");
            int neighborDesIdx = findIndex(header, "NeighborDes");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> cols = splitCsvLine(line);
                String sourceIp = valueAt(cols, sourceIpIdx);
                String description = valueAt(cols, descIdx);
                String neighborSysName = valueAt(cols, neighborIdx);
                String neighborDes = valueAt(cols, neighborDesIdx);
                String deviceName = normalizeDeviceName(!neighborDes.isEmpty() ? neighborDes : neighborSysName);
                String ip = extractDestinationIp(sourceIp, description, neighborSysName, neighborDes);
                if (deviceName.isEmpty() || ip.isEmpty()
                        || !isAllowedTrueAutoAddIp(ip)
                        || !isEligibleDiscoveredNodeName(deviceName)
                        || !descriptionMatchesDiscoveredNode(deviceName, description, neighborDes)
                        || isBlockedDiscoveredNodeName(deviceName, neighborSysName, neighborDes, description)) {
                    continue;
                }
                nodes.add(new DiscoveredNode(deviceName, ip, sourceIp, description));
            }
        }
        return nodes;
    }

    static List<String> readDiscoveredDeviceNamesForTesting(File neighborFile) throws Exception {
        List<String> names = new ArrayList<String>();
        for (DiscoveredNode node : readDiscoveredNodes(neighborFile)) {
            names.add(node.deviceName);
        }
        return names;
    }

    private static List<DiscoveredNode> readDiscoveredNodesFromDataQuality(File dataQualityFile) throws Exception {
        List<DiscoveredNode> nodes = new ArrayList<DiscoveredNode>();
        try (BufferedReader reader = new BufferedReader(new FileReader(dataQualityFile))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return nodes;
            }
            Map<String, Integer> header = headerIndex(splitCsvLine(headerLine));
            int categoryIdx = findIndex(header, "CATEGORY");
            int targetIdx = findIndex(header, "TARGET");
            int targetHasSiteIdx = findIndex(header, "TARGET_HAS_SITE_CODE");
            int targetIpIdx = findIndex(header, "TARGET_IP_CANDIDATE");
            int sourceIpIdx = findIndex(header, "SOURCE_IP");
            int descIdx = findIndex(header, "DESCRIPTION");
            int detailIdx = findIndex(header, "DETAIL");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> cols = splitCsvLine(line);
                String category = valueAt(cols, categoryIdx);
                String targetHasSite = valueAt(cols, targetHasSiteIdx);
                if (!"ONE_WAY_LLDP_PAIR".equalsIgnoreCase(category)
                        || !"false".equalsIgnoreCase(targetHasSite)) {
                    continue;
                }

                String target = valueAt(cols, targetIdx);
                String description = valueAt(cols, descIdx);
                String detail = valueAt(cols, detailIdx);
                String deviceName = normalizeDeviceName(target);
                String ip = normalizeIp(valueAt(cols, targetIpIdx));
                String sourceIp = normalizeIp(valueAt(cols, sourceIpIdx));
                if (deviceName.isEmpty() || ip.isEmpty() || ip.equals(sourceIp)
                        || !isAllowedTrueAutoAddIp(ip)
                        || !isEligibleDiscoveredNodeName(deviceName)
                        || !descriptionMatchesDiscoveredNode(deviceName, description, detail)
                        || isBlockedDiscoveredNodeName(deviceName, target, description, detail)) {
                    continue;
                }
                nodes.add(new DiscoveredNode(
                        deviceName,
                        ip,
                        sourceIp,
                        description,
                        detail));
            }
        }
        return nodes;
    }

    private static UpdateResult writeMissingNodes(PathFile fileInput, File excelFile, List<DiscoveredNode> discovered) throws Exception {
        return writeMissingNodes(fileInput, excelFile, discovered, "link-optical");
    }

    private static UpdateResult writeMissingNodes(PathFile fileInput, File excelFile,
            List<DiscoveredNode> discovered, String sourceLabel) throws Exception {
        File backup = backupWorkbook(fileInput, excelFile);
        int added = 0;
        int duplicateIp = 0;
        int duplicateDevice = 0;
        int duplicateInRun = 0;
        List<String> addedDevices = new ArrayList<String>();
        File reportFile = createReportFile(fileInput, sourceLabel);

        try (Workbook workbook = WorkbookFactory.create(new BufferedInputStream(new FileInputStream(excelFile)))) {
            Sheet sheet = workbook.getSheet(DEVICE_SHEET);
            if (sheet == null) {
                System.out.println("[AUTO-INPUT] Missing sheet: " + DEVICE_SHEET);
                return UpdateResult.failed(reportFile);
            }

            Set<String> existingIps = new LinkedHashSet<String>();
            Set<String> existingDeviceKeys = new LinkedHashSet<String>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String device = normalizeDeviceName(getCell(row, 2));
                String ip = normalizeIp(getCell(row, 3));
                if (!device.isEmpty()) {
                    existingDeviceKeys.add(deviceIdentityKey(device));
                }
                if (!ip.isEmpty()) {
                    existingIps.add(ip);
                }
            }

            Map<String, DiscoveredNode> uniqueByIp = new LinkedHashMap<String, DiscoveredNode>();
            Set<String> uniqueDeviceKeys = new LinkedHashSet<String>();
            for (DiscoveredNode node : discovered) {
                if (!isAllowedTrueAutoAddIp(node.ip)
                        || !isEligibleDiscoveredNodeName(node.deviceName)
                        || !descriptionMatchesDiscoveredNode(node.deviceName, node.description, node.detail)
                        || isBlockedDiscoveredNodeName(node.deviceName, node.description, node.detail)) {
                    continue;
                }
                if (existingIps.contains(node.ip)) {
                    duplicateIp++;
                    continue;
                }
                String deviceKey = deviceIdentityKey(node.deviceName);
                if (existingDeviceKeys.contains(deviceKey)) {
                    duplicateDevice++;
                    continue;
                }
                if (uniqueDeviceKeys.contains(deviceKey)) {
                    duplicateInRun++;
                    continue;
                }
                if (uniqueByIp.containsKey(node.ip)) {
                    duplicateInRun++;
                    continue;
                }
                uniqueByIp.put(node.ip, node);
                uniqueDeviceKeys.add(deviceKey);
            }

            int nextRowIndex = sheet.getLastRowNum() + 1;
            Row styleRow = findStyleRow(sheet);
            for (DiscoveredNode node : uniqueByIp.values()) {
                Row row = sheet.createRow(nextRowIndex++);
                copyRowStyle(styleRow, row, 5);
                setCell(row, 0, DEFAULT_RUN);
                setCell(row, 1, node.group);
                setCell(row, 2, node.deviceName);
                setCell(row, 3, node.ip);
                setCell(row, 4, node.cmdSet);
                existingIps.add(node.ip);
                existingDeviceKeys.add(deviceIdentityKey(node.deviceName));
                addedDevices.add(node.deviceName);
                added++;
            }

            try (FileOutputStream out = new FileOutputStream(excelFile)) {
                workbook.write(out);
            }
        }

        writeReport(reportFile, discovered, added, duplicateIp, duplicateDevice, duplicateInRun, backup);
        System.out.printf(Locale.ROOT,
                "[AUTO-INPUT] UserInterface_Input update (%s): added=%d duplicateIp=%d duplicateDevice=%d duplicateInRun=%d backup=%s report=%s%n",
                sourceLabel,
                added, duplicateIp, duplicateDevice, duplicateInRun,
                backup == null ? "" : backup.getAbsolutePath(),
                reportFile == null ? "" : reportFile.getAbsolutePath());
        return new UpdateResult(added, duplicateIp, duplicateDevice, duplicateInRun,
                reportFile, addedDevices, true);
    }

    private static File backupWorkbook(PathFile fileInput, File excelFile) throws Exception {
        File dir = new File(new File(new File(fileInput.getCurrentFolder(), "_output"), "System_Log"), "Input_Backup");
        dir.mkdirs();
        File backup = new File(dir, "UserInterface_Input_" + LocalDateTime.now().format(TS) + ".xlsx");
        Files.copy(excelFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static File createReportFile(PathFile fileInput, String sourceLabel) {
        try {
            File dir = new File(new File(fileInput.getCurrentFolder(), "_output"), "System_Log");
            dir.mkdirs();
            String safeLabel = sourceLabel == null ? "link-optical" : sourceLabel.replaceAll("[^A-Za-z0-9_-]", "-");
            return new File(dir, "true-linkoptical-discovered-nodes-"
                    + safeLabel + "-" + LocalDateTime.now().format(TS) + ".csv");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeReport(File reportFile, List<DiscoveredNode> discovered,
            int added, int duplicateIp, int duplicateDevice, int duplicateInRun, File backup) {
        if (reportFile == null) {
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile, false))) {
            writer.write("summary,added,duplicateIp,duplicateDevice,duplicateInRun,backup\n");
            writer.write("result," + added + "," + duplicateIp + "," + duplicateDevice + "," + duplicateInRun
                    + "," + csv(backup == null ? "" : backup.getAbsolutePath()) + "\n\n");
            writer.write("deviceName,ip,sourceIp,group,cmdSet,description,detail\n");
            for (DiscoveredNode node : discovered) {
                writer.write(csv(node.deviceName) + "," + csv(node.ip) + "," + csv(node.sourceIp)
                        + "," + csv(node.group) + "," + csv(node.cmdSet)
                        + "," + csv(node.description) + "," + csv(node.detail) + "\n");
            }
        } catch (Exception e) {
            System.out.println("[AUTO-INPUT] Failed to write discovery report: " + e.getMessage());
        }
    }

    private static Row findStyleRow(Sheet sheet) {
        for (int r = sheet.getLastRowNum(); r >= 1; r--) {
            Row row = sheet.getRow(r);
            if (row != null && !getCell(row, 2).trim().isEmpty() && !getCell(row, 3).trim().isEmpty()) {
                return row;
            }
        }
        return sheet.getRow(0);
    }

    private static void copyRowStyle(Row source, Row target, int cellCount) {
        if (source == null || target == null) {
            return;
        }
        target.setHeight(source.getHeight());
        for (int i = 0; i < cellCount; i++) {
            Cell sourceCell = source.getCell(i);
            Cell targetCell = target.createCell(i);
            if (sourceCell != null) {
                CellStyle style = sourceCell.getCellStyle();
                if (style != null) {
                    targetCell.setCellStyle(style);
                }
            }
        }
    }

    private static void setCell(Row row, int col, String value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private static String getCell(Row row, int col) {
        return row == null ? "" : BotGetLog_TrueCorp.getCell(row, col);
    }

    private static Map<String, Integer> headerIndex(List<String> columns) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        for (int i = 0; i < columns.size(); i++) {
            map.put(normalizeHeader(columns.get(i)), i);
        }
        return map;
    }

    private static int findIndex(Map<String, Integer> header, String name) {
        Integer idx = header.get(normalizeHeader(name));
        return idx == null ? -1 : idx.intValue();
    }

    private static String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static String valueAt(List<String> cols, int idx) {
        if (idx < 0 || idx >= cols.size()) {
            return "";
        }
        return cols.get(idx) == null ? "" : cols.get(idx).trim();
    }

    private static String normalizeDeviceName(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) {
            return "";
        }
        return v.replaceAll("\\s+", "_");
    }

    static String deviceIdentityKey(String value) {
        return normalizeDeviceName(value).replaceAll("[^A-Z0-9]", "");
    }

    static boolean isBlockedDiscoveredNodeName(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            String normalized = normalizeDeviceName(value);
            if (normalized.isEmpty()) {
                continue;
            }
            if (MPLS_TOKEN_PATTERN.matcher(normalized).find()
                    || MPLS_ALIAS_PATTERN.matcher(normalized).matches()
                    || SWITCH_MODEL_TOKEN_PATTERN.matcher(normalized).find()
                    || TRUE_BLOCKED_NAME_FRAGMENT_PATTERN.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean isEligibleDiscoveredNodeName(String deviceName) {
        String normalized = normalizeDeviceName(deviceName);
        if (normalized.isEmpty() || normalized.contains(".")
                || normalized.endsWith("-") || normalized.endsWith("_")) {
            return false;
        }
        return !inferGroup(normalized).isEmpty()
                && !INFRASTRUCTURE_NEIGHBOR_ALIAS_PATTERN.matcher(normalized).matches()
                && !TRUE_FORBIDDEN_NODE_TOKEN_PATTERN.matcher(normalized).find()
                && !TRUE_BLOCKED_NAME_FRAGMENT_PATTERN.matcher(normalized).find();
    }

    static boolean descriptionMatchesDiscoveredNode(String deviceName, String... descriptions) {
        String normalized = normalizeDeviceName(deviceName);
        if (normalized.isEmpty()) {
            return false;
        }
        String host = normalized;
        int dot = host.indexOf('.');
        if (dot >= 0) {
            host = host.substring(0, dot);
        }

        StringBuilder haystack = new StringBuilder();
        if (descriptions != null) {
            for (String description : descriptions) {
                if (description != null && !description.trim().isEmpty()) {
                    if (haystack.length() > 0) {
                        haystack.append(' ');
                    }
                    haystack.append(description.toUpperCase(Locale.ROOT).replaceAll("\\s+", "_"));
                }
            }
        }
        if (haystack.length() == 0) {
            return false;
        }
        String searchable = haystack.toString();
        if (host.length() >= 5 && searchable.contains(host)) {
            return true;
        }

        Matcher tokenMatcher = TRUE_SITE_TOKEN_PATTERN.matcher(host);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group();
            if (searchable.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIp(String value) {
        String ip = value == null ? "" : value.trim();
        return isValidIpv4(ip) ? ip : "";
    }

    static boolean isAllowedTrueAutoAddIp(String value) {
        String ip = normalizeIp(value);
        if (ip.isEmpty()) {
            return false;
        }
        for (String prefix : TRUE_AUTO_ADD_IP_PREFIXES) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static String extractDestinationIp(String sourceIp, String description, String neighborSysName, String neighborDes) {
        String source = normalizeIp(sourceIp);
        String haystack = safe(description) + " " + safe(neighborSysName) + " " + safe(neighborDes);
        Matcher matcher = IPV4_PATTERN.matcher(haystack);
        String last = "";
        while (matcher.find()) {
            String ip = matcher.group(1);
            if (isValidIpv4(ip) && !ip.equals(source)) {
                last = ip;
            }
        }
        return last;
    }

    static String inferGroup(String deviceName) {
        Matcher matcher = GROUP_PATTERN.matcher(normalizeDeviceName(deviceName));
        return matcher.find() ? matcher.group(1) : "";
    }

    static String inferCmdSet(String deviceName, String description, String detail) {
        String hints = safe(deviceName) + " " + safe(description) + " " + safe(detail);
        if (HUAWEI_HINT_PATTERN.matcher(hints).find()) {
            return HUAWEI_CMDSET;
        }
        if (ZTE_HINT_PATTERN.matcher(hints).find()) {
            return ZTE_CMDSET;
        }
        return DEFAULT_CMDSET;
    }

    private static boolean isValidIpv4(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (part.length() > 1 && part.startsWith("0")) {
                    return false;
                }
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return !"0.0.0.0".equals(ip) && !"255.255.255.255".equals(ip);
    }

    private static List<String> splitCsvLine(String line) {
        List<String> cols = new ArrayList<String>();
        if (line == null) {
            return cols;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cols.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cols.add(current.toString());
        return cols;
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class UpdateResult {

        final int added;
        final int duplicateIp;
        final int duplicateDevice;
        final int duplicateInRun;
        final File reportFile;
        final List<String> addedDevices;
        final boolean successful;

        UpdateResult(int added, int duplicateIp, int duplicateDevice, int duplicateInRun,
                File reportFile, List<String> addedDevices, boolean successful) {
            this.added = added;
            this.duplicateIp = duplicateIp;
            this.duplicateDevice = duplicateDevice;
            this.duplicateInRun = duplicateInRun;
            this.reportFile = reportFile;
            this.addedDevices = Collections.unmodifiableList(new ArrayList<String>(
                    addedDevices == null ? Collections.<String>emptyList() : addedDevices));
            this.successful = successful;
        }

        static UpdateResult empty() {
            return failed(null);
        }

        static UpdateResult noChanges() {
            return new UpdateResult(0, 0, 0, 0, null,
                    Collections.<String>emptyList(), true);
        }

        static UpdateResult failed(File reportFile) {
            return new UpdateResult(0, 0, 0, 0, reportFile,
                    Collections.<String>emptyList(), false);
        }
    }

    private static final class DiscoveredNode {

        final String deviceName;
        final String ip;
        final String sourceIp;
        final String group;
        final String cmdSet;
        final String description;
        final String detail;

        DiscoveredNode(String deviceName, String ip, String sourceIp, String description) {
            this(deviceName, ip, sourceIp, description, "");
        }

        DiscoveredNode(String deviceName, String ip, String sourceIp, String description, String detail) {
            this.deviceName = deviceName;
            this.ip = ip;
            this.sourceIp = sourceIp;
            this.group = inferGroup(deviceName);
            this.cmdSet = inferCmdSet(deviceName, description, detail);
            this.description = description;
            this.detail = detail;
        }
    }
}
