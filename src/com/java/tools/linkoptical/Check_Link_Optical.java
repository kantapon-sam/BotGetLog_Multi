package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Check_Link_Optical {

    public static String Sub(BufferedReader br, String path) throws IOException {
        String line;
        String Str_LLDP_all = "";
        List<String> lines = new ArrayList<>();
        while ((line = br.readLine()) != null) {
            lines.add(line);
        }

        if (path.contains("_HW-LLDP-Link_OPTIC_")) {

            // ðŸ”¹ Extract IP à¸ˆà¸²à¸à¸Šà¸·à¹ˆà¸­à¹„à¸Ÿà¸¥à¹Œ
            String fileName = new File(path).getName();
            String ipLoopback = "";
            try {
                // ex: [13969]10.85.160.102_AGN-BGC-2_BKK10AGNS01_HW-LLDP-Link_OPTIC_2025-10-29
                String part = fileName.substring(fileName.indexOf("]") + 1);
                ipLoopback = part.split("_")[0].trim();
            } catch (Exception e) {
                System.out.println("âš ï¸ Cannot parse IP from filename: " + fileName);
            }

            // ðŸ”¹ à¸«à¸² Site code (à¸šà¸£à¸£à¸—à¸±à¸”à¸à¹ˆà¸­à¸™ screen-length)
            // ðŸ”¹ à¸«à¸² Site code à¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸”à¸—à¸µà¹ˆà¸¡à¸µ "screen-length"
            String siteCode = "";
            Pattern sitePattern = Pattern.compile("<([^>]+)>\\s*screen-length", Pattern.CASE_INSENSITIVE);
            for (String l : lines) {
                Matcher m = sitePattern.matcher(l);
                if (m.find()) {
                    siteCode = m.group(1).trim();  // à¹€à¸Šà¹ˆà¸™ "AGN-BGC-2_BKK10AGNS01"
                    break;
                }
            }

            String equipment = "";
            String version = "";

            for (String l : lines) {

                // ðŸŸ¢ 1. à¸”à¸¶à¸‡ Version à¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸” "VRP (R) software, Version 8.210 (CX600 V800R013C00SPC200)"
                if (l.toLowerCase().contains("vrp") && l.toLowerCase().contains("version")) {
                    Matcher m = Pattern.compile(
                            "Version\\s*:?\\s*([0-9\\.]+)\\s*\\(([^\\)]+)\\)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(l);
                    if (m.find()) {
                        String verNum = m.group(1).trim();   // à¹€à¸Šà¹ˆà¸™ 8.210
                        String model = m.group(2).trim();    // à¹€à¸Šà¹ˆà¸™ CX600 V800R013C00SPC200
                        version = verNum + " (" + model + ")";   // âœ… version = "8.210 (CX600 V800R013C00SPC200)"
                    }
                }

                // ðŸŸ¢ 2. à¸”à¸¶à¸‡ Equipment à¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸” "HUAWEI CX600-X16A uptime is ..."
                if (l.contains("HUAWEI") && l.contains("uptime")) {
                    try {
                        String part = l.substring(l.indexOf("HUAWEI") + 7, l.indexOf("uptime")).trim();
                        equipment = part; // âœ… CX600-X16A
                        break; // à¸«à¸¥à¸±à¸‡à¹„à¸”à¹‰à¸„à¹ˆà¸²à¹„à¸¡à¹ˆà¸•à¹‰à¸­à¸‡à¸«à¸²à¸•à¹ˆà¸­
                    } catch (Exception e) {
                        equipment = "";
                    }
                }
            }

            // ðŸ”¹ Parse LLDP neighbors (HUAWEI: display lldp neighbor)
            Map<String, String[]> lldpMap = new HashMap<>();
            boolean inLLDP = false;

            String currentLocalPort = null;
            String neighborName = null;
            String neighborPort = null;

Pattern pHasNeighbor = Pattern.compile(
    "^\\s*([^\\s]+)\\s+has\\s+(\\d+)\\s+neighbor(?:\\(s\\)|s)?\\s*:?\\s*$",
    Pattern.CASE_INSENSITIVE
);

            for (String l : lines) {

                if (!inLLDP) {
                    // âœ… Start: display lldp neighbor
                    if (l.contains("display lldp neighbor")) {
                        inLLDP = true;
                    }
                    continue;
                }

                // âœ… End: display version
                if (l.contains("display version")) {
                    inLLDP = false;
                    break;
                }

                String t = l.trim();
                if (t.isEmpty()) {
                    continue;
                }

                // Example: GigabitEthernet0/6/0 has 1 neighbor(s):
                Matcher mHas = pHasNeighbor.matcher(t);
                if (mHas.find()) {
                    // flush previous (safety)
                    if (currentLocalPort != null && neighborName != null && neighborPort != null) {
                        lldpMap.put(currentLocalPort, new String[]{neighborName, neighborPort});
                    }

                    currentLocalPort = mHas.group(1).trim();
                    neighborName = null;
                    neighborPort = null;
                    continue;
                }

                if (currentLocalPort == null) {
                    continue;
                }

                // neighbor = System name (cut after ':')
                String tl = t.toLowerCase();

                if (tl.startsWith("sysname:") || tl.startsWith("system name")) {
                    int p = t.indexOf(':');
                    if (p >= 0) {
                        neighborName = t.substring(p + 1).trim();
                    }
                }

                if (tl.startsWith("portid:") || tl.startsWith("port id")) {
                    int p = t.indexOf(':');
                    if (p >= 0) {
                        neighborPort = t.substring(p + 1).trim();
                    }
                }
            }

            // flush last (safety)
            if (currentLocalPort != null && neighborName != null && neighborPort != null) {
                lldpMap.put(currentLocalPort, new String[]{neighborName, neighborPort});
            }
            // ðŸ”¹ Parse display interface main
            boolean inIntf = false;
            String currentIntf = "";
            String currentState = "";
            String desc = "";
            String bw = "";
            String distance = "";
            String wavelength = "";
            String rxWarn = "";
            float txSum = 0, rxSum = 0;
            int txCount = 0, rxCount = 0;
            String inRate = "0", outRate = "0";
            String inTime = "", outTime = "";
            String crc = "0";
            boolean distanceCaptured = false;
            boolean prevWasOpticLine = false; // âœ… à¸•à¹‰à¸­à¸‡à¸­à¸¢à¸¹à¹ˆà¸™à¸­à¸à¸¥à¸¹à¸› à¹€à¸žà¸·à¹ˆà¸­à¸ˆà¸³à¸„à¹ˆà¸²à¸‚à¹‰à¸²à¸¡à¸šà¸£à¸£à¸—à¸±à¸”
            String prevLine = "";
            for (String l : lines) {

                if (l.contains("display interface")) {
                    inIntf = true;
                    continue;
                }
                if (!inIntf) {
                    continue;
                }

                // âœ… à¹€à¸ˆà¸­ interface à¹ƒà¸«à¸¡à¹ˆ
                if (l.matches("^[A-Za-z0-9\\|/]+\\s+current state.*")) {
                    // save à¸‚à¸­à¸‡à¸à¹ˆà¸­à¸™à¸«à¸™à¹‰à¸²
                    if (!currentIntf.isEmpty()) {
                        String nName = "", nIf = "";
                        if (lldpMap.containsKey(currentIntf)) {
                            nName = lldpMap.get(currentIntf)[0];
                            nIf = lldpMap.get(currentIntf)[1];
                        }

                        double inGb = safeParseToGbps(inRate);
                        double outGb = safeParseToGbps(outRate);
                        // âœ… à¹à¸ªà¸”à¸‡à¹€à¸‰à¸žà¸²à¸° GE à¸«à¸£à¸·à¸­ GigabitEthernet à¹à¸¥à¸°à¹„à¸¡à¹ˆà¹€à¸­à¸² GigabitEthernet0/0/0
                        if ((currentIntf.matches("^(GE|GigabitEthernet|100GE|[0-9]+\\|100GE).*"))
                                && !currentIntf.equalsIgnoreCase("GigabitEthernet0/0/0")) {

                            Str_LLDP_all += csvSafe(siteCode) + "," + csvSafe(ipLoopback) + "," + csvSafe(currentIntf) + ","
                                    + csvSafe(currentState) + "," + csvSafe(desc) + "," + csvSafe(nName) + ","
                                    + formatNeighborPort(nIf) + "," + bw + ","
                                    + String.format("%.2f", inGb) + "," + inTime + ","
                                    + String.format("%.2f", outGb) + "," + outTime + ","
                                    + wavelength + "," + distance + ","
                                    + String.format("%.2f", txSum) + "," + String.format("%.2f", rxSum) + ","
                                    + rxWarn + "," + crc + "," + version + "," + equipment + "\n";

                        }

                    }

                    // reset
                    currentIntf = l.split(" current state")[0].trim();
                    currentState = getValueAfter(l, "current state :", "\\(");
                    desc = bw = distance = wavelength = rxWarn = "";
                    txSum = rxSum = 0;
                    txCount = rxCount = 0;
                    inRate = outRate = "0";
                    inTime = outTime = "";
                    crc = "0";
                    distanceCaptured = false;  // âœ… à¸£à¸µà¹€à¸‹à¹‡à¸•à¸—à¸µà¹ˆà¸™à¸µà¹ˆà¹€à¸—à¹ˆà¸²à¸™à¸±à¹‰à¸™!
                }

                if (l.contains("Description:")) {
                    desc = l.split("Description:")[1].trim();
                }
                if (l.contains("Port BW:")) {
                    // ðŸ”¹ à¸•à¸±à¸”à¸‚à¹‰à¸­à¸„à¸§à¸²à¸¡à¸«à¸¥à¸±à¸‡ "Port BW:" à¹à¸¥à¹‰à¸§à¸«à¸¢à¸¸à¸”à¸à¹ˆà¸­à¸™ comma à¸•à¸±à¸§à¹à¸£à¸
                    bw = getValueAfter(l, "Port BW:", ",").trim();

                    // ðŸ”¹ à¸–à¹‰à¸²à¸¢à¸±à¸‡à¸¡à¸µ comma à¸«à¸£à¸·à¸­ space à¸„à¹‰à¸²à¸‡ à¹ƒà¸«à¹‰à¸¥à¹‰à¸²à¸‡à¸­à¸­à¸
                    bw = bw.replace(",", "").trim();

                    // ðŸ”¹ à¹€à¸œà¸·à¹ˆà¸­à¸šà¸²à¸‡à¸à¸£à¸“à¸µà¹„à¸¡à¹ˆà¸¡à¸µ comma à¹€à¸Šà¹ˆà¸™ "Port BW: 10G Transceiver ..."
                    if (bw.contains(" ")) {
                        bw = bw.split("\\s+")[0].trim();
                    }
                }

// âœ… WaveLength + Transmission Distance (à¸à¸±à¸™à¹€à¸šà¸´à¹‰à¸¥à¹à¸™à¹ˆà¸™à¸­à¸™ à¹à¸¥à¸°à¸•à¸±à¸”à¸„à¹ˆà¸²à¸«à¸¥à¸±à¸‡ , à¸­à¸­à¸)
                if (l.toLowerCase().contains("wavelength")) {
                    // âœ… à¸”à¸¶à¸‡à¸„à¹ˆà¸²à¸«à¸¥à¸±à¸‡ "WaveLength:" à¸ˆà¸™à¸ˆà¸šà¸«à¸£à¸·à¸­à¸à¹ˆà¸­à¸™à¹€à¸„à¸£à¸·à¹ˆà¸­à¸‡à¸«à¸¡à¸²à¸¢ comma (,)
                    int idx = l.toLowerCase().indexOf("wavelength:");
                    if (idx >= 0) {
                        String wavePart = l.substring(idx + "wavelength:".length()).trim();
                        if (wavePart.contains(",")) {
                            wavePart = wavePart.split(",")[0].trim();
                        }
                        wavelength = wavePart;  // âœ… à¹€à¸Šà¹ˆà¸™ "1310nm"
                    }

                    // ðŸ”¹ à¸•à¸£à¸§à¸ˆà¸ˆà¸±à¸š Transmission Distance à¹à¸¢à¸
                    Matcher m = Pattern.compile("transmission distance[:\\s]*([0-9]+)\\s*km", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find() && !distanceCaptured) {
                        distance = m.group(1).trim() + "km";
                        distanceCaptured = true;
                    }

                } else if (l.toLowerCase().contains("connector type")) {
                    // âŒ à¸•à¸±à¸” connector type à¸—à¸´à¹‰à¸‡ à¹à¸•à¹ˆà¸¢à¸±à¸‡à¸„à¸‡à¸«à¸²à¸„à¹ˆà¸² Transmission Distance à¸–à¹‰à¸²à¸¡à¸µà¹ƒà¸™à¸šà¸£à¸£à¸—à¸±à¸”à¸™à¸µà¹‰
                    Matcher m = Pattern.compile("transmission distance[:\\s]*([0-9]+)\\s*km", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find() && !distanceCaptured) {
                        distance = m.group(1).trim() + "km";
                        distanceCaptured = true;
                    }
                    continue; // à¸‚à¹‰à¸²à¸¡ connector line à¹„à¸›à¹€à¸¥à¸¢

                } else if (l.trim().toLowerCase().startsWith("transmission distance")) {
                    // âœ… à¸à¸£à¸“à¸µà¹€à¸ˆà¸­à¸šà¸£à¸£à¸—à¸±à¸” Transmission Distance à¹€à¸”à¸µà¹ˆà¸¢à¸§
                    if (!prevLine.toLowerCase().contains("wavelength") && !distanceCaptured) {
                        Matcher m = Pattern.compile("([0-9]+)\\s*km", Pattern.CASE_INSENSITIVE).matcher(l);
                        if (m.find()) {
                            distance = m.group(1).trim() + "km";
                            distanceCaptured = true;
                        }
                    }
                }

                if (l.contains("Rx Warning range")) {
                    rxWarn = getSafePart(l, "Rx Warning range:", "dBm").trim();
                    // âœ… à¸¥à¹‰à¸²à¸‡à¸Šà¹ˆà¸­à¸‡à¸§à¹ˆà¸²à¸‡à¸£à¸­à¸šà¹† comma à¹à¸¥à¹‰à¸§à¹à¸—à¸™à¹€à¸›à¹‡à¸™ <>
                    rxWarn = rxWarn.replaceAll("\\s*,\\s*", "<>");
                    // âœ… à¸•à¸±à¸”à¸Šà¹ˆà¸­à¸‡à¸§à¹ˆà¸²à¸‡à¸—à¸µà¹ˆà¸­à¸²à¸ˆà¹€à¸«à¸¥à¸·à¸­à¹ƒà¸™ []
                    rxWarn = rxWarn.replaceAll("\\s+", "");
                }

                // âœ… Optical Power (à¸£à¸­à¸‡à¸£à¸±à¸šà¸—à¸±à¹‰à¸‡ 100G à¹à¸¥à¸° GigabitEthernet)
                if (l.matches(".*Rx\\d+ Power:.*Tx\\d+ Power:.*")) {
                    // ðŸ”¹ à¹à¸šà¸š 100G: Rx0/Tx0 ... Rx3/Tx3
                    Pattern p = Pattern.compile("Rx\\d+ Power:\\s*([\\-0-9\\.]+)dBm,\\s*Tx\\d+ Power:\\s*([\\-0-9\\.]+)dBm");
                    Matcher m = p.matcher(l);
                    while (m.find()) {
                        rxSum += Float.parseFloat(m.group(1));
                        txSum += Float.parseFloat(m.group(2));
                        rxCount++;
                        txCount++;
                    }

                } else if (l.contains("Rx Power:")) {
                    try {
                        // âœ… à¸”à¸¶à¸‡à¸„à¹ˆà¸² Rx Power
                        String rxStr = getSafePart(l, "Rx Power:", "dBm").trim();
                        rxSum += Float.parseFloat(rxStr);
                        rxCount++;

                        // âœ… à¸”à¸¶à¸‡ Warning à¸«à¸£à¸·à¸­ Working range à¹ƒà¸™à¸šà¸£à¸£à¸—à¸±à¸”à¹€à¸”à¸µà¸¢à¸§à¸à¸±à¸™ (à¸–à¹‰à¸²à¸¡à¸µ)
                        if (l.contains("Warning range:") || l.contains("Working range:")) {
                            String key = l.contains("Warning range:") ? "Warning range:" : "Working range:";
                            String warn = getSafePart(l, key, "dBm").trim();

                            // ðŸ”¹ à¸—à¸³à¸„à¸§à¸²à¸¡à¸ªà¸°à¸­à¸²à¸”à¸£à¸¹à¸›à¹à¸šà¸š à¹€à¸Šà¹ˆà¸™ [-14.400<>0.499]
                            warn = warn.replaceAll("\\s*,\\s*", "<>").replaceAll("\\s+", "");

                            // âœ… à¸–à¹‰à¸²à¸¢à¸±à¸‡à¹„à¸¡à¹ˆà¸¡à¸µ [] à¸„à¸£à¸­à¸š à¹ƒà¸«à¹‰à¹ƒà¸ªà¹ˆ
                            if (!warn.startsWith("[") && !warn.endsWith("]")) {
                                warn = "[" + warn + "]";
                            }

                            rxWarn = warn;
                        }

                    } catch (Exception e) {
                        // ignore
                    }
                } else if (l.contains("Tx Power:")) {
                    try {
                        String txStr = getSafePart(l, "Tx Power:", "dBm").trim();
                        txSum += Float.parseFloat(txStr);
                        txCount++;
                    } catch (Exception e) {
                        // ignore
                    }
                }

// âœ… Input Peak Rate (à¸šà¸£à¸£à¸—à¸±à¸”à¹€à¸”à¸µà¸¢à¸§)
                if (l.contains("Input peak rate") && l.contains("Record time")) {
                    // à¸”à¸¶à¸‡à¸„à¹ˆà¸² bits/sec
                    inRate = getSafePart(l, "Input peak rate", "bits/sec").trim();

                    // à¸”à¸¶à¸‡à¹€à¸§à¸¥à¸²à¸«à¸¥à¸±à¸‡ Record time:
                    int idx = l.indexOf("Record time:");
                    if (idx >= 0) {
                        String t = l.substring(idx + "Record time:".length()).trim().replaceAll(",", "");
                        // âŒ à¸•à¸±à¸” timezone (+07:00) à¸–à¹‰à¸²à¸¡à¸µ
                        t = t.replaceAll("\\+\\d{2}:\\d{2}", "").trim();
                        inTime = t;
                    }
                }

// âœ… Output Peak Rate (à¸šà¸£à¸£à¸—à¸±à¸”à¹€à¸”à¸µà¸¢à¸§)
                if (l.contains("Output peak rate") && l.contains("Record time")) {
                    // à¸”à¸¶à¸‡à¸„à¹ˆà¸² bits/sec
                    outRate = getSafePart(l, "Output peak rate", "bits/sec").trim();

                    // à¸”à¸¶à¸‡à¹€à¸§à¸¥à¸²à¸«à¸¥à¸±à¸‡ Record time:
                    int idx2 = l.indexOf("Record time:");
                    if (idx2 >= 0) {
                        String t2 = l.substring(idx2 + "Record time:".length()).trim().replaceAll(",", "");
                        t2 = t2.replaceAll("\\+\\d{2}:\\d{2}", "").trim();
                        outTime = t2;
                    }
                }

                if (l.contains("CRC:")) {
                    String val = getValueAfter(l, "CRC:", "packets").trim();
                    // âœ… à¹€à¸à¹‡à¸šà¹€à¸‰à¸žà¸²à¸°à¸•à¸±à¸§à¹€à¸¥à¸‚ à¹€à¸Šà¹ˆà¸™ "0" à¸«à¸£à¸·à¸­ "12"
                    crc = val.replaceAll("[^0-9]", "").trim();
                }
                // âœ… à¹€à¸žà¸´à¹ˆà¸¡à¸šà¸£à¸£à¸—à¸±à¸”à¸™à¸µà¹‰à¹„à¸§à¹‰à¸—à¹‰à¸²à¸¢à¸ªà¸¸à¸”à¸‚à¸­à¸‡à¸¥à¸¹à¸›
                prevLine = l;
            }

            // âœ… à¹€à¸žà¸´à¹ˆà¸¡à¸ªà¸¸à¸”à¸—à¹‰à¸²à¸¢
            if (!currentIntf.isEmpty()) {
                String nName = "", nIf = "";
                if (lldpMap.containsKey(currentIntf)) {
                    nName = lldpMap.get(currentIntf)[0];
                    nIf = lldpMap.get(currentIntf)[1];
                }
                double inGb = safeParseToGbps(inRate);
                double outGb = safeParseToGbps(outRate);

                // âœ… à¸à¸£à¸­à¸‡à¸žà¸­à¸£à¹Œà¸•à¸ªà¸¸à¸”à¸—à¹‰à¸²à¸¢
                if ((currentIntf.matches("^(GE|GigabitEthernet|100GE|[0-9]+\\|100GE).*"))
                        && !currentIntf.equalsIgnoreCase("GigabitEthernet0/0/0")) {

                    Str_LLDP_all += csvSafe(siteCode) + "," + csvSafe(ipLoopback) + "," + csvSafe(currentIntf) + ","
                            + csvSafe(currentState) + "," + csvSafe(desc) + "," + csvSafe(nName) + ","
                            + formatNeighborPort(nIf) + "," + bw + ","
                            + String.format("%.2f", inGb) + "," + inTime + ","
                            + String.format("%.2f", outGb) + "," + outTime + ","
                            + wavelength + "," + distance + ","
                            + String.format("%.2f", txSum) + "," + String.format("%.2f", rxSum) + ","
                            + rxWarn + "," + crc + "," + version + "," + equipment + "\n";

                }
            }

            //System.out.println("âœ… Parsed HW Optical: " + siteCode + " (" + ipLoopback + ") â€” " + equipment);
        } // âœ… ZTE Section (à¸£à¸­à¸‡à¸£à¸±à¸š QSFP / SFP / Neighbor / Optical Power / Peak rate)
        else if (path.contains("_ZTE-LLDP-Link_OPTIC_")) {

            Map<String, String[]> powerMap = new HashMap<>();
            String siteCode = "";
            String ipLoopback = "";
            String equipment = "";
            String version = "";

            // ðŸ”¹ Extract IP à¸ˆà¸²à¸à¸Šà¸·à¹ˆà¸­à¹„à¸Ÿà¸¥à¹Œ
            try {
                String fileName = new File(path).getName();
                String part = fileName.substring(fileName.indexOf("]") + 1);
                ipLoopback = part.split("_")[0].trim();
            } catch (Exception e) {
                System.out.println("âš ï¸ Cannot parse IP from filename: " + path);
            }

            // ðŸ”¹ Site Code à¹€à¸Šà¹ˆà¸™ DN1-NKR1600
            Pattern sitePatternZTE = Pattern.compile("^(\\S+)#terminal length", Pattern.CASE_INSENSITIVE);
            for (String l : lines) {
                Matcher m = sitePatternZTE.matcher(l);
                if (m.find()) {
                    siteCode = m.group(1).trim();
                    break;
                }
            }

            // ðŸ”¹ Version + Equipment
            for (String l : lines) {
                if (l.contains("ZTE ZXCTN Software")) {
                    Matcher m = Pattern.compile("Version:\\s*([^,]+),\\s*Release", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find()) {
                        version = m.group(1).trim();  // à¹€à¸Šà¹ˆà¸™ CTN9000-E V5.00.10.70
                    }
                }
                if (l.startsWith("ZXCTN")) {
                    equipment = l.trim(); // à¹€à¸Šà¹ˆà¸™ ZXCTN 9000-8EA HDC
                }
            }
// =====================================================
// âœ… Parse LLDP Neighbors (ZTE) â€” à¸„à¸£à¸­à¸šà¸„à¸¥à¸¸à¸¡à¸—à¸¸à¸à¹€à¸„à¸ªà¸•à¹ˆà¸­ string
// =====================================================
            Map<String, String[]> lldpMap = new LinkedHashMap<>();
            boolean inLLDP = false;

            for (int i = 0; i < lines.size(); i++) {

                String l = lines.get(i);
                // ðŸŽ¯ à¹€à¸£à¸´à¹ˆà¸¡à¸­à¹ˆà¸²à¸™à¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­à¸«à¸±à¸§à¸•à¸²à¸£à¸²à¸‡ LLDP
                if (l.contains("Local Interface") && l.contains("System Name")) {
                    inLLDP = true;
                    continue;
                }

                // ðŸš« à¸«à¸¢à¸¸à¸”à¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­ hostname à¹€à¸Šà¹ˆà¸™ DN1-NKR1600#show version
                if (inLLDP && l.matches("(?i).+#\\s*show\\s*version.*")) {
                    inLLDP = false;
                    break;
                }

                if (!inLLDP) {
                    continue;
                }

                // ðŸ§¹ à¸‚à¹‰à¸²à¸¡ header / à¹€à¸ªà¹‰à¸™à¸„à¸±à¹ˆà¸™ / à¸§à¹ˆà¸²à¸‡
                if (l.contains("---") || l.trim().isEmpty()) {
                    continue;
                }

                // ðŸ”¹ à¸£à¸§à¸¡à¸šà¸£à¸£à¸—à¸±à¸”à¸•à¹ˆà¸­à¹€à¸™à¸·à¹ˆà¸­à¸‡
                String fullLine = l.trim();
                if (i + 1 < lines.size() && lines.get(i + 1).startsWith(" ")) {
                    fullLine += " " + lines.get(i + 1).trim();
                    i++;
                }

                // à¸¥à¹‰à¸²à¸‡à¸Šà¹ˆà¸­à¸‡à¸§à¹ˆà¸²à¸‡à¸‹à¹‰à¸³
                fullLine = fullLine.replaceAll("\\s{2,}", " ").trim();
                String[] parts = fullLine.split("\\s+");
                if (parts.length < 6) {
                    continue;
                }
                String localIf = parts[0];
                String portId = "";
                String sysName = "";

// ðŸ”¹ à¹€à¸„à¸ª ZTE à¸—à¸µà¹ˆ Port ID à¸­à¸¢à¸¹à¹ˆ 2 à¸šà¸£à¸£à¸—à¸±à¸” à¹€à¸Šà¹ˆà¸™
// gei-0/0/0/2 NB ... GigabitEthernet 104 AYAAYA060CW
//                                  7/0/0
// â†’ à¸•à¹‰à¸­à¸‡à¸à¸²à¸£: portId = GigabitEthernet7/0/0, sysName = AYAAYA060CW
                if ("GigabitEthernet".equals(parts[3])
                        && parts[parts.length - 1].matches("\\d+/\\d+/\\d+")) {

                    portId = "GigabitEthernet" + parts[parts.length - 1];  // GigabitEthernet7/0/0
                    sysName = parts[parts.length - 2];                     // AYAAYA060CW

                } else if (parts[3].equals("GigabitEthernet") && parts.length == 9) {
                    // à¹€à¸Šà¹ˆà¸™ gei-0/7/0/3 NB xx GigabitEthernet 104 NMANMADN00W_S5 0/0/11 320_WIFI1G
                    portId = parts[3] + parts[7];               // GigabitEthernet0/0/11
                    sysName = parts[5] + parts[6] + parts[8];   // NMANMADN00W_S5320_WIFI1G

                } else if (parts[3].equals("GigabitEthernet") && parts.length == 8) {
                    // à¹€à¸Šà¹ˆà¸™ gei-0/7/0/3 NB xx GigabitEthernet 104 NMANMADN00W_S5 320_WIFI1G
                    portId = parts[3] + parts[6];               // GigabitEthernet0/0/11
                    sysName = parts[5] + parts[7];              // NMANMADN00W_S5320_WIFI1G

                } else if (parts[3].equals("port")) {
                    // à¹€à¸Šà¹ˆà¸™ xgei-0/2/0/1 NB xx port 0 120 DN1-XXX
                    portId = parts[3] + parts[4];
                    sysName = parts.length > 6 ? parts[6] : "";

                } else if (parts.length == 7) {
                    // à¹€à¸Šà¹ˆà¸™ xgei-0/7/1/20 NB xx 0 108 S07165-AF01-AP 01
                    portId = parts[3];
                    sysName = parts[5] + parts[6];

                } else {
                    // âœ… à¸›à¸à¸•à¸´à¸—à¸±à¹ˆà¸§à¹„à¸›
                    portId = parts[3];
                    sysName = parts[5];
                }

                // âœ… à¹€à¸„à¸ªà¸žà¸´à¹€à¸¨à¸©: à¸•à¹ˆà¸­à¸šà¸£à¸£à¸—à¸±à¸”à¸–à¸±à¸”à¹„à¸› (à¹€à¸¥à¸‚à¸­à¸¢à¹ˆà¸²à¸‡à¹€à¸”à¸µà¸¢à¸§ à¹€à¸Šà¹ˆà¸™ "01")
                if (i + 1 < lines.size() && lines.get(i + 1).trim().matches("^[0-9]+$")) {
                    sysName = sysName + lines.get(i + 1).trim();
                    i++;
                }

                // âœ… à¸›à¹‰à¸­à¸‡à¸à¸±à¸™ GigabitEthernet à¸¡à¸µà¸Šà¹ˆà¸­à¸‡à¸§à¹ˆà¸²à¸‡à¹€à¸à¸´à¸™ (à¹€à¸Šà¹ˆà¸™ GigabitEthernet 0/0/11)
                portId = portId.replace(" ", "");

                // âœ… à¹€à¸à¹‡à¸šà¸œà¸¥à¸¥à¸±à¸žà¸˜à¹Œ
                lldpMap.put(localIf, new String[]{sysName.trim(), portId.trim()});
            }

// âœ… Debug
            //        System.out.println("âœ… Parsed ZTE LLDP Neighbors: " + lldpMap.size());
            /*for (Map.Entry<String, String[]> e : lldpMap.entrySet()) {
    System.out.printf("ðŸ”— %-15s â†’ %-35s (%s)%n",
            e.getKey(), e.getValue()[0], e.getValue()[1]);
}*/
// =====================================================
// âœ… à¸”à¸¶à¸‡à¹€à¸‰à¸žà¸²à¸°à¸ªà¹ˆà¸§à¸™ Optical à¸£à¸°à¸«à¸§à¹ˆà¸²à¸‡ #show opt brief â†’ #show interface
// =====================================================
            List<String> opticalLines = new ArrayList<>();
            boolean inOpticalSection = false;

            for (String l : lines) {
                // à¹€à¸£à¸´à¹ˆà¸¡à¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­ show opt brief
                if (l.contains("#show opt brief")) {
                    inOpticalSection = true;
                    continue;
                }
                // à¸ˆà¸šà¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­ show interface
                if (l.contains("#show interface")) {
                    inOpticalSection = false;
                    break;
                }

                if (inOpticalSection) {
                    opticalLines.add(l);
                }
            }

// âœ… à¹à¸ªà¸”à¸‡à¸œà¸¥à¹€à¸žà¸·à¹ˆà¸­ debug à¸§à¹ˆà¸²à¸”à¸¶à¸‡à¹„à¸”à¹‰à¹€à¸‰à¸žà¸²à¸°à¸ªà¹ˆà¸§à¸™ Optical à¸ˆà¸£à¸´à¸‡à¹„à¸«à¸¡
            //   System.out.println("===== OPTICAL SECTION EXTRACTED =====");
            //      System.out.println("===== TOTAL OPTICAL LINES: " + opticalLines.size() + " =====");
// =====================================================
// âœ… PARSE Optical Section (ZTE show opt brief)
// =====================================================
            List<Map<String, String>> opticalRecords = new ArrayList<>();

            String currentPort = "";
            String bw = "", wavelength = "", warn = "", status = "", distance = "";
            String optDistance = "";  // âœ… à¹ƒà¸Šà¹‰à¸Šà¸·à¹ˆà¸­à¸•à¹ˆà¸²à¸‡à¸­à¸­à¸à¹„à¸› à¹„à¸¡à¹ˆà¸Šà¸™à¸à¸±à¸š distance à¸•à¸­à¸™ merge
            List<Float> rxVals = new ArrayList<>();
            List<Float> txVals = new ArrayList<>();
            String pendingTxValue = null;
            for (int i = 0; i < opticalLines.size(); i++) {
                String l = opticalLines.get(i).trim();
                if (l.isEmpty() || l.startsWith("Interface") || l.startsWith("TxPower") || l.startsWith("02:")) {
                    continue;
                }

                // ðŸŽ¯ à¹€à¸£à¸´à¹ˆà¸¡à¸žà¸­à¸£à¹Œà¸•à¹ƒà¸«à¸¡à¹ˆ
                if (l.matches("^(gei|xgei|cgei|ptp)-[0-9/]+.*")) {

                    // ðŸ”¹ à¸–à¹‰à¸²à¸¡à¸µà¸žà¸­à¸£à¹Œà¸•à¸à¹ˆà¸­à¸™à¸«à¸™à¹‰à¸² â€” à¸ªà¸£à¸¸à¸›à¹à¸¥à¸°à¹€à¸à¹‡à¸š
                    // ðŸ”¹ à¸–à¹‰à¸²à¸¡à¸µà¸žà¸­à¸£à¹Œà¸•à¸à¹ˆà¸­à¸™à¸«à¸™à¹‰à¸² â€” à¸ªà¸£à¸¸à¸›à¹à¸¥à¸°à¹€à¸à¹‡à¸š
                    if (!currentPort.isEmpty()) {

                        float rxAvg = rxVals.isEmpty() ? 0f : (float) rxVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                        float txAvg = txVals.isEmpty() ? 0f : (float) txVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);

                        // âœ… à¹à¸ªà¸”à¸‡à¸£à¸²à¸¢à¸¥à¸°à¹€à¸­à¸µà¸¢à¸” lane-by-lane
                        //     System.out.println("\nðŸ“¶ Port: " + currentPort);
                        //   System.out.println("   Rx lanes = " + rxVals);
                        // System.out.println("   Tx lanes = " + txVals);
                        // System.out.printf("   â†’ AvgRx = %.2f, AvgTx = %.2f%n", rxAvg, txAvg);
                        Map<String, String> rec = new LinkedHashMap<>();
                        rec.put("Port", currentPort.trim().toLowerCase().replaceAll("\\s+", ""));

                        rec.put("MaxBW", bw);
                        rec.put("Wavelength", wavelength);
                        rec.put("Distance", optDistance);

                        rec.put("RxPower", String.format("%.2f", rxAvg));
                        rec.put("TxPower", String.format("%.2f", txAvg));
                        rec.put("WarningRange", warn);
                        rec.put("Status", status);

                        opticalRecords.add(rec);

                        rxVals.clear();
                        txVals.clear();
                    }

// âœ… à¹€à¸£à¸´à¹ˆà¸¡à¸šà¸£à¸£à¸—à¸±à¸”à¹ƒà¸«à¸¡à¹ˆ
                    String[] parts = l.split("\\s+");
                    currentPort = parts[0];

// âœ… à¸£à¸µà¹€à¸‹à¹‡à¸•à¸„à¹ˆà¸²
                    bw = "";
                    wavelength = "";
                    optDistance = "";
                    pendingTxValue = null;
                    if (parts.length > 1 && parts[1].matches("^[0-9]+G.*")) {
                        bw = parts[1].split("-")[0].replaceAll("[^0-9G]", "").trim();
                    }

// âœ… à¸”à¸¶à¸‡ Optical Module + Wavelength à¹€à¸Šà¹ˆà¸™ "1G-40km-SFP   1310nm"
                    Matcher mOpt = Pattern.compile(
                            "^(gei|xgei|cgei|ptp)-[0-9/]+\\s+([^\\s]+)\\s+([0-9]+nm)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(l);

                    if (mOpt.find()) {
                        String bwRaw = mOpt.group(2).trim();
                        if (bwRaw.contains("-")) {
                            bwRaw = bwRaw.split("-")[0];
                        }
                        bw = bwRaw.replaceAll("[^0-9G]", "").trim(); // âœ… à¸•à¸±à¸”à¹€à¸¨à¸©à¸­à¸­à¸ à¹€à¸Šà¹ˆà¸™ "1G-10km-SFP" â†’ "1G"
                        wavelength = mOpt.group(3).trim();
                    }

// âœ… à¸•à¸£à¸§à¸ˆà¸«à¸²à¸„à¹ˆà¸² Transmission Distance (km) à¸ˆà¸²à¸à¸—à¸±à¹‰à¸‡à¸šà¸£à¸£à¸—à¸±à¸”
                    Pattern distPattern = Pattern.compile("(\\d+)\\s*km", Pattern.CASE_INSENSITIVE);
                    Matcher distMatch = distPattern.matcher(l);
                    if (distMatch.find()) {
                        optDistance = distMatch.group(1).trim() + "km";
                    } else {
                        // âœ… à¸–à¹‰à¸²à¹„à¸¡à¹ˆà¹€à¸ˆà¸­à¹ƒà¸™à¸šà¸£à¸£à¸—à¸±à¸”à¸™à¸µà¹‰ à¹ƒà¸«à¹‰à¸¥à¸­à¸‡à¹€à¸Šà¹‡à¸à¸šà¸£à¸£à¸—à¸±à¸”à¸à¹ˆà¸­à¸™à¸«à¸™à¹‰à¸²
                        if (i > 0) {
                            String prevLine = opticalLines.get(i - 1).trim();
                            Matcher prev = distPattern.matcher(prevLine);
                            if (prev.find()) {
                                optDistance = prev.group(1).trim() + "km";
                            }
                        }
                        // âœ… à¸–à¹‰à¸²à¸¢à¸±à¸‡à¹„à¸¡à¹ˆà¹€à¸ˆà¸­à¸­à¸µà¸ à¹ƒà¸«à¹‰à¹€à¸Šà¹‡à¸à¸šà¸£à¸£à¸—à¸±à¸”à¸–à¸±à¸”à¹„à¸›
                        if (optDistance.isEmpty() && i + 1 < opticalLines.size()) {
                            String nextLine = opticalLines.get(i + 1).trim();
                            Matcher next = distPattern.matcher(nextLine);
                            if (next.find()) {
                                optDistance = next.group(1).trim() + "km";
                            }
                        }
                    }

                    warn = "";
                    status = "";

// âœ… à¸”à¸¶à¸‡ Rx à¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸”à¹à¸£à¸ (à¸žà¸£à¹‰à¸­à¸¡ WarningRange)
                    Matcher m = Pattern.compile("([-0-9.]+)/\\[([-0-9.,]+)]").matcher(l);
                    int idx = 0;
                    while (m.find()) {
                        float val = 0f;
                        try {
                            val = Float.parseFloat(m.group(1));
                        } catch (Exception e) {
                            val = 0f;
                        }
                        if (idx == 0) {
                            // ðŸ”¹ match à¹à¸£à¸ = Rx
                            rxVals.add(val);
                            // à¹ƒà¸Šà¹‰ warn à¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸”à¹à¸£à¸à¹€à¸—à¹ˆà¸²à¸™à¸±à¹‰à¸™
                            warn = "[" + m.group(2).replaceAll("\\s*,\\s*", "<>") + "]";
                        } else {
                            // ðŸ”¹ match à¸—à¸µà¹ˆ 2,3,... = Tx (à¸›à¸à¸•à¸´ à¸–à¹‰à¸²à¸­à¸¢à¸¹à¹ˆà¸šà¸£à¸£à¸—à¸±à¸”à¹€à¸”à¸µà¸¢à¸§à¸à¸±à¸™)
                            txVals.add(val);
                        }
                        idx++;
                    }

// âœ… à¸•à¸£à¸§à¸ˆà¸«à¸²à¹€à¸¥à¸‚ Tx à¸—à¸µà¹ˆà¸­à¸²à¸ˆà¸–à¸¹à¸à¸•à¸±à¸”à¸šà¸£à¸£à¸—à¸±à¸” (à¹€à¸Šà¹ˆà¸™ ... -3.4 à¹à¸¥à¹‰à¸§à¸šà¸£à¸£à¸—à¸±à¸”à¸–à¸±à¸”à¹„à¸›à¸‚à¸¶à¹‰à¸™à¸•à¹‰à¸™à¸”à¹‰à¸§à¸¢ "/[")
                    if (pendingTxValue == null) {
                        // ðŸ” à¹€à¸„à¸ª Tx à¸–à¸¹à¸à¸•à¸±à¸”à¸šà¸£à¸£à¸—à¸±à¸”à¹à¸šà¸š "1.5/" à¹à¸¥à¹‰à¸§à¸šà¸£à¸£à¸—à¸±à¸”à¸–à¸±à¸”à¹„à¸›à¹€à¸›à¹‡à¸™ "[-4.7,4] ..."
                        Matcher tailNumWithSlash = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)/\\s*$").matcher(l);
                        if (tailNumWithSlash.find()) {
                            // à¹€à¸à¹‡à¸šà¸„à¹ˆà¸² Tx à¹„à¸§à¹‰à¸£à¸§à¸¡à¸à¸±à¸šà¸šà¸£à¸£à¸—à¸±à¸”à¸–à¸±à¸”à¹„à¸›
                            pendingTxValue = tailNumWithSlash.group(1);
                        } else {
                            // à¹€à¸„à¸ªà¹€à¸”à¸´à¸¡: à¹€à¸¥à¸‚à¸­à¸¢à¸¹à¹ˆà¸—à¹‰à¸²à¸¢à¸šà¸£à¸£à¸—à¸±à¸”à¹€à¸¥à¸¢ (à¹„à¸¡à¹ˆà¸¡à¸µ / à¸•à¹ˆà¸­à¸—à¹‰à¸²à¸¢)
                            Matcher tailNum = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)\\s*$").matcher(l);
                            if (tailNum.find()) {
                                String lastNum = tailNum.group(1);
                                try {
                                    float numVal = Float.parseFloat(lastNum);
                                    if (!rxVals.isEmpty()) {
                                        float lastRx = rxVals.get(rxVals.size() - 1);
                                        // à¸–à¹‰à¸²à¹€à¸¥à¸‚à¸—à¹‰à¸²à¸¢à¹„à¸¡à¹ˆà¹€à¸—à¹ˆà¸²à¸à¸±à¸š Rx â†’ à¸–à¸·à¸­à¸§à¹ˆà¸²à¹€à¸›à¹‡à¸™ Tx à¸—à¸µà¹ˆà¹‚à¸”à¸™à¹à¸¢à¸à¸šà¸£à¸£à¸—à¸±à¸”
                                        if (Math.abs(numVal - lastRx) > 0.0001f) {
                                            pendingTxValue = lastNum;
                                        }
                                    } else {
                                        // à¹„à¸¡à¹ˆà¸¡à¸µ Rx à¹à¸•à¹ˆà¸¡à¸µà¹€à¸¥à¸‚ â†’ à¸­à¸²à¸ˆà¹€à¸›à¹‡à¸™ Tx à¸­à¸¢à¹ˆà¸²à¸‡à¹€à¸”à¸µà¸¢à¸§
                                        pendingTxValue = lastNum;
                                    }
                                } catch (NumberFormatException ex) {
                                    // ignore
                                }
                            }
                        }
                    }

                    // âœ… à¸–à¸±à¸”à¹„à¸›à¸„à¸·à¸­ Tx â†’ à¸•à¹‰à¸­à¸‡à¸§à¸™à¸­à¹ˆà¸²à¸™à¸•à¹ˆà¸­
                    int j = i + 1;
                    while (j < opticalLines.size()) {
                        String next = opticalLines.get(j).trim();

                        // âŒ à¸‚à¹‰à¸²à¸¡à¸šà¸£à¸£à¸—à¸±à¸”à¹€à¸¨à¸© à¹€à¸Šà¹ˆà¸™ "à¹€à¸¨à¸© 0.5" à¸«à¸£à¸·à¸­ "9.5"
                        if (next.matches("^[0-9.]+\\s+[0-9.]+$")) {
                            j++;
                            continue;
                        }

                        if (next.isEmpty()) {
                            j++;
                            continue;
                        }

                        // à¸–à¹‰à¸²à¹€à¸ˆà¸­ port à¹ƒà¸«à¸¡à¹ˆ â†’ à¸ˆà¸š loop
                        if (next.matches("^(gei|xgei|cgei|ptp)-[0-9/]+.*")) {
                            i = j - 1;
                            break;
                        }

                        // âœ… à¸à¸£à¸“à¸µ Tx à¸–à¸¹à¸ split: à¸šà¸£à¸£à¸—à¸±à¸”à¸à¹ˆà¸­à¸™à¸ˆà¸šà¸”à¹‰à¸§à¸¢à¹€à¸¥à¸‚ à¹à¸¥à¸°à¸šà¸£à¸£à¸—à¸±à¸”à¸™à¸µà¹‰à¸‚à¸¶à¹‰à¸™à¸•à¹‰à¸™à¸”à¹‰à¸§à¸¢ "/[...]"
                        if (pendingTxValue != null && (next.startsWith("/[") || next.startsWith("["))) {
                            // next à¸­à¸²à¸ˆà¹€à¸›à¹‡à¸™ "/[-8.2,1.5] ..." à¸«à¸£à¸·à¸­ "[-8.2,1.5] ..."
                            String combined = pendingTxValue + (next.startsWith("/[") ? "" : "/") + next;   // à¹€à¸Šà¹ˆà¸™ "-3.4/[-8.2,1.5] Unknown"
                            Matcher mVal2 = Pattern.compile("([-0-9.]+)/\\[([-0-9.,]+)]").matcher(combined);
                            if (mVal2.find()) {
                                float val2 = 0f;
                                try {
                                    val2 = Float.parseFloat(mVal2.group(1));
                                } catch (Exception e) {
                                    val2 = 0f;
                                }
                                // à¹ƒà¸ªà¹ˆà¹€à¸›à¹‡à¸™ Tx
                                txVals.add(val2);
                                if (warn.isEmpty()) {
                                    warn = "[" + mVal2.group(2).replaceAll("\\s*,\\s*", "<>") + "]";
                                }
                            }
                            pendingTxValue = null;
                        } else {
                            // âœ… à¸ˆà¸±à¸šà¸„à¹ˆà¸² -x.x/[â€¦] à¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸”à¸•à¸²à¸¡à¸›à¸à¸•à¸´
                            Matcher mVal = Pattern.compile("([-0-9.]+)/\\[([-0-9.,]+)]").matcher(next);
                            if (mVal.find()) {
                                float val = 0f;
                                try {
                                    val = Float.parseFloat(mVal.group(1));
                                } catch (Exception e) {
                                    val = 0f;
                                }

                                // ðŸ”¹ à¸–à¹‰à¸²à¸‚à¸™à¸²à¸” list à¹„à¸¡à¹ˆà¹€à¸—à¹ˆà¸²à¸à¸±à¸™ â†’ à¸­à¸±à¸™à¸™à¸µà¹‰à¸„à¸·à¸­ Tx
                                if (rxVals.size() > txVals.size()) {
                                    txVals.add(val);
                                } else {
                                    rxVals.add(val);
                                }
                                // âŒ à¸­à¸¢à¹ˆà¸²à¹ƒà¸«à¹‰ warn à¸–à¸¹à¸à¸—à¸±à¸šà¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸”à¸«à¸¥à¸±à¸‡
                                if (warn.isEmpty()) {
                                    warn = "[" + mVal.group(2).replaceAll("\\s*,\\s*", "<>") + "]";
                                }
                            }
                        }

                        // âœ… à¸ˆà¸±à¸š Status (Normal/Abnormal/Unknown)
                        if (status.isEmpty() && next.matches(".*\\s+(Normal|Abnormal|Unknown)\\s*$")) {
                            status = next.replaceAll(".*\\s+(Normal|Abnormal|Unknown)\\s*$", "$1");
                        }

                        j++;
                    }

                } else if (currentPort != null && l.toLowerCase().contains("unknown")) {
                    // âœ… à¸£à¸­à¸‡à¸£à¸±à¸šà¸—à¸¸à¸à¹€à¸„à¸ª Unknown à¹€à¸Šà¹ˆà¸™ N/A/[-5.0,2.0] Unknown à¸«à¸£à¸·à¸­ N/A / [-7.1, -3.5] Unknown
                    Matcher m2 = Pattern.compile("N/?A\\s*/\\s*\\[\\s*([-0-9\\.]+)\\s*[,<>]\\s*([-0-9\\.]+)\\s*\\]", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m2.find()) {
                        float rx = 0f, tx = 0f;
                        try {
                            rx = Float.parseFloat(m2.group(1));
                            tx = Float.parseFloat(m2.group(2));
                        } catch (Exception e) {
                            rx = 0f;
                            tx = 0f;
                        }
                        rxVals.add(rx);
                        txVals.add(tx);
                    }

                    if (status.isEmpty()) {
                        status = "Unknown";
                    }
                }

                if (i == opticalLines.size() - 1 && !currentPort.isEmpty() && (!rxVals.isEmpty() || !txVals.isEmpty())) {
                    float rxAvg = (float) rxVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                    float txAvg = (float) txVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);

                    Map<String, String> rec = new LinkedHashMap<>();
                    rec.put("Port", currentPort.trim().toLowerCase().replaceAll("\\s+", ""));
                    rec.put("MaxBW", bw);
                    rec.put("Wavelength", wavelength);
                    rec.put("Distance", optDistance);
                    rec.put("RxPower", String.format("%.2f", rxAvg));
                    rec.put("TxPower", String.format("%.2f", txAvg));
                    rec.put("WarningRange", warn);
                    rec.put("Status", status);
                    opticalRecords.add(rec);
                }

            }

// âœ… à¹€à¸à¹‡à¸šà¸žà¸­à¸£à¹Œà¸•à¸ªà¸¸à¸”à¸—à¹‰à¸²à¸¢
            if (!currentPort.isEmpty() && (!rxVals.isEmpty() || !txVals.isEmpty())) {
                float rxAvg = (float) rxVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                float txAvg = (float) txVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);

                // âœ… Debug à¸„à¹ˆà¸²à¹€à¸‰à¸¥à¸µà¹ˆà¸¢ lane à¸ªà¸¸à¸”à¸—à¹‰à¸²à¸¢
                //     System.out.println("\nðŸ“¶ Port: " + currentPort);
                //       System.out.println("   Rx lanes = " + rxVals);
                //     System.out.println("   Tx lanes = " + txVals);
                //     System.out.printf("   â†’ AvgRx = %.2f, AvgTx = %.2f%n", rxAvg, txAvg);
                Map<String, String> rec = new LinkedHashMap<>();
                rec.put("Port", currentPort.trim().toLowerCase().replaceAll("\\s+", ""));

                rec.put("MaxBW", bw);
                rec.put("Wavelength", wavelength);
                rec.put("Distance", optDistance);

                rec.put("RxPower", String.format("%.2f", rxAvg));
                rec.put("TxPower", String.format("%.2f", txAvg));
                if (warn != null && warn.contains(",")) {
                    warn = warn.replaceAll("\\s*,\\s*", "<>");
                }

                rec.put("WarningRange", warn);
                rec.put("Status", status);
                opticalRecords.add(rec);
            }

// âœ… à¹à¸ªà¸”à¸‡à¸œà¸¥à¸•à¸£à¸§à¸ˆà¸ªà¸­à¸š
            /*     System.out.println("===== PARSED ZTE OPTICAL RECORDS =====");
            for (Map<String, String> o : opticalRecords) {
                System.out.printf("%-15s | BW=%-4s | WL=%-7s | Rx=%-6s | Tx=%-6s | Range=%-15s | %-10s%n",
                        o.get("Port"), o.get("MaxBW"), o.get("Wavelength"),
                        o.get("RxPower"), o.get("TxPower"),
                        o.get("WarningRange"), o.get("Status"));
            }
            System.out.println("===== TOTAL OPTICAL PORTS: " + opticalRecords.size() + " =====");*/
            // =====================================================
// âœ… à¸”à¸¶à¸‡à¹€à¸‰à¸žà¸²à¸°à¸ªà¹ˆà¸§à¸™ Interface à¸£à¸°à¸«à¸§à¹ˆà¸²à¸‡ #show interface â†’ #quit
// =====================================================
            List<String> interfaceLines = new ArrayList<>();
            boolean inInterface = false;

            for (String l : lines) {
                if (l.contains("#show interface")) {
                    inInterface = true;
                    continue;
                }
                if (l.contains("#quit")) {
                    inInterface = false;
                    break;
                }

                if (inInterface) {
                    interfaceLines.add(l);
                }
            }

// âœ… Debug à¸•à¸£à¸§à¸ˆà¸ªà¸­à¸š
            // System.out.println("===== INTERFACE SECTION EXTRACTED =====");
            /*for (String s : interfaceLines) {
    System.out.println(s);
}*/
            //    System.out.println("===== TOTAL INTERFACE LINES: " + interfaceLines.size() + " =====");
// =====================================================
// âœ… Parse show interface (ZTE)
// =====================================================
            List<Map<String, String>> intfRecords = new ArrayList<>();
            String currentPort_if = "", desc_if = "", oper_if = "", crc_if = "";
            double inPeakGbps_if = 0, outPeakGbps_if = 0;
            String inTime_if = "", outTime_if = "";

            for (int i = 0; i < interfaceLines.size(); i++) {
                String l = interfaceLines.get(i).trim();

                // ðŸŽ¯ à¹€à¸£à¸´à¹ˆà¸¡à¸žà¸­à¸£à¹Œà¸•à¹ƒà¸«à¸¡à¹ˆ (à¹€à¸‰à¸žà¸²à¸°à¸—à¸µà¹ˆà¸¡à¸µ gei à¸­à¸¢à¸¹à¹ˆà¹ƒà¸™à¸Šà¸·à¹ˆà¸­)
                if (l.matches("^[A-Za-z0-9/\\-]+\\s+is\\s+.*,\\s*ifindex:.*") && l.toLowerCase().contains("gei")) {
                    // ðŸ”¹ à¹€à¸à¹‡à¸šà¸žà¸­à¸£à¹Œà¸•à¹€à¸à¹ˆà¸²à¸à¹ˆà¸­à¸™à¸«à¸™à¹‰à¸² (à¸–à¹‰à¸²à¸¡à¸µ)
                    if (!currentPort_if.isEmpty() && currentPort_if.toLowerCase().contains("gei")) {
                        Map<String, String> rec = new LinkedHashMap<>();
                        rec.put("Port", currentPort_if.trim().toLowerCase().replaceAll("\\s+", ""));

                        rec.put("Description", desc_if);
                        rec.put("Oper", oper_if);
                        rec.put("CRC", crc_if);
                        rec.put("InPeak(G)", String.format("%.6f", inPeakGbps_if));
                        rec.put("OutPeak(G)", String.format("%.6f", outPeakGbps_if));
                        rec.put("InTime(TH)", inTime_if);
                        rec.put("OutTime(TH)", outTime_if);
                        intfRecords.add(rec);
                    }

                    // âœ… à¹€à¸£à¸´à¹ˆà¸¡à¸­à¹ˆà¸²à¸™à¸žà¸­à¸£à¹Œà¸•à¹ƒà¸«à¸¡à¹ˆ (à¹€à¸‰à¸žà¸²à¸°à¸—à¸µà¹ˆà¸¡à¸µ gei)
                    currentPort_if = l.split("\\s+")[0].trim().toLowerCase().replaceAll("\\s+", "");

                    oper_if = l.replaceAll(".*is\\s+([^,]+),.*", "$1").trim();
                    desc_if = crc_if = "";
                    inPeakGbps_if = outPeakGbps_if = 0;
                    inTime_if = outTime_if = "";
                    continue;
                }

                // âœ… à¸‚à¹‰à¸²à¸¡à¸–à¹‰à¸²à¹„à¸¡à¹ˆà¸­à¸¢à¸¹à¹ˆà¹ƒà¸™ port à¸—à¸µà¹ˆà¸¡à¸µ gei
                if (currentPort_if.isEmpty() || !currentPort_if.toLowerCase().contains("gei")) {
                    continue;
                }

                // ðŸ”¹ Description
                if (l.startsWith("Description:")) {
                    desc_if = l.substring("Description:".length()).trim();

                    // âœ… à¸–à¹‰à¸²à¸¡à¸µ " à¹€à¸›à¸´à¸”à¹à¸•à¹ˆà¹„à¸¡à¹ˆà¸¡à¸µà¸›à¸´à¸” à¹ƒà¸«à¹‰à¸•à¹ˆà¸­à¸šà¸£à¸£à¸—à¸±à¸”à¸•à¹ˆà¸­à¹„à¸›
                    if (desc_if.startsWith("\"") && !desc_if.endsWith("\"")) {
                        StringBuilder fullDesc = new StringBuilder(desc_if);
                        int j = i + 1;
                        while (j < interfaceLines.size()) {
                            String nextLine = interfaceLines.get(j).trim();
                            fullDesc.append(" ").append(nextLine);
                            if (nextLine.endsWith("\"")) {
                                i = j; // à¸‚à¹‰à¸²à¸¡à¸šà¸£à¸£à¸—à¸±à¸”à¸—à¸µà¹ˆà¹ƒà¸Šà¹‰à¹à¸¥à¹‰à¸§
                                break;
                            }
                            // âœ… à¸–à¹‰à¸²à¸žà¸šà¸šà¸£à¸£à¸—à¸±à¸”à¸­à¸·à¹ˆà¸™à¸—à¸µà¹ˆà¹„à¸¡à¹ˆà¹ƒà¸Šà¹ˆ description à¹à¸¥à¹‰à¸§ â€” à¸«à¸¢à¸¸à¸”à¸—à¸±à¸™à¸—à¸µ
                            if (nextLine.matches("^(Line protocol|detected status|Last line|Input|Output|CRC|Speed|Port|IPv[46]).*")) {
                                break;
                            }
                            j++;
                        }
                        desc_if = fullDesc.toString().trim();
                    }

                    // âœ… à¸•à¸±à¸” quote à¸‹à¹‰à¸²à¸¢à¸‚à¸§à¸²à¸­à¸­à¸
                    desc_if = desc_if.replaceAll("^\"|\"$", "").trim();

                    // âœ… à¸¥à¹‰à¸²à¸‡à¹€à¸¨à¸©à¸—à¸µà¹ˆà¸•à¸²à¸¡à¸¡à¸²à¸œà¸´à¸” à¹€à¸Šà¹ˆà¸™ "Line protocol is up..." (à¸à¸±à¸™à¹„à¸§à¹‰à¹€à¸œà¸·à¹ˆà¸­)
                    desc_if = desc_if.replaceAll("\\s+(Line protocol|detected status|Last line).*", "").trim();
                }

                // ðŸ”¹ CRC Error
                if (l.contains("In_CRC_ERROR")) {
                    Matcher m = Pattern.compile("In_CRC_ERROR\\s+([0-9]+)").matcher(l);
                    if (m.find()) {
                        crc_if = m.group(1);
                    }
                }

                // ðŸ”¹ Input Peak Rate
                if (l.contains("Input") && l.contains("peak time")) {
                    Matcher m = Pattern.compile("Input\\s*:\\s*([0-9]+)\\s*bit/s.*?peak time\\s*([0-9\\-: ]+)").matcher(l);
                    if (m.find()) {
                        inPeakGbps_if = Double.parseDouble(m.group(1)) / 1_000_000_000.0;
                        inTime_if = convertToThaiTime(m.group(2));
                    }
                }

                // ðŸ”¹ Output Peak Rate
                if (l.contains("Output") && l.contains("peak time")) {
                    Matcher m = Pattern.compile("Output\\s*:\\s*([0-9]+)\\s*bit/s.*?peak time\\s*([0-9\\-: ]+)").matcher(l);
                    if (m.find()) {
                        outPeakGbps_if = Double.parseDouble(m.group(1)) / 1_000_000_000.0;
                        outTime_if = convertToThaiTime(m.group(2));
                    }
                }
            }

// âœ… à¹€à¸à¹‡à¸šà¸žà¸­à¸£à¹Œà¸•à¸ªà¸¸à¸”à¸—à¹‰à¸²à¸¢ (à¹€à¸‰à¸žà¸²à¸° gei)
            if (!currentPort_if.isEmpty() && currentPort_if.toLowerCase().contains("gei")) {
                Map<String, String> rec = new LinkedHashMap<>();
                rec.put("Port", currentPort_if);
                rec.put("Description", desc_if);
                rec.put("Oper", oper_if);
                rec.put("CRC", crc_if);
                rec.put("InPeak(G)", String.format("%.6f", inPeakGbps_if));
                rec.put("OutPeak(G)", String.format("%.6f", outPeakGbps_if));
                rec.put("InTime(TH)", inTime_if);
                rec.put("OutTime(TH)", outTime_if);
                intfRecords.add(rec);
            }

// âœ… à¹à¸ªà¸”à¸‡à¸œà¸¥à¹€à¸‰à¸žà¸²à¸°à¸žà¸­à¸£à¹Œà¸•à¸—à¸µà¹ˆà¸Šà¸·à¹ˆà¸­à¸¡à¸µ gei
            //     System.out.println("===== PARSED ZTE INTERFACE (PORT NAME CONTAINS 'gei') =====");
            /*for (Map<String, String> r : intfRecords) {
    System.out.printf("%-15s | %-45s | Oper=%-12s | CRC=%-6s | In=%.6fG @ %s | Out=%.6fG @ %s%n",
            r.get("Port"), r.get("Description"), r.get("Oper"), r.get("CRC"),
            Double.parseDouble(r.get("InPeak(G)")), r.get("InTime(TH)"),
            Double.parseDouble(r.get("OutPeak(G)")), r.get("OutTime(TH)"));
}*/
            //       System.out.println("===== TOTAL PORTS (CONTAINING 'gei'): " + intfRecords.size() + " =====");
// =====================================================
// âœ… à¸£à¸§à¸¡à¸‚à¹‰à¸­à¸¡à¸¹à¸¥ ZTE: Optical + Interface + LLDP (à¹à¸ªà¸”à¸‡à¸—à¸¸à¸à¸žà¸­à¸£à¹Œà¸• gei à¸—à¸±à¹‰à¸‡à¸«à¸¡à¸”)
// =====================================================
            //System.out.println("ðŸ”„ Mapping all ZTE ports containing 'gei'...");
// ðŸ”¹ à¸£à¸§à¸¡à¸Šà¸·à¹ˆà¸­à¸žà¸­à¸£à¹Œà¸•à¸—à¸±à¹‰à¸‡à¸«à¸¡à¸”à¸—à¸µà¹ˆà¸¡à¸µ 'gei' à¸ˆà¸²à¸à¸—à¸±à¹‰à¸‡ 3 à¹à¸«à¸¥à¹ˆà¸‡ (Optical / Interface / LLDP)
            Set<String> allGeiPorts = new LinkedHashSet<>();

            for (Map<String, String> o : opticalRecords) {
                if (o.get("Port") != null && o.get("Port").toLowerCase().contains("gei")) {
                    allGeiPorts.add(o.get("Port"));
                }
            }
            for (Map<String, String> iRec : intfRecords) {
                if (iRec.get("Port") != null && iRec.get("Port").toLowerCase().contains("gei")) {
                    allGeiPorts.add(iRec.get("Port"));
                }
            }
            for (String p : lldpMap.keySet()) {
                if (p != null && p.toLowerCase().contains("gei")) {
                    allGeiPorts.add(p);
                }
            }

            //          System.out.println("âœ… Total GEI ports found: " + allGeiPorts.size());
// =====================================================
// âœ… à¸£à¸§à¸¡à¸„à¹ˆà¸²à¸ˆà¸²à¸à¸—à¸¸à¸à¸ªà¹ˆà¸§à¸™ (à¹ƒà¸ªà¹ˆà¸„à¹ˆà¸²à¸§à¹ˆà¸²à¸‡à¸–à¹‰à¸²à¹„à¸¡à¹ˆà¸¡à¸µà¸‚à¹‰à¸­à¸¡à¸¹à¸¥)
// =====================================================
            for (String port : allGeiPorts) {

                // ====== Optical ======
                Map<String, String> opticRec = null;
                for (Map<String, String> o : opticalRecords) {
                    String optPort = o.get("Port");
                    if (optPort == null || port == null) {
                        continue;
                    }

                    // Normalize à¹€à¸žà¸·à¹ˆà¸­à¹ƒà¸«à¹‰à¹€à¸—à¸µà¸¢à¸šà¹à¸šà¸šà¹„à¸¡à¹ˆà¸žà¸¥à¸²à¸”
                    String normOptPort = normalizePort(optPort);
                    String normPort = normalizePort(port);

// âœ… à¹€à¸—à¸µà¸¢à¸šà¹ƒà¸«à¹‰à¹à¸¡à¹ˆà¸™à¸—à¸±à¹‰à¸‡à¹à¸šà¸šà¹€à¸•à¹‡à¸¡à¹à¸¥à¸° prefix (à¹„à¸¡à¹ˆà¸¡à¸µà¸¥à¸¹à¸à¹à¸¡à¹ˆ)
                    if (normOptPort.equals(normPort)
                            || normOptPort.startsWith(normPort + "/")
                            || normPort.startsWith(normOptPort + "/")
                            || normOptPort.replaceAll("/[0-9]+$", "").equals(normPort)
                            || normPort.replaceAll("/[0-9]+$", "").equals(normOptPort)) {
                        opticRec = o;
                        break;
                    }

                }

                // ====== Interface ======
                Map<String, String> intfRec = null;
                for (Map<String, String> iRec : intfRecords) {
                    if (iRec.get("Port") != null
                            && iRec.get("Port").equalsIgnoreCase(port)) {
                        intfRec = iRec;
                        break;
                    }
                }

                // ====== LLDP ======
                String neighName = "";
                String neighIf = "";
                if (lldpMap.containsKey(port)) {
                    neighName = lldpMap.get(port)[0];
                    neighIf = lldpMap.get(port)[1];
                }

                // ====== Optical Fields ======
                String bwOptic = opticRec != null ? opticRec.getOrDefault("MaxBW", "") : "";
                if (bwOptic.contains("-")) {
                    bwOptic = bwOptic.split("-")[0];
                }
                bwOptic = bwOptic.replaceAll("[^0-9G]", "").trim();

                String wl = opticRec != null ? opticRec.getOrDefault("Wavelength", "") : "";
                String distanceOpt = opticRec != null ? opticRec.getOrDefault("Distance", "") : "";
                String rx = opticRec != null ? opticRec.getOrDefault("RxPower", "") : "";
                String tx = opticRec != null ? opticRec.getOrDefault("TxPower", "") : "";
                String warnOptic = opticRec != null ? opticRec.getOrDefault("WarningRange", "") : "";
                String statusOptic = opticRec != null ? opticRec.getOrDefault("Status", "") : "";

                // ====== Interface Fields ======
                String desc = intfRec != null ? intfRec.getOrDefault("Description", "") : "";
                String oper = intfRec != null ? intfRec.getOrDefault("Oper", "") : "";
                String crc = intfRec != null ? intfRec.getOrDefault("CRC", "") : "";
                String inPeak = intfRec != null ? intfRec.getOrDefault("InPeak(G)", "0") : "0";
                String outPeak = intfRec != null ? intfRec.getOrDefault("OutPeak(G)", "0") : "0";
                String inTime = intfRec != null ? intfRec.getOrDefault("InTime(TH)", "") : "";
                String outTime = intfRec != null ? intfRec.getOrDefault("OutTime(TH)", "") : "";

                // ====== à¹€à¸‚à¸µà¸¢à¸™à¸£à¸§à¸¡à¸—à¸±à¹‰à¸‡à¸«à¸¡à¸” ======
                Str_LLDP_all += csvSafe(siteCode) + "," + csvSafe(ipLoopback) + "," + csvSafe(port) + ","
                        + csvSafe(oper) + "," + csvSafe(desc) + "," + csvSafe(neighName) + ","
                        + csvSafe(formatNeighborPort(neighIf)) + "," + csvSafe(bwOptic) + ","
                        + csvSafe(inPeak) + "," + csvSafe(inTime) + ","
                        + csvSafe(outPeak) + "," + csvSafe(outTime) + ","
                        + csvSafe(wl) + "," + csvSafe(distanceOpt) + ","
                        + csvSafe(tx) + "," + csvSafe(rx) + "," + csvSafe(warnOptic) + ","
                        + csvSafe(crc) + "," + csvSafe(version) + "," + csvSafe(equipment) + "\n";
            }

        } // âœ… NOKIA placeholder (à¸¢à¸±à¸‡à¹„à¸¡à¹ˆà¸•à¹‰à¸­à¸‡à¹€à¸‚à¸µà¸¢à¸™ logic)
        else if (path.contains("_N-LLDP-Link_OPTIC_")) {

            String siteCode = "", ipLoopback = "", version = "", equipment = "";
            String currentPort = "", currentState = "", desc = "";
            String bw = "", wavelength = "", rxPower = "", txPower = "", warn = "", crc = "0";

            // ðŸ”¹ Extract IP from filename
            try {
                String fileName = new File(path).getName();
                String part = fileName.substring(fileName.indexOf("]") + 1);
                ipLoopback = part.split("_")[0].trim();
            } catch (Exception e) {
                System.out.println("âš ï¸ Cannot parse IP from filename: " + path);
            }

            // Nokia prompts may be "#environment" or "# environment".
            Pattern nokiaPromptSitePattern = Pattern.compile("^[AB]\\s*:\\s*(.*?)#\\s*environment\\b", Pattern.CASE_INSENSITIVE);
            Pattern nokiaNameSitePattern = Pattern.compile("^\\s*Name\\s*:\\s*(\\S.*)$", Pattern.CASE_INSENSITIVE);
            for (String l : lines) {
                Matcher m = nokiaPromptSitePattern.matcher(l);
                if (m.find()) {
                    siteCode = m.group(1).trim();
                    break;
                }
            }
            if (siteCode.isEmpty()) {
                for (String l : lines) {
                    Matcher m = nokiaNameSitePattern.matcher(l);
                    if (m.find()) {
                        siteCode = m.group(1).trim();
                        break;
                    }
                }
            }
            if (siteCode.isEmpty()) {
                try {
                    String fileName = new File(path).getName();
                    String part = fileName.substring(fileName.indexOf("]") + 1);
                    String[] fileParts = part.split("_");
                    if (fileParts.length > 1) {
                        siteCode = fileParts[1].trim();
                    }
                } catch (Exception e) {
                    // Keep blank if the filename does not follow the expected Nokia pattern.
                }
            }

// âœ… à¸«à¸² Version + Equipment
            for (String l : lines) {
                // âœ… à¸•à¸£à¸§à¸ˆà¸šà¸£à¸£à¸—à¸±à¸”à¸—à¸µà¹ˆà¸•à¸£à¸‡à¸à¸±à¸š "Type                              :"
                if (l.contains("  Type                              :")) {
                    equipment = l.split(": ")[1];
                }
                // âœ… à¸«à¸² TiMOS version
                if (version.isEmpty() && l.contains("TiMOS")) {
                    Matcher mVer = Pattern.compile("(TiMOS-[A-Z]-[0-9\\.R]+)", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (mVer.find()) {
                        version = mVer.group(1).trim(); // à¹€à¸Šà¹ˆà¸™ TiMOS-C-19.10.R12
                    }
                }
            }
// âœ… à¹€à¸à¹‡à¸šà¸‚à¹‰à¸­à¸¡à¸¹à¸¥ LLDP Remote Peer à¹à¸¢à¸à¹„à¸§à¹‰à¸à¹ˆà¸­à¸™
            List<Map<String, String>> lldpRecords = new ArrayList<>();

            boolean inPeerBlock = false;
            String peerPort = "";
            String remotePeerIndex = "";
            String timestamp = "";
            String chassisId = "";
            String portId = "";
            String portDesc = "";
            String sysName = "";
            String sysDesc = "";
            StringBuilder sysDescBlock = new StringBuilder();

            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();

                // ðŸŽ¯ à¹€à¸£à¸´à¹ˆà¸¡à¸šà¸¥à¹‡à¸­à¸à¹ƒà¸«à¸¡à¹ˆà¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­ "Port x/x/x Bridge nearest-bridge Remote Peer Information"
                if (l.matches("(?i).*ethernet\\s+lldp\\s+remote-info.*")) {

                    //               System.out.println(l);
                    // ðŸ”¹ à¸–à¹‰à¸²à¸¡à¸µà¸šà¸¥à¹‡à¸­à¸à¸à¹ˆà¸­à¸™à¸«à¸™à¹‰à¸²à¹ƒà¸«à¹‰à¹€à¸à¹‡à¸šà¹„à¸§à¹‰à¸à¹ˆà¸­à¸™
                    if (inPeerBlock && !peerPort.isEmpty()) {
                        Map<String, String> rec = new LinkedHashMap<>();
                        rec.put("SiteCode", siteCode);
                        rec.put("IP", ipLoopback);
                        rec.put("Port", peerPort);
                        rec.put("RemotePeerIndex", remotePeerIndex);
                        rec.put("Timestamp", timestamp);
                        rec.put("ChassisId", chassisId);
                        rec.put("PortId", portId);
                        rec.put("PortDescription", portDesc);
                        rec.put("SystemName", sysName);
                        rec.put("SystemDescription", sysDescBlock.toString());
                        rec.put("Version", version);
                        rec.put("Equipment", equipment);
                        lldpRecords.add(rec);
                    }

                    // ðŸ”„ à¹€à¸£à¸´à¹ˆà¸¡à¸šà¸¥à¹‡à¸­à¸à¹ƒà¸«à¸¡à¹ˆ
                    inPeerBlock = true;
                    Matcher portMatcher = Pattern.compile("\\b[0-9]+/[0-9]+/[A-Za-z0-9/]+\\b").matcher(l);
                    if (portMatcher.find()) {
                        peerPort = portMatcher.group().trim();
                    }

                    remotePeerIndex = timestamp = chassisId = portId = portDesc = sysName = sysDesc = "";
                    sysDescBlock.setLength(0);
                    continue;
                }

                if (!inPeerBlock) {
                    continue;
                }

                // ðŸ§© à¸”à¸¶à¸‡à¸‚à¹‰à¸­à¸¡à¸¹à¸¥à¸«à¸¥à¸±à¸à¸ˆà¸²à¸à¹à¸•à¹ˆà¸¥à¸°à¸šà¸£à¸£à¸—à¸±à¸”
                if (l.startsWith("Remote Peer Index")) {
                    Matcher m = Pattern.compile("Remote Peer Index\\s+(\\S+)\\s+at timestamp\\s+(.+?):").matcher(l);
                    if (m.find()) {
                        remotePeerIndex = m.group(1).trim();
                        timestamp = m.group(2).trim();
                    }
                } else if (l.startsWith("Chassis Id")) {
                    chassisId = l.split("Chassis Id")[1].trim();
                } else if (l.startsWith("Port Id")) {
                    if (i + 1 < lines.size() && lines.get(i + 1).trim().startsWith("\"")) {
                        String nextLine = lines.get(i + 1).trim();
                        portId = nextLine.replace("\"", "").trim();
                    }
                } else if (l.startsWith("Port Description")) {
                    StringBuilder descBlock = new StringBuilder(l);
                    int j = i + 1;
                    while (j < lines.size()) {
                        String next = lines.get(j).trim();
                        if (next.isEmpty() || next.startsWith("System Name") || next.startsWith("System Description")) {
                            break;
                        }
                        descBlock.append(" ").append(next);
                        j++;
                    }
                    String descText = descBlock.toString();
                    if (descText.contains(":")) {
                        descText = descText.substring(descText.indexOf(":") + 1);
                    }
                    portDesc = descText.replace("\"", "").replaceAll("\\s+", " ").trim();
                } else if (l.startsWith("System Name")) {
                    sysName = l.split(":", 2)[1].trim();
                } else if (l.startsWith("System Description")) {
                    sysDescBlock.setLength(0);
                    sysDescBlock.append(l.split(":", 2)[1].trim());
                    int j = i + 1;
                    while (j < lines.size()) {
                        String next = lines.get(j).trim();
                        if (next.isEmpty() || next.startsWith("Age") || next.startsWith("Remote Peer Index")) {
                            break;
                        }
                        sysDescBlock.append(" ").append(next);
                        j++;
                    }
                    sysDesc = sysDescBlock.toString().replaceAll("\\s+", " ").trim();
                }
            }

// âœ… à¹€à¸à¹‡à¸šà¸šà¸¥à¹‡à¸­à¸à¸ªà¸¸à¸”à¸—à¹‰à¸²à¸¢
            if (inPeerBlock && !peerPort.isEmpty()) {
                Map<String, String> rec = new LinkedHashMap<>();
                rec.put("SiteCode", siteCode);
                rec.put("IP", ipLoopback);
                rec.put("Port", peerPort);
                rec.put("RemotePeerIndex", remotePeerIndex);
                rec.put("Timestamp", timestamp);
                rec.put("ChassisId", chassisId);
                rec.put("PortId", portId);
                rec.put("PortDescription", portDesc);
                rec.put("SystemName", sysName);
                rec.put("SystemDescription", sysDescBlock.toString());
                rec.put("Version", version);
                rec.put("Equipment", equipment);
                lldpRecords.add(rec);
            }

            //       System.out.println("âœ… Parsed Nokia LLDP Remote Info: " + siteCode + " (" + ipLoopback + "), Records = " + lldpRecords.size());
// âœ… à¹€à¸à¹‡à¸š Optical Data à¹à¸¢à¸à¹„à¸§à¹‰à¸à¹ˆà¸­à¸™ (à¸¢à¸±à¸‡à¹„à¸¡à¹ˆà¸£à¸§à¸¡à¸à¸±à¸š LLDP)
// âœ… à¹€à¸à¹‡à¸š Optical Data à¹à¸¢à¸à¹„à¸§à¹‰à¸à¹ˆà¸­à¸™ (à¸¢à¸±à¸‡à¹„à¸¡à¹ˆà¸£à¸§à¸¡à¸à¸±à¸š LLDP)
            List<Map<String, String>> opticalRecords = new ArrayList<>();

            boolean inShowPort = false;
            String portName = "";
            String description = "";
            String operSpeed = "";
            String adminState = "";
            String operState = "";
            String opticWavelength = "";
            String opticalCompliance = "";
            String linkLength = "";
            String opticRxPower = "";
            String opticTxPower = "";
            String serialNo = "";
            String partNo = "";
            String modelNo = "";
            String rxLowWarn = "";
            String fcsErrors = "";

            List<Float> txVals = new ArrayList<>();
            List<Float> rxVals = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();

                // âœ… à¸›à¸´à¸” block à¹€à¸”à¸´à¸¡à¸–à¹‰à¸²à¹€à¸ˆà¸­ show port à¹ƒà¸«à¸¡à¹ˆà¸«à¸£à¸·à¸­ logout
                if ((l.toLowerCase().contains("show port") || l.toLowerCase().contains("logout")) && inShowPort) {
                    if (!portName.isEmpty()) {
                        Map<String, String> rec = new LinkedHashMap<>();
                        rec.put("SiteCode", siteCode);
                        rec.put("IP", ipLoopback);
                        rec.put("Port", portName);
                        rec.put("Description", description);
                        rec.put("AdminState", adminState);
                        rec.put("OperState", operState);
                        rec.put("Speed", operSpeed);
                        rec.put("Wavelength", opticWavelength);
                        rec.put("OpticalCompliance", opticalCompliance);
                        rec.put("Length", linkLength);
                        rec.put("RxPower", opticRxPower);
                        rec.put("RxLowWarm", rxLowWarn);
                        rec.put("TxPower", opticTxPower);
                        rec.put("SerialNo", serialNo);
                        rec.put("PartNo", partNo);
                        rec.put("ModelNo", modelNo);
                        rec.put("Version", version);
                        rec.put("Equipment", equipment);
                        rec.put("FcsErrors", fcsErrors);
                        opticalRecords.add(rec);
                    }

                    // ðŸ§¹ Reset à¸•à¸±à¸§à¹à¸›à¸£à¸—à¸±à¹‰à¸‡à¸«à¸¡à¸”
                    inShowPort = false;
                    portName = description = operSpeed = adminState = operState = opticWavelength
                            = opticalCompliance = linkLength = opticRxPower = opticTxPower
                            = serialNo = partNo = modelNo = "";
                }

                // ðŸŽ¯ à¹€à¸£à¸´à¹ˆà¸¡ block à¹ƒà¸«à¸¡à¹ˆà¹€à¸¡à¸·à¹ˆà¸­à¹€à¸ˆà¸­ "Description :"
                // Nokia à¸šà¸²à¸‡à¸žà¸­à¸£à¹Œà¸•à¸•à¸±à¸” description à¸¥à¸‡à¸«à¸¥à¸²à¸¢à¸šà¸£à¸£à¸—à¸±à¸”à¸à¹ˆà¸­à¸™à¸–à¸¶à¸‡ "Interface"
                if (!inShowPort && l.startsWith("Description")) {
                    StringBuilder descBlock = new StringBuilder();
                    if (l.contains(":")) {
                        descBlock.append(l.split(":", 2)[1].trim());
                    }

                    int j = i + 1;
                    while (j < lines.size()) {
                        String next = lines.get(j).trim();

                        if (next.startsWith("Interface")) {
                            inShowPort = true;
                            description = descBlock.toString()
                                    .replace("\"", "")
                                    .replaceAll("\\s+", " ")
                                    .trim();
                            i = j - 1; // à¹ƒà¸«à¹‰à¸£à¸­à¸šà¸–à¸±à¸”à¹„à¸› parse à¸šà¸£à¸£à¸—à¸±à¸” Interface à¸•à¸²à¸¡à¸›à¸à¸•à¸´
                            break;
                        }

                        if (next.isEmpty()
                                || next.startsWith("===")
                                || next.startsWith("B:")
                                || next.contains("# show port")
                                || next.toLowerCase().contains("logout")) {
                            break;
                        }

                        if (!next.isEmpty()) {
                            if (descBlock.length() > 0) {
                                descBlock.append(" ");
                            }
                            descBlock.append(next);
                        }
                        j++;
                    }

                    if (inShowPort) {
                        continue;
                    }
                }

                if (!inShowPort) {
                    continue;
                }

                // âœ… Parsing à¸£à¸²à¸¢à¸¥à¸°à¹€à¸­à¸µà¸¢à¸”à¹ƒà¸™ block à¸›à¸±à¸ˆà¸ˆà¸¸à¸šà¸±à¸™
                if (l.startsWith("Interface")) {
                    Matcher m = Pattern.compile("Interface\\s*:\\s*([^\\s]+)").matcher(l);
                    if (m.find()) {
                        portName = m.group(1).trim();
                    }
                    Matcher s = Pattern.compile("Oper Speed\\s*:\\s*([^\\s]+\\s*Gbps)").matcher(l);
                    if (s.find()) {
                        operSpeed = s.group(1).trim();
                    }
                } else if (l.startsWith("Admin State")) {
                    Matcher m = Pattern.compile("Admin State\\s*:\\s*(\\S+)").matcher(l);
                    if (m.find()) {
                        adminState = m.group(1).trim();
                    }
                } else if (l.startsWith("Oper State")) {
                    Matcher m = Pattern.compile("Oper State\\s*:\\s*([A-Za-z]+)").matcher(l);
                    if (m.find()) {
                        operState = m.group(1).trim();
                    }
                } else if (l.startsWith("TX Laser Wavelength")) {
                    Matcher m = Pattern.compile("TX Laser Wavelength\\s*:\\s*([0-9]+\\s*nm)", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find()) {
                        opticWavelength = m.group(1).trim();
                    }
                } else if (l.startsWith("Optical Compliance")) {
                    opticalCompliance = l.split(":", 2)[1].trim();
                } else if (l.startsWith("Link Length support")) {
                    String val = l.split(":", 2)[1].trim();
                    // âœ… à¸”à¸¶à¸‡à¹€à¸‰à¸žà¸²à¸°à¸ªà¹ˆà¸§à¸™à¸—à¸µà¹ˆà¸¡à¸µà¸•à¸±à¸§à¹€à¸¥à¸‚à¸•à¸²à¸¡à¸”à¹‰à¸§à¸¢ km
                    Matcher m = Pattern.compile("([0-9]+\\s*km)", Pattern.CASE_INSENSITIVE).matcher(val);
                    if (m.find()) {
                        linkLength = m.group(1).replaceAll("\\s+", "");  // à¹€à¸Šà¹ˆà¸™ "40km"
                    } else {
                        linkLength = "";
                    }
                } else if (l.startsWith("Rx Optical Power")) {
                    // à¸”à¸¶à¸‡à¸„à¹ˆà¸²à¸—à¸±à¹‰à¸‡ 5 à¸Šà¹ˆà¸­à¸‡: Value, HighAlarm, HighWarn, LowWarn, LowAlarm
                    Matcher m = Pattern.compile(
                            "(?i)Rx Optical Power.*?\\)\\s*([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)"
                    ).matcher(l);

                    if (m.find()) {
                        opticRxPower = m.group(1).trim();  // âœ… à¸„à¹ˆà¸²à¸ˆà¸£à¸´à¸‡ à¹€à¸Šà¹ˆà¸™ -4.52
                        rxLowWarn = m.group(4).trim();  // âœ… Low Warn à¹€à¸Šà¹ˆà¸™ -18.01
                    }
                } else if (l.startsWith("Tx Output Power")) {
                    Matcher m = Pattern.compile("(?i)Tx Output Power.*?\\)\\s*([-0-9\\.]+)").matcher(l);
                    if (m.find()) {
                        opticTxPower = m.group(1).trim();
                    }
                } else if (l.toLowerCase().startsWith("lane rx optical")) {

                    // ðŸ”¹ à¹€à¸à¹‡à¸šà¸„à¹ˆà¸² LowWarn à¸ˆà¸²à¸à¸šà¸£à¸£à¸—à¸±à¸” Lane Rx Optical Pwr (avg dBm)
                    Matcher m = Pattern.compile(
                            "(?i)Lane Rx Optical Pwr.*?([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)"
                    ).matcher(l);
                    if (m.find()) {
                        rxLowWarn = m.group(3).trim();  // âœ… -11.60
                    }

                } else if (l.matches("^\\s*\\d+\\s+[+\\-0-9\\.]+\\s+[+\\-0-9\\.]+\\s+[+\\-0-9\\.]+\\s+[+\\-0-9\\.]+\\s*$")) {

                    Matcher lane = Pattern.compile(
                            "^\\s*(\\d+)\\s+[+\\-0-9\\.]+\\s+[+\\-0-9\\.]+\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)"
                    ).matcher(l);

                    if (lane.find()) {
                        try {
                            float tx = Float.parseFloat(lane.group(2));
                            float rx = Float.parseFloat(lane.group(3));
                            txVals.add(tx);
                            rxVals.add(rx);
                            //   System.out.printf("ðŸ“¶ Lane %s â†’ Tx=%.2f Rx=%.2f%n", lane.group(1), tx, rx);

                            if (txVals.size() == 4 && rxVals.size() == 4) {
                                float sumTx = 0, sumRx = 0;
                                for (int iVal = 0; iVal < 4; iVal++) {
                                    sumTx += txVals.get(iVal);
                                    sumRx += rxVals.get(iVal);
                                }
                                float avgTx = sumTx / 4;
                                float avgRx = sumRx / 4;

                                opticTxPower = String.format("%.2f", avgTx);
                                opticRxPower = String.format("%.2f", avgRx);

                                //  System.out.println("âœ… Avg Tx=" + opticTxPower + " dBm, Avg Rx=" + opticRxPower + " dBm (" + portName + ")");
                                txVals.clear();
                                rxVals.clear();
                            }

                        } catch (Exception e) {
                            System.err.println("âŒ Parse error line: " + l);
                        }
                    }
                } else if (l.startsWith("Serial Number")) {
                    serialNo = l.split(":", 2)[1].trim();
                } else if (l.startsWith("Part Number")) {
                    partNo = l.split(":", 2)[1].trim();
                } else if (l.startsWith("Model Number")) {
                    modelNo = l.split(":", 2)[1].trim();
                } else if (l.contains("FCS Errors")) {
                    Matcher m = Pattern.compile("FCS Errors\\s*:\\s*([0-9]+)", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find()) {
                        fcsErrors = m.group(1).trim();  // âœ… à¹„à¸”à¹‰à¸„à¹ˆà¸² "857"
                    }
                }

            }

// âœ… à¹€à¸žà¸´à¹ˆà¸¡à¸šà¸¥à¹‡à¸­à¸à¸ªà¸¸à¸”à¸—à¹‰à¸²à¸¢ (à¸à¸£à¸“à¸µà¹„à¸Ÿà¸¥à¹Œà¸ˆà¸šà¹‚à¸”à¸¢à¹„à¸¡à¹ˆà¸¡à¸µ logout)
            if (inShowPort && !portName.isEmpty()) {
                Map<String, String> rec = new LinkedHashMap<>();
                rec.put("SiteCode", siteCode);
                rec.put("IP", ipLoopback);
                rec.put("Port", portName);
                rec.put("Description", description);
                rec.put("AdminState", adminState);
                rec.put("OperState", operState);
                rec.put("Speed", operSpeed);
                rec.put("Wavelength", opticWavelength);
                rec.put("OpticalCompliance", opticalCompliance);
                rec.put("Length", linkLength);
                rec.put("RxPower", opticRxPower);
                rec.put("RxLowWarm", rxLowWarn);
                rec.put("TxPower", opticTxPower);
                rec.put("SerialNo", serialNo);
                rec.put("PartNo", partNo);
                rec.put("ModelNo", modelNo);
                rec.put("Version", version);
                rec.put("Equipment", equipment);
                rec.put("FcsErrors", fcsErrors);
                opticalRecords.add(rec);
            }

            //       System.out.println("âœ… Parsed Nokia Optical: " + siteCode + " (" + ipLoopback + "), Records = " + opticalRecords.size());
            //   for (Map<String, String> rec : opticalRecords) {
            //       System.out.println(rec);
            //    }
            //     for (int i = 0; i < opticalRecords.size(); i++) {
            //           System.out.println(opticalRecords.get(i));
            //  }
            // =====================================================
// âœ… à¸£à¸§à¸¡à¸‚à¹‰à¸­à¸¡à¸¹à¸¥ Optical à¸à¸±à¸š LLDP (Map)
// =====================================================
            //System.out.println("ðŸ”„ Merging Nokia LLDP + Optical Records...");
            for (Map<String, String> lldp : lldpRecords) {
                String lp = lldp.getOrDefault("Port", "");
                String site = lldp.getOrDefault("SiteCode", "");
                String ip = lldp.getOrDefault("IP", "");
                String neighName = lldp.getOrDefault("SystemName", "");
                String neighIf = lldp.getOrDefault("PortId", "");
                String neighPortDesc = lldp.getOrDefault("PortDescription", "");
                String ver2 = lldp.getOrDefault("Version", "");
                String eq2 = lldp.getOrDefault("Equipment", "");

                // âœ… à¸«à¸²à¸„à¸¹à¹ˆà¹ƒà¸™ Optical à¸•à¸²à¸¡ Port
                Map<String, String> opticRec = null;
                for (Map<String, String> o : opticalRecords) {
                    String portVal = o.get("Port");
                    if (portVal != null && portVal.equalsIgnoreCase(lp)) {
                        opticRec = o;
                        break;
                    }
                }

                // âœ… à¸à¸£à¸“à¸µ port à¸žà¸´à¹€à¸¨à¸© (2/1/c6/1) à¹ƒà¸«à¹‰à¹ƒà¸Šà¹‰à¸‚à¹‰à¸­à¸¡à¸¹à¸¥à¸ˆà¸²à¸ base port (2/1/c6)
                String specialBasePort = "";
                if (opticRec == null && lp.matches(".+/[0-9]+$")) {
                    specialBasePort = lp.replaceAll("/[0-9]+$", "");
                    for (Map<String, String> o : opticalRecords) {
                        String portVal = o.get("Port");
                        if (portVal != null && portVal.equalsIgnoreCase(specialBasePort)) {
                            opticRec = o;
                            break;
                        }
                    }
                    if (opticRec != null) {
                        System.out.printf("ðŸ”— LLDP Port %-15s â†’ à¹ƒà¸Šà¹‰à¸‚à¹‰à¸­à¸¡à¸¹à¸¥à¸ˆà¸²à¸ Optical %-15s%n", lp, specialBasePort);
                    }
                }

                // âœ… à¸•à¸±à¸§à¹à¸›à¸£à¸œà¸¥à¸¥à¸±à¸žà¸˜à¹Œ
                String bw2 = "", wavelength2 = "", length2 = "", rx2 = "", tx2 = "", rxLow2 = "", fcs2 = "", state2 = "", desc2 = "";

                if (opticRec != null) {
                    bw2 = opticRec.getOrDefault("Speed", "");
                    wavelength2 = opticRec.getOrDefault("Wavelength", "");
                    length2 = opticRec.getOrDefault("Length", "");
                    rx2 = opticRec.getOrDefault("RxPower", "");
                    tx2 = opticRec.getOrDefault("TxPower", "");
                    rxLow2 = opticRec.getOrDefault("RxLowWarm", "");
                    fcs2 = opticRec.getOrDefault("FcsErrors", "");
                    desc2 = cleanNokiaShowPortDescription(opticRec.getOrDefault("Description", ""));
                    state2 = opticRec.getOrDefault("OperState", "");
                }

                // âœ… Description à¸‚à¸­à¸‡ Nokia à¹ƒà¸«à¹‰à¹ƒà¸Šà¹‰à¸ˆà¸²à¸ show port à¹€à¸—à¹ˆà¸²à¸™à¸±à¹‰à¸™
                // à¹„à¸¡à¹ˆ override à¸”à¹‰à¸§à¸¢ Port Description à¸ˆà¸²à¸ lldp remote-info
                if (desc2 != null) {
                    desc2 = desc2.trim();
                }
// âœ… à¸–à¹‰à¸²à¹€à¸›à¹‡à¸™à¸žà¸­à¸£à¹Œà¸•à¸¥à¸¹à¸ (à¹€à¸Šà¹ˆà¸™ 2/1/c1/1) à¹à¸•à¹ˆà¹„à¸¡à¹ˆà¸¡à¸µà¸‚à¹‰à¸­à¸¡à¸¹à¸¥ Wavelength â†’ à¹ƒà¸Šà¹‰à¸„à¹ˆà¸²à¸ˆà¸²à¸à¸žà¸­à¸£à¹Œà¸•à¹à¸¡à¹ˆ (2/1/c1)
                if ((wavelength2.isEmpty() || tx2.isEmpty() || rx2.isEmpty() || rxLow2.isEmpty() || length2.isEmpty())
                        && lp.matches(".+/[0-9]+$")) {

                    String parentPort = lp.replaceAll("/[0-9]+$", "");
                    for (Map<String, String> o : opticalRecords) {
                        String portVal = o.get("Port");
                        if (portVal != null && portVal.equalsIgnoreCase(parentPort)) {

                            // âœ… à¹ƒà¸Šà¹‰à¸‚à¹‰à¸­à¸¡à¸¹à¸¥à¸ˆà¸²à¸à¸žà¸­à¸£à¹Œà¸•à¹à¸¡à¹ˆ à¸–à¹‰à¸²à¸¢à¸±à¸‡à¹„à¸¡à¹ˆà¸¡à¸µà¹ƒà¸™à¸žà¸­à¸£à¹Œà¸•à¸¥à¸¹à¸
                            if (wavelength2.isEmpty()) {
                                wavelength2 = o.getOrDefault("Wavelength", "");
                            }
                            if (length2.isEmpty()) {
                                length2 = o.getOrDefault("Length", "");
                            }
                            if (tx2.isEmpty()) {
                                tx2 = o.getOrDefault("TxPower", "");
                            }
                            if (rx2.isEmpty()) {
                                rx2 = o.getOrDefault("RxPower", "");
                            }
                            if (rxLow2.isEmpty()) {
                                rxLow2 = o.getOrDefault("RxLowWarm", "");
                            }

                            //  System.out.printf("â†©ï¸ à¹ƒà¸Šà¹‰ Optical à¸ˆà¸²à¸à¸žà¸­à¸£à¹Œà¸•à¹à¸¡à¹ˆ %s â†’ à¸ªà¸³à¸«à¸£à¸±à¸šà¸¥à¸¹à¸ %s%n", parentPort, lp);
                            break;
                        }
                    }
                }

                // âœ… à¹ƒà¸«à¹‰à¹à¸ªà¸”à¸‡à¸žà¸­à¸£à¹Œà¸•à¸ˆà¸£à¸´à¸‡à¸—à¸±à¹‰à¸‡à¸«à¸¡à¸” (à¸£à¸§à¸¡à¸–à¸¶à¸‡ /1)
                String displayPort = formatInterface(lp);

                // âœ… à¸£à¸§à¸¡à¸‚à¹‰à¸­à¸¡à¸¹à¸¥à¸—à¸±à¹‰à¸‡à¸«à¸¡à¸”à¸¥à¸‡à¹ƒà¸™ CSV
                Str_LLDP_all += csvSafe(site) + "," + csvSafe(ip) + "," + csvSafe(displayPort) + "," + csvSafe(state2) + "," + csvSafe(desc2) + ","
                        + csvSafe(neighName) + "," + csvSafe(formatNeighborPort(cleanNokiaNeighborPort(neighIf, neighPortDesc))) + "," + csvSafe(bw2) + ","
                        + "0.00," + "" + "," + "0.00," + "" + ","
                        + csvSafe(wavelength2) + "," + csvSafe(length2) + "," + csvSafe(tx2) + "," + csvSafe(rx2) + "," + csvSafe(rxLow2) + ","
                        + csvSafe(fcs2) + "," + csvSafe(ver2) + "," + csvSafe(eq2) + "\n";
            }

            //    System.out.println("âœ… Merged LLDP + Optical complete: " + lldpRecords.size() + " records matched");
        }

        if (path.contains("LLDP")) {
            System.out.println("Done " + path);
        }
        // System.out.println(Str_LLDP_all);
        return Str_LLDP_all; // âœ… à¸›à¸à¸•à¸´ return à¹€à¸¡à¸·à¹ˆà¸­à¹„à¸¡à¹ˆà¸¡à¸µ error

    }

    // ===== Utility Methods =====
    private static double safeParseToGbps(String str) {
        if (str == null) {
            return 0.0;
        }
        str = str.replaceAll("[^0-9]", "").trim();
        if (str.isEmpty()) {
            return 0.0;
        }
        try {
            double bits = Double.parseDouble(str);
            return bits / 1_000_000_000.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static String getValueAfter(String line, String key, String delimiter) {
        if (line == null || !line.contains(key)) {
            return "";
        }
        try {
            String[] part = line.split(key, 2);
            String[] sub = part[1].split(delimiter);
            return sub[0].trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String getSafePart(String line, String keyword, String endToken) {
        if (line == null || !line.contains(keyword)) {
            return "";
        }
        try {
            String[] parts = line.split(keyword, 2);
            String[] endSplit = parts[1].split(endToken);
            return endSplit[0].trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatNeighborPort(String port) {
        if (port == null || port.isEmpty()) {
            return "";
        }
        // âœ… à¸–à¹‰à¸²à¹€à¸›à¹‡à¸™à¸žà¸­à¸£à¹Œà¸•à¸—à¸µà¹ˆà¹€à¸›à¹‡à¸™à¸•à¸±à¸§à¹€à¸¥à¸‚ à¹€à¸Šà¹ˆà¸™ 1/1/2, 9:1, 0/0/11 â†’ à¸„à¸£à¸­à¸šà¸”à¹‰à¸§à¸¢ ' '
        if (port.matches("^[0-9:/]+$")) {
            return "'" + port;
        }
        return port; // à¸–à¹‰à¸²à¹€à¸›à¹‡à¸™à¸Šà¸·à¹ˆà¸­ interface à¹€à¸Šà¹ˆà¸™ xgei-1/1/0/2 à¸«à¸£à¸·à¸­ GigabitEthernet à¹„à¸¡à¹ˆà¸•à¹‰à¸­à¸‡à¸„à¸£à¸­à¸š
    }


    private static String cleanNokiaShowPortDescription(String desc) {
        if (desc == null) {
            return "";
        }
        String value = desc.trim();

        // à¸–à¹‰à¸² format à¸¡à¸²à¹à¸šà¸š Nokia port description à¹€à¸Šà¹ˆà¸™
        // 1/1/26, 10-Gig Ethernet, To_CPE-...
        // à¹ƒà¸«à¹‰à¸•à¸±à¸”à¹€à¸«à¸¥à¸·à¸­à¹€à¸‰à¸žà¸²à¸° Description à¸ˆà¸£à¸´à¸‡à¸”à¹‰à¸²à¸™à¸«à¸¥à¸±à¸‡
        if (value.contains(",")) {
            String[] parts = value.split(",", 3);
            if (parts.length >= 3) {
                value = parts[2].trim();
            }
        }

        return value.replace("\"", "").trim();
    }

    private static String cleanNokiaNeighborPort(String portId, String portDescription) {
        String value = portId == null ? "" : portId.trim();

        // âœ… à¸–à¹‰à¸² PortId à¸§à¹ˆà¸²à¸‡ à¹ƒà¸«à¹‰à¸¥à¸­à¸‡à¸•à¸±à¸”à¸ˆà¸²à¸ Port Description à¹à¸—à¸™
        if (value.isEmpty() && portDescription != null) {
            value = portDescription.trim();
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(colon + 1).trim();
            }
        }

        // âœ… Nokia à¸¡à¸±à¸à¸ªà¹ˆà¸‡à¸¡à¸²à¹€à¸›à¹‡à¸™ 2/2/7, 10-Gig Ethernet, ...
        if (value.contains(",")) {
            value = value.split(",", 2)[0].trim();
        }

value = value.replace("*", "").replace("\"", "").trim();
        return value;
    }
// âœ… à¸Ÿà¸±à¸‡à¸à¹Œà¸Šà¸±à¸™à¹à¸—à¸£à¸à¹€à¸žà¸·à¹ˆà¸­à¸šà¸±à¸™à¸—à¸¶à¸à¸„à¹ˆà¸² optical power à¹€à¸‚à¹‰à¸² Map

    private static void putPower(Map<String, String[]> map, String port,
            String bw, String wavelength, String distance,
            float rx, float tx, String warn) {

        if (port == null || port.isEmpty()) {
            return;
        }

        // à¸–à¹‰à¸²à¹€à¸„à¸¢à¸¡à¸µà¸­à¸¢à¸¹à¹ˆà¹à¸¥à¹‰à¸§ â€” à¸­à¸±à¸›à¹€à¸”à¸•à¸„à¹ˆà¸²à¹ƒà¸«à¸¡à¹ˆ
        map.put(port, new String[]{
            bw != null ? bw : "",
            wavelength != null ? wavelength : "",
            distance != null ? distance : "",
            String.format("%.2f", rx),
            String.format("%.2f", tx),
            warn != null ? warn : ""
        });

    }

private static String csvSafe(String value) {
    if (value == null) {
        return "";
    }

    String v = value.replace("\"", "\"\"");   // escape quote à¸à¹ˆà¸­à¸™

    if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
        return "\"" + v + "\"";
    }
    return v;
}
// âœ… à¸Ÿà¸±à¸‡à¸à¹Œà¸Šà¸±à¸™à¸ªà¸³à¸«à¸£à¸±à¸šà¹ƒà¸ªà¹ˆ ' à¸«à¸™à¹‰à¸²à¸Šà¸·à¹ˆà¸­ Interface à¸–à¹‰à¸²à¹€à¸›à¹‡à¸™à¸•à¸±à¸§à¹€à¸¥à¸‚à¸¥à¹‰à¸§à¸™ à¹€à¸Šà¹ˆà¸™ 1/1/2001

    private static String formatInterface(String iface) {
        if (iface == null || iface.isEmpty()) {
            return "";
        }
        // à¸–à¹‰à¸²à¹„à¸¡à¹ˆà¸¡à¸µà¸•à¸±à¸§à¸­à¸±à¸à¸©à¸£à¹€à¸¥à¸¢ (à¹€à¸‰à¸žà¸²à¸°à¸•à¸±à¸§à¹€à¸¥à¸‚, /, à¸«à¸£à¸·à¸­ :)
        if (iface.matches("^[0-9/:]+$")) {
            return "'" + iface;
        }
        return iface;
    }
// ðŸ•’ à¹à¸›à¸¥à¸‡à¹€à¸§à¸¥à¸² UTC â†’ à¹„à¸—à¸¢ (+7 à¸Šà¸±à¹ˆà¸§à¹‚à¸¡à¸‡)

    private static String convertToThaiTime(String utcTime) {
        try {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(utcTime, fmt);
            dt = dt.plusHours(7);
            return dt.format(fmt);
        } catch (Exception e) {
            return utcTime;
        }
    }

    private static String normalizePort(String p) {
        if (p == null) {
            return "";
        }
        return p.trim().toLowerCase().replaceAll("\\s+", "");
    }

}

