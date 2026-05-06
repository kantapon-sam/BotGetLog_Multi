
package com.java.tools.arp;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckARP {

    private static String safe(String[] arr, int idx) {
        return (idx < arr.length) ? arr[idx].trim() : "";
    }

    private static String normalizePort(String p) {
        if (p == null) {
            return "";
        }

        p = p.trim().toLowerCase();
        p = p.replaceAll("\\(.*?\\)", "").replaceAll("\\s+", "").trim();

        Matcher m = Pattern.compile("(\\d+(?:/\\d+)+(?:\\.\\d+)?)$").matcher(p);
        if (m.find()) {
            p = m.group(1);
        }

        p = p.replaceAll("\\.(0+)(\\d+)$", ".$2");
        return p;
    }

    private static String buildQuotedDesc(String[] desParts) {
        StringBuilder descBuilder = new StringBuilder();
        for (int k = 3; k < desParts.length; k++) {
            if (k > 3) {
                descBuilder.append(",");
            }
            descBuilder.append(desParts[k]);
        }
        return "\"" + descBuilder.toString().trim() + "\"";
    }

    private static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text.trim() + "\"";
    }

    private static String extractHuaweiPromptNode(String line) {
        if (line == null) {
            return "";
        }

        Matcher matcher = Pattern.compile("^<([^>]+)>").matcher(line.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return "";
    }

    private static boolean isHuaweiCommandLine(String line, String command) {
        if (line == null) {
            return false;
        }

        String trimmed = line.trim();
        if (trimmed.equals(command)) {
            return true;
        }

        return trimmed.matches("^<[^>]+>" + Pattern.quote(command) + "$");
    }

    private static String[] parseHuaweiArpLine(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (!trimmed.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+\\s+.+$")) {
            return null;
        }

        String[] parts = trimmed.split("\\s{2,}");
        if (parts.length < 4) {
            return null;
        }

        String ip = safe(parts, 0);
        String mac = safe(parts, 1);
        String expire = "";
        String type = "";
        String iface = "";
        String vpn = "";

        if (safe(parts, 2).matches("^\\d+$")) {
            expire = safe(parts, 2);
            type = safe(parts, 3);
            iface = safe(parts, 4);
            vpn = safe(parts, 5);
        } else {
            type = safe(parts, 2);
            iface = safe(parts, 3);
            vpn = safe(parts, 4);
        }

        if (!ip.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$") || mac.isEmpty() || iface.isEmpty()) {
            return null;
        }

        return new String[]{ip, mac, expire, type, iface, vpn};
    }

private static String buildZtePort(String iface, String subIface, String extVlan) {
    String i = iface == null ? "" : iface.trim();
    String s = subIface == null ? "" : subIface.trim();
    String v = extVlan == null ? "" : extVlan.trim();

    // à¸–à¹‰à¸²à¸¡à¸µ sub-interface à¸ˆà¸£à¸´à¸‡ à¹ƒà¸«à¹‰à¹ƒà¸Šà¹‰ sub-interface à¸à¹ˆà¸­à¸™
    if (!s.isEmpty() && !s.equalsIgnoreCase("N/A")) {
        return s;
    }

    // à¸–à¹‰à¸² interface à¸¥à¸‡à¸—à¹‰à¸²à¸¢à¸”à¹‰à¸§à¸¢ "." à¹à¸¥à¸°à¸¡à¸µ ext vlan à¹ƒà¸«à¹‰à¸•à¹ˆà¸­à¸—à¹‰à¸²à¸¢à¹€à¸‚à¹‰à¸²à¹„à¸›
    // à¹€à¸Šà¹ˆà¸™ gei-1/1/0/5. + 3351 => gei-1/1/0/5.3351
    if (!i.isEmpty() && i.endsWith(".") && !v.isEmpty() && !v.equalsIgnoreCase("N/A")) {
        return i + v;
    }

    return i;
}

    private static String buildZteVlanInfo(String extVlan, String interVlan) {
        String e = extVlan == null ? "" : extVlan.trim();
        String i = interVlan == null ? "" : interVlan.trim();
        if (e.isEmpty() && i.isEmpty()) {
            return "";
        }
        return e + "/" + i;
    }

    private static String cleanNokiaValue(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("*", "").trim();
    }

    private static String extractSapPort(String sap) {
        String cleaned = cleanNokiaValue(sap);
        if (cleaned.contains(":")) {
            cleaned = cleaned.split(":", 2)[0].trim();
        }
        return cleaned;
    }

    private static String appendPipeValue(String existing, String value) {
        String v = cleanNokiaValue(value);
        if (v.isEmpty()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.trim().isEmpty()) {
            return v;
        }

        String[] arr = existing.split("\\|");
        for (String x : arr) {
            if (x.trim().equalsIgnoreCase(v)) {
                return existing;
            }
        }
        return existing + "|" + v;
    }

    private static String formatNokiaPortValue(String port) {
        String cleaned = cleanNokiaValue(port);
        if (cleaned.isEmpty()) {
            return "";
        }

        Matcher lagMatcher = Pattern.compile("^lag-(\\d+)(:.*)?$", Pattern.CASE_INSENSITIVE).matcher(cleaned);
        if (lagMatcher.find()) {
            String suffix = lagMatcher.group(2) == null ? "" : lagMatcher.group(2).trim();
            return "lag" + lagMatcher.group(1) + suffix;
        }

        if (cleaned.equalsIgnoreCase("system") || cleaned.equalsIgnoreCase("loopback")) {
            return cleaned;
        }

        if (cleaned.matches("^[A-Za-z]?/?\\d+(?:/\\d+)+(?::[^\\s]+)?$")) {
            return "'" + cleaned;
        }

        return cleaned;
    }

    private static String resolveNokiaPortDisplay(String binding, String lagMembers) {
        String cleanedBinding = cleanNokiaValue(binding);
        if (!cleanedBinding.startsWith("lag-")) {
            return formatNokiaPortValue(cleanedBinding);
        }

        return formatNokiaPortValue(cleanedBinding);
    }

    private static String resolveNokiaDesc(String binding, String ifaceName,
            Map<String, String> lagToDesc,
            Map<String, String> lagToPorts,
            Map<String, String> portToDesc) {

        String cleanedBinding = cleanNokiaValue(binding);
        String desc = lagToDesc.get(cleanedBinding);

        if ((desc == null || desc.trim().isEmpty()) && cleanedBinding.startsWith("lag-")) {
            String members = lagToPorts.get(cleanedBinding);
            if (members != null && !members.trim().isEmpty()) {
                String firstMember = members.split("\\|")[0].trim();
                desc = portToDesc.get(cleanNokiaValue(firstMember));
            }
        }

        if ((desc == null || desc.trim().isEmpty()) && portToDesc.containsKey(cleanedBinding)) {
            desc = portToDesc.get(cleanedBinding);
        }

        if (desc == null || desc.trim().isEmpty()) {
            desc = ifaceName;
        }

        return desc == null ? "" : desc.trim();
    }

    private static String resolveNokiaPhy(String binding, String ifaceName,
            Map<String, String> routerIfToPhy,
            Map<String, String> lagToPhy,
            Map<String, String> lagToPorts,
            Map<String, String> portToPhy) {

        String phy = routerIfToPhy.getOrDefault(cleanNokiaValue(ifaceName), "");
        if (!phy.isEmpty()) {
            return phy;
        }

        String cleanedBinding = cleanNokiaValue(binding);
        phy = lagToPhy.getOrDefault(cleanedBinding, "");
        if (!phy.isEmpty()) {
            return phy;
        }

        if (cleanedBinding.startsWith("lag-")) {
            String members = lagToPorts.get(cleanedBinding);
            if (members != null && !members.trim().isEmpty()) {
                String firstMember = members.split("\\|")[0].trim();
                return portToPhy.getOrDefault(cleanNokiaValue(firstMember), "");
            }
        }

        return portToPhy.getOrDefault(cleanedBinding, "");
    }

    private static String resolveNokiaProto(String binding, String ifaceName,
            Map<String, String> routerIfToProto,
            Map<String, String> lagToProto,
            Map<String, String> lagToPorts,
            Map<String, String> portToProto) {

        String proto = routerIfToProto.getOrDefault(cleanNokiaValue(ifaceName), "");
        if (!proto.isEmpty()) {
            return proto;
        }

        String cleanedBinding = cleanNokiaValue(binding);
        proto = lagToProto.getOrDefault(cleanedBinding, "");
        if (!proto.isEmpty()) {
            return proto;
        }

        if (cleanedBinding.startsWith("lag-")) {
            String members = lagToPorts.get(cleanedBinding);
            if (members != null && !members.trim().isEmpty()) {
                String firstMember = members.split("\\|")[0].trim();
                return portToProto.getOrDefault(cleanNokiaValue(firstMember), "");
            }
        }

        return portToProto.getOrDefault(cleanedBinding, "");
    }

    public static String Sub(BufferedReader br, String path) throws IOException {
        String line;

        String Str_ARP_ALL = "";
        String node = "";
        String model = "";
        String loopback = "";
        String arp_all = "";
        String des_all = "";

        if (path.contains("_HW-ARP_")) {

            try {
                loopback = path.split("_")[0].split("]")[1];
            } catch (Exception e) {
                loopback = "";
            }

            while ((line = br.readLine()) != null) {

                String promptNode = extractHuaweiPromptNode(line);
                if (!promptNode.isEmpty()) {
                    node = promptNode;
                }

                if (line.contains("HUAWEI") && line.contains("uptime")) {
                    try {
                        model = line.split("HUAWEI ")[1].split(" uptime")[0];
                    } catch (Exception e) {
                        model = "";
                    }
                }

                if (isHuaweiCommandLine(line, "display arp all")) {

                    while ((line = br.readLine()) != null) {
                        if (line.contains("Total:")) {
                            break;
                        }

                        if (line.startsWith("IP ADDRESS")
                                || line.contains("VLAN/CEVLAN")
                                || line.startsWith("---")
                                || line.trim().isEmpty()
                                || line.trim().matches("^[0-9]+/-$")) {
                            continue;
                        }

                        String[] arpParts = parseHuaweiArpLine(line);
                        if (arpParts != null) {
                            arp_all += "\n" + arpParts[0] + "," + arpParts[1] + "," + arpParts[2] + "," + arpParts[3] + "," + arpParts[4] + "," + arpParts[5];
                        }
                    }

                    if (line == null) {
                        break;
                    }
                }

                if (isHuaweiCommandLine(line, "display interface description")) {

                    Pattern gePattern = Pattern.compile(
                            "^([A-Za-z0-9/\\-\\.\\(\\)]+)\\s+(up|down|\\*down)\\s+(up|down|\\*down)\\s*(.*)$"
                    );
                    Pattern simplePattern = Pattern.compile(
                            "^([A-Za-z0-9/\\-\\.\\(\\):]+)\\s+(.*)$"
                    );

                    String lastIface = null;
                    String lastPhy = "";
                    String lastProto = "";
                    String lastDesc = "";

                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("<")) {
                            if (lastIface != null) {
                                des_all += "\n" + lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc;
                            }
                            break;
                        }

                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("Interface")) {
                            continue;
                        }

                        Matcher m = gePattern.matcher(trimmed);
                        Matcher m2 = simplePattern.matcher(trimmed);

                        if (m.find()) {
                            if (lastIface != null) {
                                des_all += "\n" + lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc;
                            }

                            lastIface = m.group(1).trim();
                            lastPhy = m.group(2).replace("*", "").trim();
                            lastProto = m.group(3).replace("*", "").trim();
                            lastDesc = m.group(4).trim();
                        } else if (m2.find()) {
                            if (lastIface != null) {
                                des_all += "\n" + lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc;
                            }

                            lastIface = m2.group(1).trim();
                            lastPhy = "";
                            lastProto = "";
                            lastDesc = m2.group(2).trim();
                        } else {
                            if (lastIface != null) {
                                if (trimmed.matches("^\\d+\\s+Interface.*") && lastDesc.matches(".*\\d$")) {
                                    String num = trimmed.split("\\s+")[0];
                                    String rest = trimmed.substring(num.length()).trim();
                                    lastDesc = lastDesc + num + " " + rest;
                                } else {
                                    lastDesc = lastDesc + " " + trimmed;
                                }
                            }
                        }
                    }

                    if (lastIface != null && !des_all.endsWith(lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc)) {
                        des_all += "\n" + lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc;
                    }
                }
            }

            String[] arpLines = arp_all.split("\n");
            String[] desLines = des_all.split("\n");

            for (int i = 1; i < arpLines.length; i++) {
                if (arpLines[i].trim().isEmpty()) {
                    continue;
                }

                String[] arpParts = arpLines[i].split(",", -1);
                String ip = safe(arpParts, 0);
                String mac = safe(arpParts, 1);
                String EXPIRE = safe(arpParts, 2);
                String TYPE = safe(arpParts, 3);
                String port = safe(arpParts, 4);
                String vpn = safe(arpParts, 5);

                String arpPortKey = normalizePort(port);

                boolean matched = false;
                for (int j = 1; j < desLines.length; j++) {
                    if (desLines[j].trim().isEmpty()) {
                        continue;
                    }

                    String[] desParts = desLines[j].split(",", -1);
                    if (desParts.length < 4) {
                        continue;
                    }

                    String desPortName = safe(desParts, 0);
                    String desPortKey = normalizePort(desPortName);

                    if (arpPortKey.equals(desPortKey)) {
                        String PHY = safe(desParts, 1);
                        String Protocol = safe(desParts, 2);
                        String Des = buildQuotedDesc(desParts);

                        Str_ARP_ALL += "\n" + node + "," + model + "," + loopback + ","
                                + ip + "," + mac + ","
                                + TYPE + "," + port + "," + vpn + "," + PHY + "," + Protocol + "," + Des;
                        matched = true;
                        break;
                    }
                }

                if (!matched) {
                    Str_ARP_ALL += "\n" + node + "," + model + "," + loopback + ","
                            + ip + "," + mac + ","
                            + TYPE + "," + port + "," + vpn + ",,,\"\"";
                }
            }
        } else if (path.contains("_ZTE-ARP_")) {

            try {
                loopback = path.split("_")[0].split("]")[1];
            } catch (Exception e) {
                loopback = "";
            }

            try {
                node = path.split("_")[1];
            } catch (Exception e) {
                node = "";
            }

            model = "ZTE";

            StringBuilder raw = new StringBuilder();
            while ((line = br.readLine()) != null) {
                raw.append(line).append("\n");
            }

            String[] rows = raw.toString().split("\\r?\\n");
            Map<String, String> ztePortToVpn = new HashMap<>();

            for (String row : rows) {
                String trimmed = row.trim();
                Matcher prompt = Pattern.compile("^([A-Za-z0-9._\\-/]+)#.*$").matcher(trimmed);
                if (prompt.find()) {
                    node = prompt.group(1).trim();
                    break;
                }
            }

            for (String row : rows) {
                String trimmed = row.trim();

                Matcher m1 = Pattern.compile("^ZXCTN\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
                if (m1.find()) {
                    model = m1.group(1).trim();
                    break;
                }

                Matcher m2 = Pattern.compile("^Chassis model\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
                if (m2.find()) {
                    model = m2.group(1).trim();
                    break;
                }
            }

            for (String row : rows) {
                Matcher loopMatcher = Pattern.compile("^IP Loopback\\s*:\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)$", Pattern.CASE_INSENSITIVE).matcher(row.trim());
                if (loopMatcher.find()) {
                    loopback = loopMatcher.group(1).trim();
                    break;
                }
            }

            String currentCfgIface = "";
            String currentCfgVpn = "";
            Pattern ifaceStart = Pattern.compile("^interface\\s+([A-Za-z0-9_./\\-]+)$", Pattern.CASE_INSENSITIVE);
            Pattern vrfPattern = Pattern.compile("^ip vrf forwarding\\s+(.+)$", Pattern.CASE_INSENSITIVE);

            for (String row : rows) {
                String trimmed = row.trim();

                Matcher ifaceMatcher = ifaceStart.matcher(trimmed);
                if (ifaceMatcher.find()) {
                    if (!currentCfgIface.isEmpty() && !currentCfgVpn.isEmpty()) {
                        ztePortToVpn.put(normalizePort(currentCfgIface), currentCfgVpn);
                    }
                    currentCfgIface = ifaceMatcher.group(1).trim();
                    currentCfgVpn = "";
                    continue;
                }

                if (!currentCfgIface.isEmpty()) {
                    Matcher vrfMatcher = vrfPattern.matcher(trimmed);
                    if (vrfMatcher.find()) {
                        currentCfgVpn = vrfMatcher.group(1).trim();
                        continue;
                    }

                    if (trimmed.equals("$")) {
                        if (!currentCfgVpn.isEmpty()) {
                            ztePortToVpn.put(normalizePort(currentCfgIface), currentCfgVpn);
                        }
                        currentCfgIface = "";
                        currentCfgVpn = "";
                    }
                }
            }
            if (!currentCfgIface.isEmpty() && !currentCfgVpn.isEmpty()) {
                ztePortToVpn.put(normalizePort(currentCfgIface), currentCfgVpn);
            }

            boolean inArp = false;
            String curIp = "";
            String curAge = "";
            String curMac = "";
            String curIface = "";
            String curExtVlan = "";
            String curInterVlan = "";
            String curSubIface = "";

            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.contains("#show arp")) {
                    inArp = true;
                    continue;
                }

                if (!inArp) {
                    continue;
                }

                if (trimmed.contains("#show interface description")
                        || trimmed.matches("^[A-Za-z0-9._\\-/]+#.*$")
                        || trimmed.contains("#quit")) {
                    if (!curIp.isEmpty()) {
                      String port = buildZtePort(curIface, curSubIface, curExtVlan);
                        String vpn = ztePortToVpn.getOrDefault(normalizePort(port), buildZteVlanInfo(curExtVlan, curInterVlan));
                        arp_all += "\n" + curIp + "," + curMac + "," + curAge + ",," + port + "," + vpn;
                    }

                    inArp = false;
                    curIp = "";
                    curAge = "";
                    curMac = "";
                    curIface = "";
                    curExtVlan = "";
                    curInterVlan = "";
                    curSubIface = "";
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.matches("^\\d{2}:\\d{2}:\\d{2}.*")
                        || trimmed.startsWith("Arp protect")
                        || trimmed.startsWith("The count is")
                        || trimmed.startsWith("IP ")
                        || trimmed.startsWith("Address ")
                        || trimmed.startsWith("-")) {
                    continue;
                }

                if (trimmed.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+\\s+.*")) {
                    if (!curIp.isEmpty()) {
                  String port = buildZtePort(curIface, curSubIface, curExtVlan);
                        String vpn = ztePortToVpn.getOrDefault(normalizePort(port), buildZteVlanInfo(curExtVlan, curInterVlan));
                        arp_all += "\n" + curIp + "," + curMac + "," + curAge + ",," + port + "," + vpn;
                    }

                    String[] parts = trimmed.split("\\s+");
                    curIp = safe(parts, 0);
                    curAge = safe(parts, 1);
                    curMac = safe(parts, 2);
                    curIface = safe(parts, 3);
                    curExtVlan = safe(parts, 4);
                    curInterVlan = safe(parts, 5);

                    StringBuilder subBuilder = new StringBuilder();
                    for (int i = 6; i < parts.length; i++) {
                        if (i > 6) {
                            subBuilder.append(" ");
                        }
                        subBuilder.append(parts[i]);
                    }
                    curSubIface = subBuilder.toString().trim();
                    continue;
                }

if (!curIp.isEmpty()) {
    String[] parts = trimmed.split("\\s+");
    if (parts.length >= 1) {
        String first = safe(parts, 0);
        String second = safe(parts, 1);

        if (!first.isEmpty() && first.startsWith(".")) {
            curIface = curIface + first;

            if (!curSubIface.isEmpty() && !curSubIface.equalsIgnoreCase("N/A")) {
                if (curSubIface.endsWith(".")) {
                    curSubIface = curSubIface + first.substring(1);
                } else {
                    curSubIface = curSubIface + first;
                }
            }

        } else if (!first.isEmpty() && first.matches("^\\d+\\.\\d+$")) {
            curIface = curIface + first;

            if (!curSubIface.isEmpty() && !curSubIface.equalsIgnoreCase("N/A")) {
                int dotPos = first.indexOf(".");
                if (dotPos >= 0) {
                    curSubIface = curSubIface + first.substring(dotPos);
                } else {
                    curSubIface = curSubIface + first;
                }
            }

        } else if (!first.isEmpty() && first.matches("^\\d+$")) {
            // iface à¸«à¸¥à¸±à¸ à¹€à¸Šà¹ˆà¸™ gei-1/1/0/5. + 3351
            if (!curIface.isEmpty() && curIface.endsWith(".")) {
                curIface = curIface + first;
            }

            // subIface à¹€à¸Šà¹ˆà¸™ gei-1/1/0/5.3 + 351
            if (!curSubIface.isEmpty() && !curSubIface.equalsIgnoreCase("N/A")) {
                if (!second.isEmpty() && second.matches("^\\d+$")) {
                    curSubIface = curSubIface + second;
                } else if (curSubIface.endsWith(".")) {
                    curSubIface = curSubIface + first;
                }
            }
        }
    }
}
            }

            boolean inDesc = false;
            String lastIface = null;
            String lastPhy = "";
            String lastProto = "";
            String lastDesc = "";

            Pattern zteDescPattern = Pattern.compile(
                    "^([A-Za-z0-9_./\\-]+)\\s+(up|down|\\*down)\\s+(up|down|\\*down)\\s+(up|down|\\*down)\\s*(.*)$"
            );

            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.contains("#show interface description")) {
                    inDesc = true;
                    continue;
                }

                if (!inDesc) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z0-9._\\-/]+#.*$")
                        || trimmed.contains("#show ")
                        || trimmed.contains("#quit")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    if (lastIface != null) {
                        des_all += "\n" + lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc;
                    }
                    inDesc = false;
                    lastIface = null;
                    lastPhy = "";
                    lastProto = "";
                    lastDesc = "";
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.matches("^\\d{2}:\\d{2}:\\d{2}.*")
                        || trimmed.startsWith("Interface")
                        || trimmed.startsWith("Description")) {
                    continue;
                }

                Matcher m = zteDescPattern.matcher(trimmed);
                if (m.find()) {
                    if (lastIface != null) {
                        des_all += "\n" + lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc;
                    }

                    lastIface = m.group(1).trim();
                    lastPhy = m.group(3).replace("*", "").trim();
                    lastProto = m.group(4).replace("*", "").trim();
                    lastDesc = m.group(5).trim();
                } else if (lastIface != null) {
                    if (lastDesc.isEmpty()) {
                        lastDesc = trimmed;
                    } else {
                        lastDesc = lastDesc + " " + trimmed;
                    }
                }
            }

            if (inDesc && lastIface != null && !des_all.endsWith(lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc)) {
                des_all += "\n" + lastIface + "," + lastPhy + "," + lastProto + "," + lastDesc;
            }

            String[] arpLines = arp_all.split("\n");
            String[] desLines = des_all.split("\n");

            for (int i = 1; i < arpLines.length; i++) {
                if (arpLines[i].trim().isEmpty()) {
                    continue;
                }

                String[] arpParts = arpLines[i].split(",", -1);
                String ip = safe(arpParts, 0);
                String mac = safe(arpParts, 1);
                String EXPIRE = safe(arpParts, 2);
                String TYPE = safe(arpParts, 3);
                if (TYPE.isEmpty()) {
                    TYPE = EXPIRE;
                }
                String port = safe(arpParts, 4);
                String vpn = safe(arpParts, 5);

                String arpPortKey = normalizePort(port);
                boolean matched = false;

                for (int j = 1; j < desLines.length; j++) {
                    if (desLines[j].trim().isEmpty()) {
                        continue;
                    }

                    String[] desParts = desLines[j].split(",", -1);
                    if (desParts.length < 4) {
                        continue;
                    }

                    String desPortName = safe(desParts, 0);
                    String desPortKey = normalizePort(desPortName);

                    if (arpPortKey.equals(desPortKey)) {
                        String PHY = safe(desParts, 1);
                        String Protocol = safe(desParts, 2);
                        String Des = buildQuotedDesc(desParts);

                        Str_ARP_ALL += "\n" + node + "," + model + "," + loopback + ","
                                + ip + "," + mac + ","
                                + TYPE + "," + port + "," + vpn + "," + PHY + "," + Protocol + "," + Des;
                        matched = true;
                        break;
                    }
                }

       if (!matched) {
    Str_ARP_ALL += "\n" + node + "," + model + "," + loopback + ","
            + ip + "," + mac + ","
            + TYPE + "," + port + "," + vpn + ",,,\"\"";
}
            }
        } else if (path.contains("_N-ARP_")) {

            try {
                loopback = path.split("_")[0].split("]")[1];
            } catch (Exception e) {
                loopback = "";
            }

            try {
                node = path.split("_")[1];
            } catch (Exception e) {
                node = "";
            }

            model = "Nokia";

            StringBuilder raw = new StringBuilder();
            while ((line = br.readLine()) != null) {
                raw.append(line).append("\n");
            }

            String[] rows = raw.toString().split("\\r?\\n");

            for (String row : rows) {
                String trimmed = row.trim();

                Matcher promptMatcher = Pattern.compile("^[A-Za-z]:([A-Za-z0-9._\\-/]+)#.*$").matcher(trimmed);
                if (promptMatcher.find()) {
                    node = promptMatcher.group(1).trim();
                    break;
                }
            }

            for (String row : rows) {
                String trimmed = row.trim();

                Matcher nameMatcher = Pattern.compile("^Name\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
                if (nameMatcher.find()) {
                    node = nameMatcher.group(1).trim();
                }

                Matcher typeMatcher = Pattern.compile("^Type\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed);
                if (typeMatcher.find()) {
                    model = typeMatcher.group(1).trim();
                }
            }

            Map<String, String> routerIfToPort = new HashMap<>();
            Map<String, String> routerIfToPhy = new HashMap<>();
            Map<String, String> routerIfToProto = new HashMap<>();
            Map<String, String> portToDesc = new HashMap<>();
            Map<String, String> portToPhy = new HashMap<>();
            Map<String, String> portToProto = new HashMap<>();
            Map<String, String> lagToDesc = new HashMap<>();
            Map<String, String> lagToPhy = new HashMap<>();
            Map<String, String> lagToProto = new HashMap<>();
            Map<String, String> lagToPorts = new HashMap<>();
            Map<String, String> serviceIdToName = new HashMap<>();

            boolean inRouterIf = false;
            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.matches("^[A-Za-z]:.*#\\s*show router interface\\s*$")) {
                    inRouterIf = true;
                    continue;
                }

                if (!inRouterIf) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z]:.*#.*$")
                        || trimmed.startsWith("Interfaces :")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    inRouterIf = false;
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.startsWith("=")
                        || trimmed.startsWith("-")
                        || trimmed.startsWith("Interface Table")
                        || trimmed.startsWith("Interface-Name")
                        || trimmed.startsWith("IP-Address")
                        || row.startsWith("   ")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 5) {
                    String ifName = safe(parts, 0);
                    String adm = safe(parts, 1);
                    String opr = safe(parts, 2);
                    String binding = safe(parts, 4);

                    routerIfToPort.put(cleanNokiaValue(ifName), cleanNokiaValue(binding));
                    routerIfToPhy.put(cleanNokiaValue(ifName), adm);
                    routerIfToProto.put(cleanNokiaValue(ifName), safe(opr.split("/"), 0));
                }
            }

            boolean inPortDesc = false;
            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.matches("^[A-Za-z]:.*#\\s*show port description\\s*$")) {
                    inPortDesc = true;
                    continue;
                }

                if (!inPortDesc) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z]:.*#.*$")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    inPortDesc = false;
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.startsWith("=")
                        || trimmed.startsWith("-")
                        || trimmed.startsWith("Port Descriptions")
                        || trimmed.startsWith("Port Id")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s{2,}", 2);
                if (parts.length >= 2 && parts[0].matches("^[A-Za-z0-9/]+(?:c\\d+)?$")) {
                    portToDesc.put(cleanNokiaValue(parts[0]), cleanNokiaValue(parts[1]));
                }
            }

            boolean inPort = false;
            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.matches("^[A-Za-z]:.*#\\s*show port\\s*$")) {
                    inPort = true;
                    continue;
                }

                if (!inPort) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z]:.*#.*$")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    inPort = false;
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.startsWith("=")
                        || trimmed.startsWith("-")
                        || trimmed.startsWith("Ports on Slot")
                        || trimmed.startsWith("Port")
                        || trimmed.startsWith("Id")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s{2,}");
                if (parts.length >= 4) {
                    String portId = safe(parts, 0);
                    if (portId.matches("^[A-Za-z0-9/]+(?:c\\d+)?$")) {
                        portToPhy.put(cleanNokiaValue(portId), safe(parts, 1));
                        portToProto.put(cleanNokiaValue(portId), safe(parts, 3));
                    }
                }
            }

            boolean inLag = false;
            String lastLagId = "";
            boolean lastWasLagRow = false;

            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.matches("^[A-Za-z]:.*#\\s*show lag description\\s*$")) {
                    inLag = true;
                    continue;
                }

                if (!inLag) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z]:.*#.*$")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    inLag = false;
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.startsWith("=")
                        || trimmed.startsWith("-")
                        || trimmed.startsWith("Lag Port States")
                        || trimmed.startsWith("LACP Status")) {
                    continue;
                }

                Matcher lagMatcher = Pattern.compile("^(\\d+)\\([^)]*\\)\\s+(\\S+)\\s+(\\S+)\\s+(.*)$").matcher(trimmed);
                if (lagMatcher.find()) {
                    lastLagId = "lag-" + lagMatcher.group(1).trim();
                    lagToPhy.put(lastLagId, cleanNokiaValue(lagMatcher.group(2)));
                    lagToProto.put(lastLagId, cleanNokiaValue(lagMatcher.group(3)));
                    lagToDesc.put(lastLagId, cleanNokiaValue(lagMatcher.group(4)));
                    lastWasLagRow = true;
                    continue;
                }

                Matcher memberMatcher = Pattern.compile("^([A-Za-z0-9/]+(?:c\\d+)?)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s*(.*)$").matcher(trimmed);
                if (memberMatcher.find()) {
                    String memberPort = cleanNokiaValue(memberMatcher.group(1));
                    if (!lastLagId.isEmpty()) {
                        lagToPorts.put(lastLagId, appendPipeValue(lagToPorts.get(lastLagId), memberPort));
                    }
                    lastWasLagRow = false;
                    continue;
                }

                if (lastWasLagRow && !lastLagId.isEmpty()) {
                    String curDesc = lagToDesc.getOrDefault(lastLagId, "");
                    String joiner = (curDesc.endsWith(".") || curDesc.endsWith("-") || curDesc.isEmpty()) ? "" : " ";
                    lagToDesc.put(lastLagId, (curDesc + joiner + trimmed).trim());
                }
            }

            boolean inServiceList = false;
            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.matches("^[A-Za-z]:.*#\\s*show service service-using vprn\\s*$")) {
                    inServiceList = true;
                    continue;
                }

                if (!inServiceList) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z]:.*#.*$")
                        || trimmed.startsWith("Matching Services")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    inServiceList = false;
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.startsWith("=")
                        || trimmed.startsWith("-")
                        || trimmed.startsWith("Services [")
                        || trimmed.startsWith("ServiceId")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 6 && parts[0].matches("^\\d+$")) {
                    serviceIdToName.put(cleanNokiaValue(parts[0]), cleanNokiaValue(parts[5]));
                }
            }

            boolean inBaseArp = false;
            for (String row : rows) {
                String trimmed = row.trim();

                if (trimmed.matches("^[A-Za-z]:.*#\\s*show router arp\\s*$")) {
                    inBaseArp = true;
                    continue;
                }

                if (!inBaseArp) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z]:.*#.*$")
                        || trimmed.startsWith("No. of ARP Entries")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    inBaseArp = false;
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.startsWith("=")
                        || trimmed.startsWith("-")
                        || trimmed.startsWith("ARP Table")
                        || trimmed.startsWith("IP Address")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 5 && parts[0].matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                    String ip = cleanNokiaValue(parts[0]);
                    String mac = cleanNokiaValue(parts[1]);
                    String arpType = cleanNokiaValue(parts[3]);
                    String ifaceName = cleanNokiaValue(parts[4]);
                    String binding = cleanNokiaValue(routerIfToPort.getOrDefault(ifaceName, ifaceName));
                    String port = resolveNokiaPortDisplay(binding, lagToPorts.get(binding));
                    String vpn = "Base";
                    String phy = resolveNokiaPhy(binding, ifaceName, routerIfToPhy, lagToPhy, lagToPorts, portToPhy);
                    String proto = resolveNokiaProto(binding, ifaceName, routerIfToProto, lagToProto, lagToPorts, portToProto);
                    String desc = resolveNokiaDesc(binding, ifaceName, lagToDesc, lagToPorts, portToDesc);

                    Str_ARP_ALL += "\n" + node + "," + model + "," + loopback + ","
                            + ip + "," + mac + ","
                            + arpType + "," + port + "," + vpn + "," + phy + "," + proto + "," + quote(desc);
                }
            }

            boolean inServiceArp = false;
            String currentServiceId = "";

            for (String row : rows) {
                String trimmed = row.trim();

                Matcher serviceArpMatcher = Pattern.compile("^[A-Za-z]:.*#\\s*show service id\\s+(\\d+)\\s+arp\\s*$").matcher(trimmed);
                if (serviceArpMatcher.find()) {
                    inServiceArp = true;
                    currentServiceId = serviceArpMatcher.group(1).trim();
                    continue;
                }

                if (!inServiceArp) {
                    continue;
                }

                if (trimmed.matches("^[A-Za-z]:.*#.*$")
                        || trimmed.startsWith("Connection closed by foreign host")
                        || trimmed.startsWith("Script done,")
                        || trimmed.startsWith("Enter IP address")) {
                    inServiceArp = false;
                    currentServiceId = "";
                    continue;
                }

                if (trimmed.isEmpty()
                        || trimmed.startsWith("=")
                        || trimmed.startsWith("-")
                        || trimmed.startsWith("ARP Table")
                        || trimmed.startsWith("IP Address")
                        || trimmed.startsWith("* indicates")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 6 && parts[0].matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                    String ip = cleanNokiaValue(parts[0]);
                    String mac = cleanNokiaValue(parts[1]);
                    String arpType = cleanNokiaValue(parts[3]);
                    String ifaceName = cleanNokiaValue(parts[4]);
                    String sap = cleanNokiaValue(parts[5]);
                    String basePort = extractSapPort(sap);

                    String port = sap.isEmpty() ? ifaceName : sap;
                    port = formatNokiaPortValue(port);
                    String vpn = serviceIdToName.getOrDefault(currentServiceId, currentServiceId);
                    String phy = portToPhy.getOrDefault(basePort, "");
                    String proto = portToProto.getOrDefault(basePort, "");
                    String desc = portToDesc.getOrDefault(basePort, ifaceName);

                    if ("loopback".equalsIgnoreCase(basePort)) {
                        phy = "Up";
                        proto = "Up";
                        desc = ifaceName;
                    }

                    Str_ARP_ALL += "\n" + node + "," + model + "," + loopback + ","
                            + ip + "," + mac + ","
                            + arpType + "," + port + "," + vpn + "," + phy + "," + proto + "," + quote(desc);
                }
            }
        }

        if (path.contains("ARP")) {
            System.out.println("Done " + path);
        }

        br.close();
        return Str_ARP_ALL;
    }
}

