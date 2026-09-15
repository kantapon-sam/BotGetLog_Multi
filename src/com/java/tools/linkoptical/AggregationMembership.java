package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit local bundle membership only; traffic and descriptions never imply membership. */
final class AggregationMembership {
    static final String HEADER = "Group Interface";
    private static final Pattern PROMPT = Pattern.compile("^\\S+[>#].*$");
    private static final Pattern NEXT_COMMAND = Pattern.compile("^(?:show|quit|logout|exit|terminal)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZTE_LACP_COMMAND = Pattern.compile("^(?:\\S+[>#]\\s*)?show\\s+lacp\\s+internal\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZTE_GROUP_NAME = Pattern.compile("smartgroup[0-9]+");
    private static final Pattern DESCRIPTION = Pattern.compile("^Description\\s*:.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern HW_GROUP_NAME = Pattern.compile("Eth-Trunk[0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HW_MEMBER_HEADER = Pattern.compile("^PortName\\s+Status\\s+Weight.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOKIA_PORT = Pattern.compile("[0-9]+(?:/[a-zA-Z0-9]+){1,4}(?::[0-9]+)?");
    private static final Pattern NOKIA_STATE = Pattern.compile("^Oper State\\s*:.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZTE_INTERFACE = Pattern.compile("^(\\S+)\\s+is\\s+(?:up|down)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZTE_GROUP = Pattern.compile("^Smartgroup\\s*:\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ZTE_MEMBER = Pattern.compile("^(\\S+)\\[([^]]+)\\]\\s+(ACTIVE|INACTIVE)\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HW_INTERFACE = Pattern.compile("^(\\S+)\\s+current state\\s*:.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern HW_MEMBER = Pattern.compile("^(\\S+)\\s+(UP|DOWN|ADMINDOWN|\\*DOWN)\\s+\\d+\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOKIA_INTERFACE = Pattern.compile("^Interface\\s*:\\s*(\\S+).*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOKIA_LAG = Pattern.compile("\\bin\\s+LAG\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOKIA_SUMMARY = Pattern.compile("^([0-9]+(?:/[a-zA-Z0-9]+){1,4}(?::[0-9]+)?)\\s+(?:Up|Down)\\s+(?:Yes|No)\\s+\\S+\\s+[0-9]+\\s+[0-9]+\\s+([0-9]+)\\s+.*$");
    private final String vendor;
    private final Map<String, Map<String, String>> members = new LinkedHashMap<String, Map<String, String>>();
    private final Map<String, String> descriptions = new LinkedHashMap<String, String>();
    private final Set<String> conflictingDescriptions = new LinkedHashSet<String>();
    private String descriptionGroup = "", memberGroup = "", nokiaPort = "";
    private boolean zteLacp, huaweiMembers, nokiaSummary;

    AggregationMembership(String filename) {
        vendor = filename.contains("_ZTE-LLDP-Link_OPTIC_") ? "ZTE"
                : filename.contains("_HW-LLDP-Link_OPTIC_") ? "HW"
                : filename.contains("_N-LLDP-Link_OPTIC_") ? "N" : "";
    }

    /** Observe the same read pass used by the existing physical-port parser. */
    BufferedReader reader(Reader input) {
        return new BufferedReader(input) {
            @Override public String readLine() throws IOException {
                String line = super.readLine();
                if (line != null) accept(line);
                return line;
            }
        };
    }

    void accept(String line) {
        String text = line.trim();
        if ("ZTE".equals(vendor)) zte(text);
        else if ("HW".equals(vendor)) huawei(text);
        else if ("N".equals(vendor)) nokia(text);
    }

    private void zte(String text) {
        if (ZTE_LACP_COMMAND.matcher(text).matches()) {
            zteLacp = true; memberGroup = ""; descriptionGroup = ""; return;
        }
        if (PROMPT.matcher(text).matches() || NEXT_COMMAND.matcher(text).matches()) {
            zteLacp = false; memberGroup = ""; descriptionGroup = "";
        }
        Matcher iface = ZTE_INTERFACE.matcher(text);
        if (iface.matches()) {
            String name = iface.group(1).toLowerCase(Locale.ROOT);
            descriptionGroup = ZTE_GROUP_NAME.matcher(name).matches() ? name : "";
        }
        description(text);
        if (!zteLacp) return;
        Matcher group = ZTE_GROUP.matcher(text);
        if (group.matches()) { memberGroup = "smartgroup" + group.group(1); return; }
        Matcher member = ZTE_MEMBER.matcher(text);
        if (!memberGroup.isEmpty() && member.matches()) {
            add(member.group(1), memberGroup, member.group(3).toUpperCase(Locale.ROOT) + " / " + member.group(4));
        }
    }

    private void huawei(String text) {
        Matcher iface = HW_INTERFACE.matcher(text);
        if (iface.matches()) {
            // Subinterfaces may repeat the parent's members with another service description.
            String name = iface.group(1);
            descriptionGroup = HW_GROUP_NAME.matcher(name).matches() ? "Eth-Trunk" + name.substring(9) : "";
            memberGroup = descriptionGroup; huaweiMembers = false;
        }
        if (PROMPT.matcher(text).matches()) { descriptionGroup = ""; memberGroup = ""; huaweiMembers = false; }
        description(text);
        if (!memberGroup.isEmpty() && HW_MEMBER_HEADER.matcher(text).matches()) huaweiMembers = true;
        if (text.startsWith("The Number of")) huaweiMembers = false;
        Matcher member = HW_MEMBER.matcher(text);
        if (huaweiMembers && member.matches() && member.group(1).contains("/") && !member.group(1).contains(".")) {
            add(member.group(1), memberGroup, member.group(2).toUpperCase(Locale.ROOT));
        }
    }

    private void nokia(String text) {
        if (PROMPT.matcher(text).matches()) { nokiaPort = ""; nokiaSummary = false; }
        if (text.startsWith("Port") && text.contains("LAG/")) nokiaSummary = true;
        Matcher summary = NOKIA_SUMMARY.matcher(text);
        if (nokiaSummary && summary.matches()) add(summary.group(1), "lag-" + summary.group(2), "");
        Matcher iface = NOKIA_INTERFACE.matcher(text);
        if (iface.matches()) {
            String name = iface.group(1);
            nokiaPort = NOKIA_PORT.matcher(name).matches() ? name : "";
            nokiaSummary = false;
        }
        if (nokiaPort.isEmpty() || !NOKIA_STATE.matcher(text).matches()) return;
        String state = text.substring(text.indexOf(':') + 1).trim().split("\\s{2,}", 2)[0];
        Matcher lag = NOKIA_LAG.matcher(state);
        if (lag.find()) {
            String memberState = state.substring(0, lag.start()).trim();
            int dash = memberState.indexOf(" - ");
            if (dash >= 0) memberState = memberState.substring(dash + 3).trim();
            add(nokiaPort, "lag-" + lag.group(1), memberState);
        }
    }

    private void description(String text) {
        if (descriptionGroup.isEmpty() || !DESCRIPTION.matcher(text).matches()) return;
        String value = text.substring(text.indexOf(':') + 1).trim();
        String old = descriptions.get(descriptionGroup);
        if (old != null && !old.equals(value)) conflictingDescriptions.add(descriptionGroup);
        descriptions.put(descriptionGroup, value);
    }

    private void add(String port, String group, String state) {
        String key = normalize(port);
        Map<String, String> groups = members.get(key);
        if (groups == null) { groups = new LinkedHashMap<String, String>(); members.put(key, groups); }
        // A detailed member state takes precedence over the summary's blank state.
        if (!groups.containsKey(group) || !state.isEmpty()) groups.put(group, state);
    }

    String[] fields(String port) {
        Map<String, String> groups = members.get(normalize(port));
        if (groups == null || groups.isEmpty()) return new String[]{"", "", ""};
        if (groups.size() != 1) return new String[]{"", "", "CONFLICT"};
        Map.Entry<String, String> member = groups.entrySet().iterator().next();
        String group = member.getKey();
        String description = conflictingDescriptions.contains(group) ? "" : descriptions.get(group);
        return new String[]{group, description == null ? "" : description, member.getValue()};
    }

    String append(String row, String port) {
        return row + "," + csv(fields(port)[0]);
    }

    /** Keep the original NeighborDes column at index 20 in the filtered export. */
    static String insertNeighborDescription(String row, String value) {
        boolean quoted = false; int columns = 0;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (c == ',' && !quoted && ++columns == 20) {
                return row.substring(0, i + 1) + csv(value) + "," + row.substring(i + 1);
            }
        }
        throw new IllegalArgumentException("LLDP row is missing its Group Interface column");
    }

    private static String normalize(String port) {
        return port == null ? "" : port.trim().replaceFirst("^'", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
