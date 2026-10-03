package com.java.tools.linkoptical;

import java.util.*;
import java.util.regex.*;

/** Junos physical-port rows in the existing 20-column Link Optical schema. */
final class JuniperExportParser {
    private static final Pattern PROMPT = Pattern.compile("^(?:[^\\s@>#]+@)*([A-Za-z0-9_.:-]+)>\\s*(.*)$");
    private static final Pattern PHYSICAL = Pattern.compile("(?:ge|xe|et)-\\d+/\\d+/\\d+(?::\\d+)?");

    private JuniperExportParser() {}

    static String parse(List<String> lines, String filename) {
        String transcript = String.join("\n", lines);
        Matcher identity = Pattern.compile("\\[\\d+](.*?)_(.*?)_J-LLDP-Link_OPTIC_\\d{4}-\\d{2}-\\d{2}(?:\\.txt)?$")
                .matcher(new java.io.File(filename).getName());
        String ip = "", site = "";
        if (identity.find()) { ip = identity.group(1); site = identity.group(2); }
        Map<String, List<Neighbor>> neighbors = new LinkedHashMap<>();
        boolean inLldp = false;
        String local = "";
        Neighbor neighbor = null;
        String version = "", model = "";
        boolean inVersion = false;
        for (String raw : lines) {
            String line = clean(raw);
            Matcher prompt = PROMPT.matcher(line);
            if (prompt.matches()) {
                if (site.isEmpty() || prompt.group(2).startsWith("set cli screen-length")) site = prompt.group(1);
                String command = prompt.group(2).trim();
                inLldp = command.equalsIgnoreCase("show lldp neighbors detail");
                inVersion = command.equalsIgnoreCase("show version");
                local = ""; neighbor = null;
                continue;
            }
            if (inVersion) {
                if (line.startsWith("Model:")) model = afterColon(line);
                if (line.startsWith("Junos:")) version = afterColon(line);
                if (version.isEmpty()) {
                    Matcher release = Pattern.compile("^JUNOS Software Release \\[(.*)]$").matcher(line);
                    if (release.matches()) version = release.group(1);
                }
            }
            if (!inLldp) continue;
            if (line.startsWith("Local Interface")) {
                local = afterColon(line); neighbor = null;
            } else if (line.equals("Neighbour Information:") || line.equals("Neighbor Information:")) {
                if (PHYSICAL.matcher(local).matches()) {
                    neighbor = new Neighbor();
                    neighbors.computeIfAbsent(local, k -> new ArrayList<>()).add(neighbor);
                }
            } else if (neighbor != null) {
                if (line.matches("(?i)^System name\\s*:.*")) neighbor.name = afterColon(line);
                else if (line.matches("(?i)^Port ID\\s*:.*")) neighbor.port = afterColon(line);
                else if (line.matches("(?i)^Port description\\s*:.*")) neighbor.description = afterColon(line);
                else if (line.matches("(?i)^Port type\\s*:.*")) neighbor.type = afterColon(line);
            }
        }
        StringBuilder csv = new StringBuilder();
        for (LiveNodeHealthParser.PortSnapshot p : JuniperLivePortParser.parse(transcript)) {
            List<Neighbor> peers = neighbors.get(p.port);
            if (peers == null || peers.isEmpty()) peers = Collections.singletonList(new Neighbor());
            Set<String> emitted = new HashSet<>();
            for (Neighbor n : peers) {
                String peerPort = n.port;
                // Numeric ifIndex is not an interface name. Use a port-description
                // fallback only when the entire value is recognizably a port.
                if ((peerPort.isEmpty() || peerPort.matches("\\d+"))
                        && n.description.matches("(?i)(?:(?:ge|xe|et)-\\d+/\\d+/\\d+(?::\\d+)?|(?:\\*?[AB]:)?\\d+(?:/\\w+){2,4}|(?:Ethernet|GigabitEthernet|XGigabitEthernet|TenGigE|HundredGigE|x?c?gei-)[0-9/.:_-]+)")) {
                    peerPort = n.description;
                }
                if (!emitted.add(n.name + "\u0000" + peerPort)) continue;
                String[] row = {site, ip, p.port, p.portStatus, p.description, n.name, peerPort,
                    p.speed, "", "", "", "", p.wavelength, p.distance, number(p.txPowerDbm),
                    number(p.rxPowerDbm), lowWarning(p.rxWarningRange), p.crcTotal == null ? "" : p.crcTotal.toString(), version, model};
                for (int i = 0; i < row.length; i++) { if (i > 0) csv.append(','); csv.append(quote(row[i])); }
                csv.append('\n');
            }
        }
        return csv.toString();
    }

    private static String lowWarning(String range) { return range.isEmpty() ? "" : range.split(" to ", -1)[0]; }
    private static String number(Double value) { return value == null ? "" : String.format(Locale.ROOT, "%.2f", value); }
    private static String afterColon(String line) { return line.substring(line.indexOf(':') + 1).trim(); }
    private static String clean(String line) { return line.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "").trim(); }
    private static String quote(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
    private static final class Neighbor { String name = "", port = "", description = "", type = ""; }
}
