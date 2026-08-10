package com.java.botgetlog.truecorp;

import com.java.tools.linkoptical.CpuMemoryExporter;
import com.java.tools.linkoptical.LiveNodeHealthParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One-shot, read-only node probe for MapViewer Live Node Monitor. */
public final class TrueLiveNodeProbeCli {

    private static final String RESULT_MARKER = "LIVE_NODE_RESULT_JSON=";

    private TrueLiveNodeProbeCli() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        String ip = value(options, "ip");
        String node = value(options, "node");
        String cmdSet = value(options, "cmdset");
        String metrics = normalizeMetrics(value(options, "metrics"));
        String nodeType = value(options, "type");
        List<String> selectedPorts = parsePortList(value(options, "ports"));
        if (ip.isEmpty() || node.isEmpty() || cmdSet.isEmpty()) {
            printResult(errorJson(node, ip, cmdSet, "INVALID_REQUEST",
                    "--ip, --node and --cmdset are required.", 0L));
            System.exit(2);
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            BotGetLog_TrueCorp.LiveProbeConfig config = BotGetLog_TrueCorp.loadLiveProbeConfig();
            if (!config.isComplete()) {
                printResult(errorJson(node, ip, cmdSet, "MISSING_CREDENTIALS",
                        "Live probe credentials are not configured.", elapsed(startedAt)));
                System.exit(3);
                return;
            }

            List<String> commands = commandsFor(cmdSet, metrics, node, nodeType, selectedPorts);
            if (commands.isEmpty()) {
                printResult(errorJson(node, ip, cmdSet, "UNSUPPORTED_VENDOR",
                        "Live monitoring supports Nokia, ZTE and Huawei command sets.", elapsed(startedAt)));
                System.exit(4);
                return;
            }

            Telnet_Multi.LiveProbeResult lastResult = null;
            for (String gateway : config.gatewayServers) {
                lastResult = Telnet_Multi.runLiveProbe(gateway,
                        config.gatewayUsername, config.gatewayPassword,
                        ip, config.nodeUsername, config.nodePassword,
                        cmdSet, node, commands, includesOptical(metrics)
                                && (isNokia(cmdSet) || isHuawei(cmdSet)));
                if (lastResult.success || "INVALID_CREDENTIALS".equals(lastResult.status)) {
                    break;
                }
            }

            if (lastResult == null || !lastResult.success) {
                String status = lastResult == null ? "NO_GATEWAY" : lastResult.status;
                String message = lastResult == null ? "No TRUE gateway is configured." : lastResult.message;
                long elapsedMs = lastResult == null ? elapsed(startedAt) : lastResult.elapsedMs;
                printResult(errorJson(node, ip, cmdSet, status, message, elapsedMs));
                System.exit(5);
                return;
            }

            CpuMemoryExporter.CpuMemorySnapshot snapshot
                    = CpuMemoryExporter.parseSnapshot(node, ip, lastResult.transcript);
            List<LiveNodeHealthParser.PortSnapshot> ports
                    = includesPorts(metrics)
                    ? LiveNodeHealthParser.parsePorts(node, ip, cmdSet, lastResult.transcript)
                    : new ArrayList<LiveNodeHealthParser.PortSnapshot>();
            printResult(successJson(node, ip, cmdSet, metrics, snapshot, ports, lastResult.elapsedMs));
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = e.toString();
            }
            printResult(errorJson(node, ip, cmdSet, "PROBE_ERROR", message, elapsed(startedAt)));
            System.exit(6);
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        if (args == null) {
            return result;
        }
        for (int i = 0; i < args.length; i++) {
            String token = args[i] == null ? "" : args[i].trim();
            if (!token.startsWith("--")) {
                continue;
            }
            String key = token.substring(2).toLowerCase(Locale.ROOT);
            String value = "";
            int equals = key.indexOf('=');
            if (equals >= 0) {
                value = key.substring(equals + 1);
                key = key.substring(0, equals);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            result.put(key, value == null ? "" : value.trim());
        }
        return result;
    }

    static List<String> commandsFor(String cmdSet) {
        return commandsFor(cmdSet, "cpu");
    }

    static List<String> commandsFor(String cmdSet, String metrics) {
        return commandsFor(cmdSet, metrics, "", "", new ArrayList<String>());
    }

    static List<String> commandsFor(String cmdSet, String metrics, String node,
            String nodeType, List<String> selectedPorts) {
        String value = cmdSet == null ? "" : cmdSet.trim().toUpperCase(Locale.ROOT);
        String mode = normalizeMetrics(metrics);
        LinkedHashMap<String, Boolean> commands = new LinkedHashMap<>();
        if (value.startsWith("N")) {
            commands.put("environment no more", Boolean.TRUE);
            if (includesCpu(mode)) {
                commands.put("show system cpu", Boolean.TRUE);
                commands.put("show system memory-pools", Boolean.TRUE);
            }
            if (includesPorts(mode)) {
                commands.put("show port", Boolean.TRUE);
            }
            if (includesPortStatus(mode)) {
                commands.put("show port description", Boolean.TRUE);
                int detailCount = 0;
                for (String port : selectedPorts == null
                        ? new ArrayList<String>() : selectedPorts) {
                    if (detailCount >= 5 || !isSafePort(port)) {
                        continue;
                    }
                    commands.put("show port " + port, Boolean.TRUE);
                    detailCount++;
                }
            }
        }
        else if (value.startsWith("Z")) {
            commands.put("terminal length 0", Boolean.TRUE);
            if (includesCpu(mode)) commands.put("show processor", Boolean.TRUE);
            if (includesOptical(mode)) commands.put("show opt brief", Boolean.TRUE);
            if (includesPortStatus(mode)) {
                commands.put(isLargeZteNode(node, nodeType)
                        ? "show interface brief" : "show interface", Boolean.TRUE);
                int detailCount = 0;
                for (String port : selectedPorts == null
                        ? new ArrayList<String>() : selectedPorts) {
                    if (detailCount >= 5 || !isSafePort(port)) {
                        continue;
                    }
                    commands.put("show interface " + port, Boolean.TRUE);
                    detailCount++;
                }
            }
        }
        else if (value.startsWith("H")) {
            commands.put("screen-length 0 temporary", Boolean.TRUE);
            if (includesCpu(mode)) {
                commands.put("display cpu-usage", Boolean.TRUE);
                commands.put("display memory-usage", Boolean.TRUE);
            }
            // Full "display interface" output can exceed several MB on AGN nodes.
            // Read the compact physical-port inventory first; optical/all mode then
            // expands only active physical ports inside Telnet_Multi.
            if (includesPorts(mode)) commands.put("display interface description", Boolean.TRUE);
            if (includesPortStatus(mode)) {
                int detailCount = 0;
                for (String port : selectedPorts == null
                        ? new ArrayList<String>() : selectedPorts) {
                    if (detailCount >= 5 || !isSafePort(port)) {
                        continue;
                    }
                    commands.put("display interface " + port, Boolean.TRUE);
                    detailCount++;
                }
            }
        }
        return new ArrayList<>(commands.keySet());
    }

    private static boolean isLargeZteNode(String node, String nodeType) {
        String type = nodeType == null ? "" : nodeType.trim().toUpperCase(Locale.ROOT);
        String name = node == null ? "" : node.trim().toUpperCase(Locale.ROOT);
        return "RN".equals(type) || "AGN".equals(type)
                || name.startsWith("RN-") || name.startsWith("AGN-");
    }

    private static List<String> parsePortList(String value) {
        List<String> ports = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) {
            return ports;
        }
        for (String token : value.split(",")) {
            String port = token == null ? "" : token.trim();
            if (isSafePort(port) && !ports.contains(port) && ports.size() < 5) {
                ports.add(port);
            }
        }
        return ports;
    }

    private static boolean isSafePort(String port) {
        return port != null && port.matches("[A-Za-z0-9_.:/-]{1,80}");
    }

    private static String successJson(String node, String ip, String cmdSet, String metrics,
            CpuMemoryExporter.CpuMemorySnapshot snapshot,
            List<LiveNodeHealthParser.PortSnapshot> ports, long elapsedMs) {
        boolean cpuData = snapshot != null && snapshot.hasAnyData();
        boolean portData = ports != null && !ports.isEmpty();
        boolean hasRequestedData = (includesCpu(metrics) && cpuData)
                || (includesPorts(metrics) && portData);
        StringBuilder json = baseJson(true, node, ip, cmdSet, "OK",
                hasRequestedData ? "Live data updated." : "Connected, but the selected live values were not parsed.",
                elapsedMs);
        appendString(json, "metrics", metrics);
        appendNumber(json, "cpuCurrentPercent", snapshot.cpuCurrentPercent);
        appendNumber(json, "cpuIdlePercent", snapshot.cpuIdlePercent);
        appendNumber(json, "memoryTotalMb", snapshot.memoryTotalMb);
        appendNumber(json, "memoryUsedMb", snapshot.memoryUsedMb);
        appendNumber(json, "memoryFreeMb", snapshot.memoryFreeMb);
        appendNumber(json, "memoryUsedPercent", snapshot.memoryUsedPercent);
        appendNumber(json, "memoryFreePercent", snapshot.memoryFreePercent);
        appendPorts(json, ports);
        json.append('}');
        return json.toString();
    }

    private static void appendPorts(StringBuilder json, List<LiveNodeHealthParser.PortSnapshot> ports) {
        List<LiveNodeHealthParser.PortSnapshot> safePorts = ports == null
                ? new ArrayList<LiveNodeHealthParser.PortSnapshot>() : ports;
        int up = 0;
        int down = 0;
        int optical = 0;
        int alarm = 0;
        for (LiveNodeHealthParser.PortSnapshot port : safePorts) {
            if ("UP".equals(port.portStatus)) up++;
            if ("DOWN".equals(port.portStatus)) down++;
            if (port.hasOpticalData) optical++;
            if ("ALARM".equals(port.opticalStatus)) alarm++;
        }
        json.append(",\"portCount\":").append(safePorts.size());
        json.append(",\"portUpCount\":").append(up);
        json.append(",\"portDownCount\":").append(down);
        json.append(",\"opticalPortCount\":").append(optical);
        json.append(",\"opticalAlarmCount\":").append(alarm);
        json.append(",\"ports\":[");
        for (int i = 0; i < safePorts.size(); i++) {
            if (i > 0) json.append(',');
            LiveNodeHealthParser.PortSnapshot port = safePorts.get(i);
            json.append('{');
            json.append("\"port\":\"").append(escape(port.port)).append('"');
            appendString(json, "portStatus", port.portStatus);
            appendString(json, "description", port.description);
            appendString(json, "speed", port.speed);
            appendString(json, "wavelength", port.wavelength);
            appendString(json, "distance", port.distance);
            appendNumber(json, "txPowerDbm", port.txPowerDbm);
            appendNumber(json, "rxPowerDbm", port.rxPowerDbm);
            appendString(json, "rxWarningRange", port.rxWarningRange);
            json.append(",\"hasOpticalData\":").append(port.hasOpticalData);
            appendString(json, "opticalStatus", port.opticalStatus);
            appendLong(json, "crcInput", port.crcInput);
            appendLong(json, "crcOutput", port.crcOutput);
            appendLong(json, "crcTotal", port.crcTotal);
            json.append('}');
        }
        json.append(']');
    }

    private static String normalizeMetrics(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("port".equals(mode) || "optical".equals(mode) || "all".equals(mode)) return mode;
        return "cpu";
    }

    private static boolean includesCpu(String metrics) {
        return "cpu".equals(metrics) || "all".equals(metrics);
    }

    private static boolean includesPortStatus(String metrics) {
        return "port".equals(metrics) || "all".equals(metrics);
    }

    private static boolean includesOptical(String metrics) {
        return "optical".equals(metrics) || "all".equals(metrics);
    }

    private static boolean includesPorts(String metrics) {
        return includesPortStatus(metrics) || includesOptical(metrics);
    }

    private static boolean isNokia(String cmdSet) {
        return cmdSet != null && cmdSet.trim().toUpperCase(Locale.ROOT).startsWith("N");
    }

    private static boolean isHuawei(String cmdSet) {
        return cmdSet != null && cmdSet.trim().toUpperCase(Locale.ROOT).startsWith("H");
    }

    private static String errorJson(String node, String ip, String cmdSet,
            String status, String message, long elapsedMs) {
        StringBuilder json = baseJson(false, node, ip, cmdSet, status, message, elapsedMs);
        json.append('}');
        return json.toString();
    }

    private static StringBuilder baseJson(boolean success, String node, String ip,
            String cmdSet, String status, String message, long elapsedMs) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        json.append("\"success\":").append(success);
        appendString(json, "node", node);
        appendString(json, "ip", ip);
        appendString(json, "cmdSet", cmdSet);
        appendString(json, "status", status);
        appendString(json, "message", message);
        json.append(",\"elapsedMs\":").append(Math.max(0L, elapsedMs));
        json.append(",\"updatedAt\":").append(System.currentTimeMillis());
        return json;
    }

    private static void appendString(StringBuilder json, String key, String value) {
        json.append(",\"").append(escape(key)).append("\":\"")
                .append(escape(value)).append('\"');
    }

    private static void appendNumber(StringBuilder json, String key, Double value) {
        json.append(",\"").append(escape(key)).append("\":");
        if (value == null || value.isNaN() || value.isInfinite()) {
            json.append("null");
        } else {
            json.append(String.format(Locale.US, "%.2f", value));
        }
    }

    private static void appendLong(StringBuilder json, String key, Long value) {
        json.append(",\"").append(escape(key)).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append(value.longValue());
        }
    }

    static String escape(String value) {
        String text = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
            }
        }
        return escaped.toString();
    }

    private static String value(Map<String, String> options, String key) {
        String value = options == null ? null : options.get(key);
        return value == null ? "" : value.trim();
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private static void printResult(String json) {
        System.out.println(RESULT_MARKER + json);
    }
}
