package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts an in-memory Link Optical transcript into live port snapshots. */
public final class LiveNodeHealthParser {

    private LiveNodeHealthParser() {
    }

    public static List<PortSnapshot> parsePorts(String node, String ip, String cmdSet, String transcript) {
        String vendorCmdSet = normalizeCmdSet(cmdSet);
        if (vendorCmdSet.isEmpty() || transcript == null || transcript.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String syntheticPath = "[0]" + safe(ip) + "_" + safe(node) + "_"
                + vendorCmdSet + "_LIVE.txt";
        try {
            Map<String, double[]> huaweiLaneAverages = vendorCmdSet.startsWith("HW")
                    ? parseHuaweiLaneAverages(transcript) : Collections.<String, double[]>emptyMap();
            String csv = Check_Link_Optical.Sub(new BufferedReader(new StringReader(transcript)), syntheticPath);
            List<PortSnapshot> detailed = new ArrayList<PortSnapshot>();
            String safeCsv = csv == null ? "" : csv.trim();
            for (String line : safeCsv.isEmpty() ? new String[0] : safeCsv.split("\\r?\\n")) {
                List<String> fields = parseCsvLine(line);
                if (fields.size() < 17) {
                    continue;
                }
                String port = field(fields, 2);
                if (vendorCmdSet.startsWith("N-") && port.startsWith("'")) {
                    port = port.substring(1);
                }
                if (port.isEmpty()) {
                    continue;
                }
                String currentState = field(fields, 3);
                String description = field(fields, 4);
                String speed = field(fields, 7);
                String wavelength = field(fields, 12);
                String distance = field(fields, 13);
                Double tx = number(field(fields, 14));
                Double rx = number(field(fields, 15));
                double[] laneAverage = huaweiLaneAverages.get(normalizePortKey(port));
                if (laneAverage != null) {
                    tx = laneAverage[0];
                    rx = laneAverage[1];
                }
                String rxWarning = field(fields, 16);
                boolean hasOptical = !wavelength.isEmpty() || !distance.isEmpty() || !rxWarning.isEmpty()
                        || meaningful(tx) || meaningful(rx);
                String portStatus = normalizePortStatus(currentState);
                String opticalStatus = opticalStatus(hasOptical, rx, rxWarning);
                detailed.add(new PortSnapshot(port, portStatus, description, speed,
                        wavelength, distance, tx, rx, rxWarning, hasOptical, opticalStatus));
            }
            if (vendorCmdSet.startsWith("N-")) {
                detailed.addAll(parseNokiaPortDetails(transcript));
                return mergePortSummary(parseNokiaPortSummary(transcript), detailed);
            }
            if (!vendorCmdSet.startsWith("HW")) {
                return Collections.unmodifiableList(detailed);
            }
            return mergePortSummary(parseHuaweiInterfaceDescription(transcript), detailed);
        } catch (Exception ignored) {
            if (vendorCmdSet.startsWith("N-")) {
                return Collections.unmodifiableList(parseNokiaPortSummary(transcript));
            }
            if (vendorCmdSet.startsWith("HW")) {
                return Collections.unmodifiableList(parseHuaweiInterfaceDescription(transcript));
            }
            return Collections.emptyList();
        }
    }

    private static List<PortSnapshot> parseNokiaPortSummary(String transcript) {
        Map<String, PortSnapshot> rows = new LinkedHashMap<String, PortSnapshot>();
        Map<String, String> descriptions = parseNokiaPortDescriptions(transcript);
        if (transcript == null || transcript.isEmpty()) {
            return new ArrayList<PortSnapshot>();
        }
        // Nokia SR OS "show port" summary columns are:
        // Port Id, Admin State, Link State (Yes/No), Port State (Up/Down), ...
        // Parse the operational Port State rather than the preceding Link flag.
        Pattern rowPattern = Pattern.compile(
                "^\\s*([A-Za-z0-9]+(?:/[A-Za-z0-9]+){1,4})\\s+"
                + "(Up|Down)\\s+(Yes|No)\\s+(Up|Down)\\s+(.+)$",
                Pattern.CASE_INSENSITIVE);
        for (String line : transcript.split("\\r?\\n")) {
            Matcher matcher = rowPattern.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String port = matcher.group(1);
            String state = normalizePortStatus(matcher.group(4));
            String speed = nokiaPortSpeed(line);
            String description = descriptions.get(normalizePortKey(port));
            PortSnapshot snapshot = new PortSnapshot(port, state, safe(description), speed,
                    "", "", null, null, "", false, "NO_DATA");
            rows.put(normalizePortKey(port), snapshot);
        }
        return new ArrayList<PortSnapshot>(rows.values());
    }

    private static Map<String, String> parseNokiaPortDescriptions(String transcript) {
        Map<String, String> descriptions = new LinkedHashMap<String, String>();
        if (transcript == null || transcript.isEmpty()) {
            return descriptions;
        }
        boolean inDescription = false;
        for (String line : transcript.split("\\r?\\n")) {
            String value = safe(line);
            if (value.matches("(?i)^\\S+#\\s*show\\s+port\\s+description\\s*$")) {
                inDescription = true;
                continue;
            }
            if (!inDescription) {
                continue;
            }
            if (value.matches("^\\S+#.*$") || value.startsWith("Connection closed")) {
                inDescription = false;
                continue;
            }
            if (value.isEmpty() || value.startsWith("=") || value.startsWith("-")
                    || value.startsWith("Port Descriptions") || value.startsWith("Port Id")
                    || value.equalsIgnoreCase("show port description")) {
                continue;
            }
            String[] fields = value.split("\\s{2,}", 2);
            if (fields.length == 2
                    && fields[0].matches("[A-Za-z0-9]+(?:/[A-Za-z0-9]+){1,4}")) {
                descriptions.put(normalizePortKey(fields[0]), fields[1].trim());
            }
        }
        return descriptions;
    }

    private static List<PortSnapshot> parseNokiaPortDetails(String transcript) {
        List<PortSnapshot> rows = new ArrayList<PortSnapshot>();
        if (transcript == null || transcript.isEmpty()) {
            return rows;
        }
        Pattern commandPattern = Pattern.compile(
                "(?im)^\\s*\\S+#\\s*show\\s+port\\s+"
                + "([A-Za-z0-9]+(?:/[A-Za-z0-9]+){1,4})\\s*$");
        Matcher commands = commandPattern.matcher(transcript);
        List<Integer> starts = new ArrayList<Integer>();
        List<Integer> ends = new ArrayList<Integer>();
        List<String> commandPorts = new ArrayList<String>();
        while (commands.find()) {
            starts.add(Integer.valueOf(commands.start()));
            ends.add(Integer.valueOf(commands.end()));
            commandPorts.add(commands.group(1));
        }
        for (int index = 0; index < commandPorts.size(); index++) {
            int blockStart = ends.get(index).intValue();
            int blockEnd = index + 1 < starts.size()
                    ? starts.get(index + 1).intValue() : transcript.length();
            String block = transcript.substring(blockStart, blockEnd);
            String port = commandPorts.get(index);

            Matcher interfaceMatcher = Pattern.compile(
                    "(?im)^\\s*Interface\\s*:\\s*([^\\s]+)").matcher(block);
            if (interfaceMatcher.find()) {
                port = interfaceMatcher.group(1);
            }
            String description = "";
            Matcher descriptionMatcher = Pattern.compile(
                    "(?ims)^\\s*Description\\s*:\\s*(.*?)^\\s*Interface\\s*:").matcher(block);
            if (descriptionMatcher.find()) {
                description = descriptionMatcher.group(1).replace("\"", "")
                        .replaceAll("\\s+", " ").trim();
            }
            String state = "UNKNOWN";
            Matcher stateMatcher = Pattern.compile(
                    "(?im)^\\s*Oper State\\s*:\\s*([A-Za-z]+)").matcher(block);
            if (stateMatcher.find()) {
                state = normalizePortStatus(stateMatcher.group(1));
            }
            String speed = "";
            Matcher speedMatcher = Pattern.compile(
                    "(?i)Oper Speed\\s*:\\s*([0-9.]+)\\s*([GMK]?bps)").matcher(block);
            if (speedMatcher.find()) {
                String unit = speedMatcher.group(2).toUpperCase(Locale.ROOT);
                speed = speedMatcher.group(1) + (unit.startsWith("G") ? "G"
                        : unit.startsWith("M") ? "M" : unit.startsWith("K") ? "K" : "");
            }
            String wavelength = "";
            Matcher wavelengthMatcher = Pattern.compile(
                    "(?im)^\\s*TX Laser Wavelength\\s*:\\s*([0-9.]+)\\s*nm").matcher(block);
            if (wavelengthMatcher.find()) {
                wavelength = wavelengthMatcher.group(1) + "nm";
            }
            String distance = "";
            Matcher distanceMatcher = Pattern.compile(
                    "(?im)^\\s*Link Length support\\s*:.*?([0-9.]+)\\s*(km|m)\\b").matcher(block);
            if (distanceMatcher.find()) {
                distance = distanceMatcher.group(1) + distanceMatcher.group(2).toLowerCase(Locale.ROOT);
            }
            Double tx = null;
            Matcher txMatcher = Pattern.compile(
                    "(?im)^\\s*Tx Output Power.*?\\)\\s*([-+0-9.]+)").matcher(block);
            if (txMatcher.find()) {
                tx = number(txMatcher.group(1));
            }
            Double rx = null;
            String warning = "";
            Matcher rxMatcher = Pattern.compile(
                    "(?im)^\\s*Rx Optical Power.*?\\)\\s*([-+0-9.]+)[!*]?\\s+"
                    + "([-+0-9.]+)[!*]?\\s+([-+0-9.]+)[!*]?\\s+"
                    + "([-+0-9.]+)[!*]?\\s+([-+0-9.]+)[!*]?")
                    .matcher(block);
            if (rxMatcher.find()) {
                rx = number(rxMatcher.group(1));
                warning = "[" + rxMatcher.group(4) + "<>" + rxMatcher.group(3) + "]";
            }
            boolean hasOptical = !wavelength.isEmpty() || !distance.isEmpty()
                    || meaningful(tx) || meaningful(rx);
            rows.add(new PortSnapshot(port, state, description, speed,
                    wavelength, distance, tx, rx, warning, hasOptical,
                    opticalStatus(hasOptical, rx, warning)));
        }
        return rows;
    }

    private static String nokiaPortSpeed(String line) {
        String value = safe(line).toUpperCase(Locale.ROOT);
        if (value.contains("400GBASE") || value.contains("400G")) return "400G";
        if (value.contains("100GBASE") || value.contains("C100G")) return "100G";
        if (value.contains("50GBASE") || value.contains("50G")) return "50G";
        if (value.contains("40GBASE") || value.contains("C40G")) return "40G";
        if (value.contains("25GBASE") || value.contains("25G")) return "25G";
        if (value.contains("10GBASE") || value.contains("XGIGE")) return "10G";
        if (value.contains("GIGE") || value.contains("XCME")) return "1G";
        if (value.contains("FASTE")) return "100M";
        return "";
    }

    private static List<PortSnapshot> parseHuaweiInterfaceDescription(String transcript) {
        List<PortSnapshot> rows = new ArrayList<PortSnapshot>();
        if (transcript == null || transcript.isEmpty()) {
            return rows;
        }
        Pattern rowPattern = Pattern.compile(
                "^\\s*((?:100GE|50GE|40GE|25GE|10GE|XGigabitEthernet|GigabitEthernet|GE|Ethernet)"
                + "[0-9]+(?:/[0-9]+){2,4}(?:\\([^)]*\\))?)\\s+"
                + "(up|down|\\*down)\\s+(\\S+)(?:\\s+(.*))?$",
                Pattern.CASE_INSENSITIVE);
        for (String line : transcript.split("\\r?\\n")) {
            Matcher matcher = rowPattern.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String rawPort = matcher.group(1);
            String port = rawPort.replaceFirst("\\([^)]*\\)$", "");
            String state = normalizePortStatus(matcher.group(2));
            String description = safe(matcher.group(4));
            if ("--".equals(description) || "-".equals(description)) {
                description = "";
            }
            rows.add(new PortSnapshot(port, state, description, huaweiPortSpeed(rawPort),
                    "", "", null, null, "", false, "NO_DATA"));
        }
        return rows;
    }

    private static Map<String, double[]> parseHuaweiLaneAverages(String transcript) {
        Map<String, double[]> result = new LinkedHashMap<String, double[]>();
        if (transcript == null || transcript.isEmpty()) {
            return result;
        }
        Pattern portPattern = Pattern.compile(
                "^\\s*((?:100GE|50GE|40GE|25GE|10GE|XGigabitEthernet|GigabitEthernet|GE|Ethernet)"
                + "[0-9]+(?:/[0-9]+){2,4})\\s+current state\\s*:", Pattern.CASE_INSENSITIVE);
        Pattern lanePattern = Pattern.compile(
                "Rx\\d+\\s+Power:\\s*([-+0-9.]+)dBm,\\s*Tx\\d+\\s+Power:\\s*([-+0-9.]+)dBm",
                Pattern.CASE_INSENSITIVE);
        String currentPort = "";
        double rxSum = 0.0d;
        double txSum = 0.0d;
        int laneCount = 0;
        for (String line : transcript.split("\\r?\\n")) {
            Matcher portMatcher = portPattern.matcher(line);
            if (portMatcher.find()) {
                putHuaweiLaneAverage(result, currentPort, txSum, rxSum, laneCount);
                currentPort = portMatcher.group(1);
                rxSum = txSum = 0.0d;
                laneCount = 0;
                continue;
            }
            if (currentPort.isEmpty()) {
                continue;
            }
            Matcher laneMatcher = lanePattern.matcher(line);
            while (laneMatcher.find()) {
                rxSum += Double.parseDouble(laneMatcher.group(1));
                txSum += Double.parseDouble(laneMatcher.group(2));
                laneCount++;
            }
        }
        putHuaweiLaneAverage(result, currentPort, txSum, rxSum, laneCount);
        return result;
    }

    private static void putHuaweiLaneAverage(Map<String, double[]> result, String port,
            double txSum, double rxSum, int laneCount) {
        if (result == null || safe(port).isEmpty() || laneCount <= 1) {
            return;
        }
        result.put(normalizePortKey(port), new double[]{txSum / laneCount, rxSum / laneCount});
    }

    private static List<PortSnapshot> mergePortSummary(List<PortSnapshot> summary,
            List<PortSnapshot> detailed) {
        Map<String, PortSnapshot> merged = new LinkedHashMap<String, PortSnapshot>();
        for (PortSnapshot port : summary) {
            merged.put(normalizePortKey(port.port), port);
        }
        for (PortSnapshot detail : detailed) {
            String key = normalizePortKey(detail.port);
            PortSnapshot compact = merged.get(key);
            if (compact == null) {
                merged.put(key, detail);
                continue;
            }
            boolean useDetailOptical = detail.hasOpticalData || !compact.hasOpticalData;
            merged.put(key, new PortSnapshot(detail.port,
                    "UNKNOWN".equals(detail.portStatus) ? compact.portStatus : detail.portStatus,
                    detail.description.isEmpty() ? compact.description : detail.description,
                    detail.speed.isEmpty() ? compact.speed : detail.speed,
                    useDetailOptical ? detail.wavelength : compact.wavelength,
                    useDetailOptical ? detail.distance : compact.distance,
                    useDetailOptical ? detail.txPowerDbm : compact.txPowerDbm,
                    useDetailOptical ? detail.rxPowerDbm : compact.rxPowerDbm,
                    useDetailOptical ? detail.rxWarningRange : compact.rxWarningRange,
                    useDetailOptical ? detail.hasOpticalData : compact.hasOpticalData,
                    useDetailOptical ? detail.opticalStatus : compact.opticalStatus));
        }
        return Collections.unmodifiableList(new ArrayList<PortSnapshot>(merged.values()));
    }

    private static String normalizePortKey(String port) {
        String value = safe(port).replaceFirst("^'+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (value.matches("^ge[0-9].*")) {
            value = "gigabitethernet" + value.substring(2);
        }
        return value;
    }

    private static String huaweiPortSpeed(String port) {
        String value = safe(port).toUpperCase(Locale.ROOT);
        Matcher hint = Pattern.compile("\\((400G|200G|100G|50G|40G|25G|10G|1G|100M)\\)")
                .matcher(value);
        if (hint.find()) return hint.group(1);
        if (value.startsWith("100GE")) return "100G";
        if (value.startsWith("50GE")) return "50G";
        if (value.startsWith("40GE")) return "40G";
        if (value.startsWith("25GE")) return "25G";
        if (value.startsWith("10GE") || value.startsWith("XGIGABITETHERNET")) return "10G";
        if (value.startsWith("GE") || value.startsWith("GIGABITETHERNET")) return "1G";
        return "";
    }

    private static String normalizeCmdSet(String cmdSet) {
        String value = safe(cmdSet).toUpperCase(Locale.ROOT);
        if (value.startsWith("N")) {
            return "N-LLDP-Link_OPTIC";
        }
        if (value.startsWith("Z")) {
            return "ZTE-LLDP-Link_OPTIC";
        }
        if (value.startsWith("H")) {
            return "HW-LLDP-Link_OPTIC";
        }
        return "";
    }

    private static String normalizePortStatus(String value) {
        String state = safe(value).toUpperCase(Locale.ROOT);
        if (state.contains("UP")) {
            return "UP";
        }
        if (state.contains("DOWN") || state.contains("OFFLINE")) {
            return "DOWN";
        }
        return state.isEmpty() ? "UNKNOWN" : state;
    }

    private static String opticalStatus(boolean hasOptical, Double rx, String warning) {
        if (!hasOptical) {
            return "NO_DATA";
        }
        List<Double> bounds = numbers(warning);
        if (rx != null && !bounds.isEmpty()) {
            double minimum = bounds.get(0).doubleValue();
            double maximum = bounds.size() > 1 ? bounds.get(1).doubleValue() : Double.POSITIVE_INFINITY;
            if (rx.doubleValue() < minimum || rx.doubleValue() > maximum) {
                return "ALARM";
            }
        }
        return rx == null ? "NO_DATA" : "NORMAL";
    }

    private static List<Double> numbers(String value) {
        List<Double> values = new ArrayList<Double>();
        if (value == null) return values;
        Matcher matcher = Pattern.compile("[-+]?[0-9]+(?:\\.[0-9]+)?").matcher(value);
        while (matcher.find() && values.size() < 2) {
            Double parsed = number(matcher.group());
            if (parsed != null) values.add(parsed);
        }
        return values;
    }

    private static boolean meaningful(Double value) {
        return value != null && Math.abs(value.doubleValue()) > 0.0001d;
    }

    private static Double number(String value) {
        String text = safe(value);
        if (text.isEmpty() || "N/A".equalsIgnoreCase(text) || "-".equals(text)) {
            return null;
        }
        try {
            return Double.valueOf(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
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
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    private static String field(List<String> fields, int index) {
        return index >= 0 && index < fields.size() ? safe(fields.get(index)) : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class PortSnapshot {
        public final String port;
        public final String portStatus;
        public final String description;
        public final String speed;
        public final String wavelength;
        public final String distance;
        public final Double txPowerDbm;
        public final Double rxPowerDbm;
        public final String rxWarningRange;
        public final boolean hasOpticalData;
        public final String opticalStatus;

        PortSnapshot(String port, String portStatus, String description, String speed,
                String wavelength, String distance, Double txPowerDbm, Double rxPowerDbm,
                String rxWarningRange, boolean hasOpticalData, String opticalStatus) {
            this.port = safe(port);
            this.portStatus = safe(portStatus);
            this.description = safe(description);
            this.speed = safe(speed);
            this.wavelength = safe(wavelength);
            this.distance = safe(distance);
            this.txPowerDbm = txPowerDbm;
            this.rxPowerDbm = rxPowerDbm;
            this.rxWarningRange = safe(rxWarningRange);
            this.hasOpticalData = hasOpticalData;
            this.opticalStatus = safe(opticalStatus);
        }
    }
}
