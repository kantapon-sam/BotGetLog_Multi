package com.java.myapp.linkoptical;

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

            // 🔹 Extract IP จากชื่อไฟล์
            String fileName = new File(path).getName();
            String ipLoopback = "";
            try {
                // ex: [13969]10.85.160.102_AGN-BGC-2_BKK10AGNS01_HW-LLDP-Link_OPTIC_2025-10-29
                String part = fileName.substring(fileName.indexOf("]") + 1);
                ipLoopback = part.split("_")[0].trim();
            } catch (Exception e) {
                System.out.println("⚠️ Cannot parse IP from filename: " + fileName);
            }

            // 🔹 หา Site code (บรรทัดก่อน screen-length)
            // 🔹 หา Site code จากบรรทัดที่มี "screen-length"
            String siteCode = "";
            Pattern sitePattern = Pattern.compile("<([^>]+)>\\s*screen-length", Pattern.CASE_INSENSITIVE);
            for (String l : lines) {
                Matcher m = sitePattern.matcher(l);
                if (m.find()) {
                    siteCode = m.group(1).trim();  // เช่น "AGN-BGC-2_BKK10AGNS01"
                    break;
                }
            }

            String equipment = "";
            String version = "";

            for (String l : lines) {

                // 🟢 1. ดึง Version จากบรรทัด "VRP (R) software, Version 8.210 (CX600 V800R013C00SPC200)"
                if (l.toLowerCase().contains("vrp") && l.toLowerCase().contains("version")) {
                    Matcher m = Pattern.compile(
                            "Version\\s*:?\\s*([0-9\\.]+)\\s*\\(([^\\)]+)\\)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(l);
                    if (m.find()) {
                        String verNum = m.group(1).trim();   // เช่น 8.210
                        String model = m.group(2).trim();    // เช่น CX600 V800R013C00SPC200
                        version = verNum + " (" + model + ")";   // ✅ version = "8.210 (CX600 V800R013C00SPC200)"
                    }
                }

                // 🟢 2. ดึง Equipment จากบรรทัด "HUAWEI CX600-X16A uptime is ..."
                if (l.contains("HUAWEI") && l.contains("uptime")) {
                    try {
                        String part = l.substring(l.indexOf("HUAWEI") + 7, l.indexOf("uptime")).trim();
                        equipment = part; // ✅ CX600-X16A
                        break; // หลังได้ค่าไม่ต้องหาต่อ
                    } catch (Exception e) {
                        equipment = "";
                    }
                }
            }

            // 🔹 Parse LLDP neighbors (HUAWEI: display lldp neighbor)
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
                    // ✅ Start: display lldp neighbor
                    if (l.contains("display lldp neighbor")) {
                        inLLDP = true;
                    }
                    continue;
                }

                // ✅ End: display version
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
            // 🔹 Parse display interface main
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
            boolean prevWasOpticLine = false; // ✅ ต้องอยู่นอกลูป เพื่อจำค่าข้ามบรรทัด
            String prevLine = "";
            for (String l : lines) {

                if (l.contains("display interface")) {
                    inIntf = true;
                    continue;
                }
                if (!inIntf) {
                    continue;
                }

                // ✅ เจอ interface ใหม่
                if (l.matches("^[A-Za-z0-9\\|/]+\\s+current state.*")) {
                    // save ของก่อนหน้า
                    if (!currentIntf.isEmpty()) {
                        String nName = "", nIf = "";
                        if (lldpMap.containsKey(currentIntf)) {
                            nName = lldpMap.get(currentIntf)[0];
                            nIf = lldpMap.get(currentIntf)[1];
                        }

                        double inGb = safeParseToGbps(inRate);
                        double outGb = safeParseToGbps(outRate);
                        // ✅ แสดงเฉพาะ GE หรือ GigabitEthernet และไม่เอา GigabitEthernet0/0/0
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
                    distanceCaptured = false;  // ✅ รีเซ็ตที่นี่เท่านั้น!
                }

                if (l.contains("Description:")) {
                    desc = l.split("Description:")[1].trim();
                }
                if (l.contains("Port BW:")) {
                    // 🔹 ตัดข้อความหลัง "Port BW:" แล้วหยุดก่อน comma ตัวแรก
                    bw = getValueAfter(l, "Port BW:", ",").trim();

                    // 🔹 ถ้ายังมี comma หรือ space ค้าง ให้ล้างออก
                    bw = bw.replace(",", "").trim();

                    // 🔹 เผื่อบางกรณีไม่มี comma เช่น "Port BW: 10G Transceiver ..."
                    if (bw.contains(" ")) {
                        bw = bw.split("\\s+")[0].trim();
                    }
                }

// ✅ WaveLength + Transmission Distance (กันเบิ้ลแน่นอน และตัดค่าหลัง , ออก)
                if (l.toLowerCase().contains("wavelength")) {
                    // ✅ ดึงค่าหลัง "WaveLength:" จนจบหรือก่อนเครื่องหมาย comma (,)
                    int idx = l.toLowerCase().indexOf("wavelength:");
                    if (idx >= 0) {
                        String wavePart = l.substring(idx + "wavelength:".length()).trim();
                        if (wavePart.contains(",")) {
                            wavePart = wavePart.split(",")[0].trim();
                        }
                        wavelength = wavePart;  // ✅ เช่น "1310nm"
                    }

                    // 🔹 ตรวจจับ Transmission Distance แยก
                    Matcher m = Pattern.compile("transmission distance[:\\s]*([0-9]+)\\s*km", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find() && !distanceCaptured) {
                        distance = m.group(1).trim() + "km";
                        distanceCaptured = true;
                    }

                } else if (l.toLowerCase().contains("connector type")) {
                    // ❌ ตัด connector type ทิ้ง แต่ยังคงหาค่า Transmission Distance ถ้ามีในบรรทัดนี้
                    Matcher m = Pattern.compile("transmission distance[:\\s]*([0-9]+)\\s*km", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find() && !distanceCaptured) {
                        distance = m.group(1).trim() + "km";
                        distanceCaptured = true;
                    }
                    continue; // ข้าม connector line ไปเลย

                } else if (l.trim().toLowerCase().startsWith("transmission distance")) {
                    // ✅ กรณีเจอบรรทัด Transmission Distance เดี่ยว
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
                    // ✅ ล้างช่องว่างรอบๆ comma แล้วแทนเป็น <>
                    rxWarn = rxWarn.replaceAll("\\s*,\\s*", "<>");
                    // ✅ ตัดช่องว่างที่อาจเหลือใน []
                    rxWarn = rxWarn.replaceAll("\\s+", "");
                }

                // ✅ Optical Power (รองรับทั้ง 100G และ GigabitEthernet)
                if (l.matches(".*Rx\\d+ Power:.*Tx\\d+ Power:.*")) {
                    // 🔹 แบบ 100G: Rx0/Tx0 ... Rx3/Tx3
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
                        // ✅ ดึงค่า Rx Power
                        String rxStr = getSafePart(l, "Rx Power:", "dBm").trim();
                        rxSum += Float.parseFloat(rxStr);
                        rxCount++;

                        // ✅ ดึง Warning หรือ Working range ในบรรทัดเดียวกัน (ถ้ามี)
                        if (l.contains("Warning range:") || l.contains("Working range:")) {
                            String key = l.contains("Warning range:") ? "Warning range:" : "Working range:";
                            String warn = getSafePart(l, key, "dBm").trim();

                            // 🔹 ทำความสะอาดรูปแบบ เช่น [-14.400<>0.499]
                            warn = warn.replaceAll("\\s*,\\s*", "<>").replaceAll("\\s+", "");

                            // ✅ ถ้ายังไม่มี [] ครอบ ให้ใส่
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

// ✅ Input Peak Rate (บรรทัดเดียว)
                if (l.contains("Input peak rate") && l.contains("Record time")) {
                    // ดึงค่า bits/sec
                    inRate = getSafePart(l, "Input peak rate", "bits/sec").trim();

                    // ดึงเวลาหลัง Record time:
                    int idx = l.indexOf("Record time:");
                    if (idx >= 0) {
                        String t = l.substring(idx + "Record time:".length()).trim().replaceAll(",", "");
                        // ❌ ตัด timezone (+07:00) ถ้ามี
                        t = t.replaceAll("\\+\\d{2}:\\d{2}", "").trim();
                        inTime = t;
                    }
                }

// ✅ Output Peak Rate (บรรทัดเดียว)
                if (l.contains("Output peak rate") && l.contains("Record time")) {
                    // ดึงค่า bits/sec
                    outRate = getSafePart(l, "Output peak rate", "bits/sec").trim();

                    // ดึงเวลาหลัง Record time:
                    int idx2 = l.indexOf("Record time:");
                    if (idx2 >= 0) {
                        String t2 = l.substring(idx2 + "Record time:".length()).trim().replaceAll(",", "");
                        t2 = t2.replaceAll("\\+\\d{2}:\\d{2}", "").trim();
                        outTime = t2;
                    }
                }

                if (l.contains("CRC:")) {
                    String val = getValueAfter(l, "CRC:", "packets").trim();
                    // ✅ เก็บเฉพาะตัวเลข เช่น "0" หรือ "12"
                    crc = val.replaceAll("[^0-9]", "").trim();
                }
                // ✅ เพิ่มบรรทัดนี้ไว้ท้ายสุดของลูป
                prevLine = l;
            }

            // ✅ เพิ่มสุดท้าย
            if (!currentIntf.isEmpty()) {
                String nName = "", nIf = "";
                if (lldpMap.containsKey(currentIntf)) {
                    nName = lldpMap.get(currentIntf)[0];
                    nIf = lldpMap.get(currentIntf)[1];
                }
                double inGb = safeParseToGbps(inRate);
                double outGb = safeParseToGbps(outRate);

                // ✅ กรองพอร์ตสุดท้าย
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

            //System.out.println("✅ Parsed HW Optical: " + siteCode + " (" + ipLoopback + ") — " + equipment);
        } // ✅ ZTE Section (รองรับ QSFP / SFP / Neighbor / Optical Power / Peak rate)
        else if (path.contains("_ZTE-LLDP-Link_OPTIC_")) {

            Map<String, String[]> powerMap = new HashMap<>();
            String siteCode = "";
            String ipLoopback = "";
            String equipment = "";
            String version = "";

            // 🔹 Extract IP จากชื่อไฟล์
            try {
                String fileName = new File(path).getName();
                String part = fileName.substring(fileName.indexOf("]") + 1);
                ipLoopback = part.split("_")[0].trim();
            } catch (Exception e) {
                System.out.println("⚠️ Cannot parse IP from filename: " + path);
            }

            // 🔹 Site Code เช่น DN1-NKR1600
            Pattern sitePatternZTE = Pattern.compile("^(\\S+)#terminal length", Pattern.CASE_INSENSITIVE);
            for (String l : lines) {
                Matcher m = sitePatternZTE.matcher(l);
                if (m.find()) {
                    siteCode = m.group(1).trim();
                    break;
                }
            }

            // 🔹 Version + Equipment
            for (String l : lines) {
                if (l.contains("ZTE ZXCTN Software")) {
                    Matcher m = Pattern.compile("Version:\\s*([^,]+),\\s*Release", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (m.find()) {
                        version = m.group(1).trim();  // เช่น CTN9000-E V5.00.10.70
                    }
                }
                if (l.startsWith("ZXCTN")) {
                    equipment = l.trim(); // เช่น ZXCTN 9000-8EA HDC
                }
            }
// =====================================================
// ✅ Parse LLDP Neighbors (ZTE) — ครอบคลุมทุกเคสต่อ string
// =====================================================
            Map<String, String[]> lldpMap = new LinkedHashMap<>();
            boolean inLLDP = false;

            for (int i = 0; i < lines.size(); i++) {

                String l = lines.get(i);
                // 🎯 เริ่มอ่านเมื่อเจอหัวตาราง LLDP
                if (l.contains("Local Interface") && l.contains("System Name")) {
                    inLLDP = true;
                    continue;
                }

                // 🚫 หยุดเมื่อเจอ hostname เช่น DN1-NKR1600#show version
                if (inLLDP && l.matches("(?i).+#\\s*show\\s*version.*")) {
                    inLLDP = false;
                    break;
                }

                if (!inLLDP) {
                    continue;
                }

                // 🧹 ข้าม header / เส้นคั่น / ว่าง
                if (l.contains("---") || l.trim().isEmpty()) {
                    continue;
                }

                // 🔹 รวมบรรทัดต่อเนื่อง
                String fullLine = l.trim();
                if (i + 1 < lines.size() && lines.get(i + 1).startsWith(" ")) {
                    fullLine += " " + lines.get(i + 1).trim();
                    i++;
                }

                // ล้างช่องว่างซ้ำ
                fullLine = fullLine.replaceAll("\\s{2,}", " ").trim();
                String[] parts = fullLine.split("\\s+");
                if (parts.length < 6) {
                    continue;
                }
                String localIf = parts[0];
                String portId = "";
                String sysName = "";

// 🔹 เคส ZTE ที่ Port ID อยู่ 2 บรรทัด เช่น
// gei-0/0/0/2 NB ... GigabitEthernet 104 AYAAYA060CW
//                                  7/0/0
// → ต้องการ: portId = GigabitEthernet7/0/0, sysName = AYAAYA060CW
                if ("GigabitEthernet".equals(parts[3])
                        && parts[parts.length - 1].matches("\\d+/\\d+/\\d+")) {

                    portId = "GigabitEthernet" + parts[parts.length - 1];  // GigabitEthernet7/0/0
                    sysName = parts[parts.length - 2];                     // AYAAYA060CW

                } else if (parts[3].equals("GigabitEthernet") && parts.length == 9) {
                    // เช่น gei-0/7/0/3 NB xx GigabitEthernet 104 NMANMADN00W_S5 0/0/11 320_WIFI1G
                    portId = parts[3] + parts[7];               // GigabitEthernet0/0/11
                    sysName = parts[5] + parts[6] + parts[8];   // NMANMADN00W_S5320_WIFI1G

                } else if (parts[3].equals("GigabitEthernet") && parts.length == 8) {
                    // เช่น gei-0/7/0/3 NB xx GigabitEthernet 104 NMANMADN00W_S5 320_WIFI1G
                    portId = parts[3] + parts[6];               // GigabitEthernet0/0/11
                    sysName = parts[5] + parts[7];              // NMANMADN00W_S5320_WIFI1G

                } else if (parts[3].equals("port")) {
                    // เช่น xgei-0/2/0/1 NB xx port 0 120 DN1-XXX
                    portId = parts[3] + parts[4];
                    sysName = parts.length > 6 ? parts[6] : "";

                } else if (parts.length == 7) {
                    // เช่น xgei-0/7/1/20 NB xx 0 108 S07165-AF01-AP 01
                    portId = parts[3];
                    sysName = parts[5] + parts[6];

                } else {
                    // ✅ ปกติทั่วไป
                    portId = parts[3];
                    sysName = parts[5];
                }

                // ✅ เคสพิเศษ: ต่อบรรทัดถัดไป (เลขอย่างเดียว เช่น "01")
                if (i + 1 < lines.size() && lines.get(i + 1).trim().matches("^[0-9]+$")) {
                    sysName = sysName + lines.get(i + 1).trim();
                    i++;
                }

                // ✅ ป้องกัน GigabitEthernet มีช่องว่างเกิน (เช่น GigabitEthernet 0/0/11)
                portId = portId.replace(" ", "");

                // ✅ เก็บผลลัพธ์
                lldpMap.put(localIf, new String[]{sysName.trim(), portId.trim()});
            }

// ✅ Debug
            //        System.out.println("✅ Parsed ZTE LLDP Neighbors: " + lldpMap.size());
            /*for (Map.Entry<String, String[]> e : lldpMap.entrySet()) {
    System.out.printf("🔗 %-15s → %-35s (%s)%n",
            e.getKey(), e.getValue()[0], e.getValue()[1]);
}*/
// =====================================================
// ✅ ดึงเฉพาะส่วน Optical ระหว่าง #show opt brief → #show interface
// =====================================================
            List<String> opticalLines = new ArrayList<>();
            boolean inOpticalSection = false;

            for (String l : lines) {
                // เริ่มเมื่อเจอ show opt brief
                if (l.contains("#show opt brief")) {
                    inOpticalSection = true;
                    continue;
                }
                // จบเมื่อเจอ show interface
                if (l.contains("#show interface")) {
                    inOpticalSection = false;
                    break;
                }

                if (inOpticalSection) {
                    opticalLines.add(l);
                }
            }

// ✅ แสดงผลเพื่อ debug ว่าดึงได้เฉพาะส่วน Optical จริงไหม
            //   System.out.println("===== OPTICAL SECTION EXTRACTED =====");
            //      System.out.println("===== TOTAL OPTICAL LINES: " + opticalLines.size() + " =====");
// =====================================================
// ✅ PARSE Optical Section (ZTE show opt brief)
// =====================================================
            List<Map<String, String>> opticalRecords = new ArrayList<>();

            String currentPort = "";
            String bw = "", wavelength = "", warn = "", status = "", distance = "";
            String optDistance = "";  // ✅ ใช้ชื่อต่างออกไป ไม่ชนกับ distance ตอน merge
            List<Float> rxVals = new ArrayList<>();
            List<Float> txVals = new ArrayList<>();
            String pendingTxValue = null;
            for (int i = 0; i < opticalLines.size(); i++) {
                String l = opticalLines.get(i).trim();
                if (l.isEmpty() || l.startsWith("Interface") || l.startsWith("TxPower") || l.startsWith("02:")) {
                    continue;
                }

                // 🎯 เริ่มพอร์ตใหม่
                if (l.matches("^(gei|xgei|cgei|ptp)-[0-9/]+.*")) {

                    // 🔹 ถ้ามีพอร์ตก่อนหน้า — สรุปและเก็บ
                    // 🔹 ถ้ามีพอร์ตก่อนหน้า — สรุปและเก็บ
                    if (!currentPort.isEmpty()) {

                        float rxAvg = rxVals.isEmpty() ? 0f : (float) rxVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                        float txAvg = txVals.isEmpty() ? 0f : (float) txVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);

                        // ✅ แสดงรายละเอียด lane-by-lane
                        //     System.out.println("\n📶 Port: " + currentPort);
                        //   System.out.println("   Rx lanes = " + rxVals);
                        // System.out.println("   Tx lanes = " + txVals);
                        // System.out.printf("   → AvgRx = %.2f, AvgTx = %.2f%n", rxAvg, txAvg);
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

// ✅ เริ่มบรรทัดใหม่
                    String[] parts = l.split("\\s+");
                    currentPort = parts[0];

// ✅ รีเซ็ตค่า
                    bw = "";
                    wavelength = "";
                    optDistance = "";
                    pendingTxValue = null;
                    if (parts.length > 1 && parts[1].matches("^[0-9]+G.*")) {
                        bw = parts[1].split("-")[0].replaceAll("[^0-9G]", "").trim();
                    }

// ✅ ดึง Optical Module + Wavelength เช่น "1G-40km-SFP   1310nm"
                    Matcher mOpt = Pattern.compile(
                            "^(gei|xgei|cgei|ptp)-[0-9/]+\\s+([^\\s]+)\\s+([0-9]+nm)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(l);

                    if (mOpt.find()) {
                        String bwRaw = mOpt.group(2).trim();
                        if (bwRaw.contains("-")) {
                            bwRaw = bwRaw.split("-")[0];
                        }
                        bw = bwRaw.replaceAll("[^0-9G]", "").trim(); // ✅ ตัดเศษออก เช่น "1G-10km-SFP" → "1G"
                        wavelength = mOpt.group(3).trim();
                    }

// ✅ ตรวจหาค่า Transmission Distance (km) จากทั้งบรรทัด
                    Pattern distPattern = Pattern.compile("(\\d+)\\s*km", Pattern.CASE_INSENSITIVE);
                    Matcher distMatch = distPattern.matcher(l);
                    if (distMatch.find()) {
                        optDistance = distMatch.group(1).trim() + "km";
                    } else {
                        // ✅ ถ้าไม่เจอในบรรทัดนี้ ให้ลองเช็กบรรทัดก่อนหน้า
                        if (i > 0) {
                            String prevLine = opticalLines.get(i - 1).trim();
                            Matcher prev = distPattern.matcher(prevLine);
                            if (prev.find()) {
                                optDistance = prev.group(1).trim() + "km";
                            }
                        }
                        // ✅ ถ้ายังไม่เจออีก ให้เช็กบรรทัดถัดไป
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

// ✅ ดึง Rx จากบรรทัดแรก (พร้อม WarningRange)
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
                            // 🔹 match แรก = Rx
                            rxVals.add(val);
                            // ใช้ warn จากบรรทัดแรกเท่านั้น
                            warn = "[" + m.group(2).replaceAll("\\s*,\\s*", "<>") + "]";
                        } else {
                            // 🔹 match ที่ 2,3,... = Tx (ปกติ ถ้าอยู่บรรทัดเดียวกัน)
                            txVals.add(val);
                        }
                        idx++;
                    }

// ✅ ตรวจหาเลข Tx ที่อาจถูกตัดบรรทัด (เช่น ... -3.4 แล้วบรรทัดถัดไปขึ้นต้นด้วย "/[")
                    if (pendingTxValue == null) {
                        // 🔍 เคส Tx ถูกตัดบรรทัดแบบ "1.5/" แล้วบรรทัดถัดไปเป็น "[-4.7,4] ..."
                        Matcher tailNumWithSlash = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)/\\s*$").matcher(l);
                        if (tailNumWithSlash.find()) {
                            // เก็บค่า Tx ไว้รวมกับบรรทัดถัดไป
                            pendingTxValue = tailNumWithSlash.group(1);
                        } else {
                            // เคสเดิม: เลขอยู่ท้ายบรรทัดเลย (ไม่มี / ต่อท้าย)
                            Matcher tailNum = Pattern.compile("(-?[0-9]+(?:\\.[0-9]+)?)\\s*$").matcher(l);
                            if (tailNum.find()) {
                                String lastNum = tailNum.group(1);
                                try {
                                    float numVal = Float.parseFloat(lastNum);
                                    if (!rxVals.isEmpty()) {
                                        float lastRx = rxVals.get(rxVals.size() - 1);
                                        // ถ้าเลขท้ายไม่เท่ากับ Rx → ถือว่าเป็น Tx ที่โดนแยกบรรทัด
                                        if (Math.abs(numVal - lastRx) > 0.0001f) {
                                            pendingTxValue = lastNum;
                                        }
                                    } else {
                                        // ไม่มี Rx แต่มีเลข → อาจเป็น Tx อย่างเดียว
                                        pendingTxValue = lastNum;
                                    }
                                } catch (NumberFormatException ex) {
                                    // ignore
                                }
                            }
                        }
                    }

                    // ✅ ถัดไปคือ Tx → ต้องวนอ่านต่อ
                    int j = i + 1;
                    while (j < opticalLines.size()) {
                        String next = opticalLines.get(j).trim();

                        // ❌ ข้ามบรรทัดเศษ เช่น "เศษ 0.5" หรือ "9.5"
                        if (next.matches("^[0-9.]+\\s+[0-9.]+$")) {
                            j++;
                            continue;
                        }

                        if (next.isEmpty()) {
                            j++;
                            continue;
                        }

                        // ถ้าเจอ port ใหม่ → จบ loop
                        if (next.matches("^(gei|xgei|cgei|ptp)-[0-9/]+.*")) {
                            i = j - 1;
                            break;
                        }

                        // ✅ กรณี Tx ถูก split: บรรทัดก่อนจบด้วยเลข และบรรทัดนี้ขึ้นต้นด้วย "/[...]"
                        if (pendingTxValue != null && (next.startsWith("/[") || next.startsWith("["))) {
                            // next อาจเป็น "/[-8.2,1.5] ..." หรือ "[-8.2,1.5] ..."
                            String combined = pendingTxValue + (next.startsWith("/[") ? "" : "/") + next;   // เช่น "-3.4/[-8.2,1.5] Unknown"
                            Matcher mVal2 = Pattern.compile("([-0-9.]+)/\\[([-0-9.,]+)]").matcher(combined);
                            if (mVal2.find()) {
                                float val2 = 0f;
                                try {
                                    val2 = Float.parseFloat(mVal2.group(1));
                                } catch (Exception e) {
                                    val2 = 0f;
                                }
                                // ใส่เป็น Tx
                                txVals.add(val2);
                                if (warn.isEmpty()) {
                                    warn = "[" + mVal2.group(2).replaceAll("\\s*,\\s*", "<>") + "]";
                                }
                            }
                            pendingTxValue = null;
                        } else {
                            // ✅ จับค่า -x.x/[…] จากบรรทัดตามปกติ
                            Matcher mVal = Pattern.compile("([-0-9.]+)/\\[([-0-9.,]+)]").matcher(next);
                            if (mVal.find()) {
                                float val = 0f;
                                try {
                                    val = Float.parseFloat(mVal.group(1));
                                } catch (Exception e) {
                                    val = 0f;
                                }

                                // 🔹 ถ้าขนาด list ไม่เท่ากัน → อันนี้คือ Tx
                                if (rxVals.size() > txVals.size()) {
                                    txVals.add(val);
                                } else {
                                    rxVals.add(val);
                                }
                                // ❌ อย่าให้ warn ถูกทับจากบรรทัดหลัง
                                if (warn.isEmpty()) {
                                    warn = "[" + mVal.group(2).replaceAll("\\s*,\\s*", "<>") + "]";
                                }
                            }
                        }

                        // ✅ จับ Status (Normal/Abnormal/Unknown)
                        if (status.isEmpty() && next.matches(".*\\s+(Normal|Abnormal|Unknown)\\s*$")) {
                            status = next.replaceAll(".*\\s+(Normal|Abnormal|Unknown)\\s*$", "$1");
                        }

                        j++;
                    }

                } else if (currentPort != null && l.toLowerCase().contains("unknown")) {
                    // ✅ รองรับทุกเคส Unknown เช่น N/A/[-5.0,2.0] Unknown หรือ N/A / [-7.1, -3.5] Unknown
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

// ✅ เก็บพอร์ตสุดท้าย
            if (!currentPort.isEmpty() && (!rxVals.isEmpty() || !txVals.isEmpty())) {
                float rxAvg = (float) rxVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                float txAvg = (float) txVals.stream().mapToDouble(Float::doubleValue).average().orElse(0);

                // ✅ Debug ค่าเฉลี่ย lane สุดท้าย
                //     System.out.println("\n📶 Port: " + currentPort);
                //       System.out.println("   Rx lanes = " + rxVals);
                //     System.out.println("   Tx lanes = " + txVals);
                //     System.out.printf("   → AvgRx = %.2f, AvgTx = %.2f%n", rxAvg, txAvg);
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

// ✅ แสดงผลตรวจสอบ
            /*     System.out.println("===== PARSED ZTE OPTICAL RECORDS =====");
            for (Map<String, String> o : opticalRecords) {
                System.out.printf("%-15s | BW=%-4s | WL=%-7s | Rx=%-6s | Tx=%-6s | Range=%-15s | %-10s%n",
                        o.get("Port"), o.get("MaxBW"), o.get("Wavelength"),
                        o.get("RxPower"), o.get("TxPower"),
                        o.get("WarningRange"), o.get("Status"));
            }
            System.out.println("===== TOTAL OPTICAL PORTS: " + opticalRecords.size() + " =====");*/
            // =====================================================
// ✅ ดึงเฉพาะส่วน Interface ระหว่าง #show interface → #quit
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

// ✅ Debug ตรวจสอบ
            // System.out.println("===== INTERFACE SECTION EXTRACTED =====");
            /*for (String s : interfaceLines) {
    System.out.println(s);
}*/
            //    System.out.println("===== TOTAL INTERFACE LINES: " + interfaceLines.size() + " =====");
// =====================================================
// ✅ Parse show interface (ZTE)
// =====================================================
            List<Map<String, String>> intfRecords = new ArrayList<>();
            String currentPort_if = "", desc_if = "", oper_if = "", crc_if = "";
            double inPeakGbps_if = 0, outPeakGbps_if = 0;
            String inTime_if = "", outTime_if = "";

            for (int i = 0; i < interfaceLines.size(); i++) {
                String l = interfaceLines.get(i).trim();

                // 🎯 เริ่มพอร์ตใหม่ (เฉพาะที่มี gei อยู่ในชื่อ)
                if (l.matches("^[A-Za-z0-9/\\-]+\\s+is\\s+.*,\\s*ifindex:.*") && l.toLowerCase().contains("gei")) {
                    // 🔹 เก็บพอร์ตเก่าก่อนหน้า (ถ้ามี)
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

                    // ✅ เริ่มอ่านพอร์ตใหม่ (เฉพาะที่มี gei)
                    currentPort_if = l.split("\\s+")[0].trim().toLowerCase().replaceAll("\\s+", "");

                    oper_if = l.replaceAll(".*is\\s+([^,]+),.*", "$1").trim();
                    desc_if = crc_if = "";
                    inPeakGbps_if = outPeakGbps_if = 0;
                    inTime_if = outTime_if = "";
                    continue;
                }

                // ✅ ข้ามถ้าไม่อยู่ใน port ที่มี gei
                if (currentPort_if.isEmpty() || !currentPort_if.toLowerCase().contains("gei")) {
                    continue;
                }

                // 🔹 Description
                if (l.startsWith("Description:")) {
                    desc_if = l.substring("Description:".length()).trim();

                    // ✅ ถ้ามี " เปิดแต่ไม่มีปิด ให้ต่อบรรทัดต่อไป
                    if (desc_if.startsWith("\"") && !desc_if.endsWith("\"")) {
                        StringBuilder fullDesc = new StringBuilder(desc_if);
                        int j = i + 1;
                        while (j < interfaceLines.size()) {
                            String nextLine = interfaceLines.get(j).trim();
                            fullDesc.append(" ").append(nextLine);
                            if (nextLine.endsWith("\"")) {
                                i = j; // ข้ามบรรทัดที่ใช้แล้ว
                                break;
                            }
                            // ✅ ถ้าพบบรรทัดอื่นที่ไม่ใช่ description แล้ว — หยุดทันที
                            if (nextLine.matches("^(Line protocol|detected status|Last line|Input|Output|CRC|Speed|Port|IPv[46]).*")) {
                                break;
                            }
                            j++;
                        }
                        desc_if = fullDesc.toString().trim();
                    }

                    // ✅ ตัด quote ซ้ายขวาออก
                    desc_if = desc_if.replaceAll("^\"|\"$", "").trim();

                    // ✅ ล้างเศษที่ตามมาผิด เช่น "Line protocol is up..." (กันไว้เผื่อ)
                    desc_if = desc_if.replaceAll("\\s+(Line protocol|detected status|Last line).*", "").trim();
                }

                // 🔹 CRC Error
                if (l.contains("In_CRC_ERROR")) {
                    Matcher m = Pattern.compile("In_CRC_ERROR\\s+([0-9]+)").matcher(l);
                    if (m.find()) {
                        crc_if = m.group(1);
                    }
                }

                // 🔹 Input Peak Rate
                if (l.contains("Input") && l.contains("peak time")) {
                    Matcher m = Pattern.compile("Input\\s*:\\s*([0-9]+)\\s*bit/s.*?peak time\\s*([0-9\\-: ]+)").matcher(l);
                    if (m.find()) {
                        inPeakGbps_if = Double.parseDouble(m.group(1)) / 1_000_000_000.0;
                        inTime_if = convertToThaiTime(m.group(2));
                    }
                }

                // 🔹 Output Peak Rate
                if (l.contains("Output") && l.contains("peak time")) {
                    Matcher m = Pattern.compile("Output\\s*:\\s*([0-9]+)\\s*bit/s.*?peak time\\s*([0-9\\-: ]+)").matcher(l);
                    if (m.find()) {
                        outPeakGbps_if = Double.parseDouble(m.group(1)) / 1_000_000_000.0;
                        outTime_if = convertToThaiTime(m.group(2));
                    }
                }
            }

// ✅ เก็บพอร์ตสุดท้าย (เฉพาะ gei)
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

// ✅ แสดงผลเฉพาะพอร์ตที่ชื่อมี gei
            //     System.out.println("===== PARSED ZTE INTERFACE (PORT NAME CONTAINS 'gei') =====");
            /*for (Map<String, String> r : intfRecords) {
    System.out.printf("%-15s | %-45s | Oper=%-12s | CRC=%-6s | In=%.6fG @ %s | Out=%.6fG @ %s%n",
            r.get("Port"), r.get("Description"), r.get("Oper"), r.get("CRC"),
            Double.parseDouble(r.get("InPeak(G)")), r.get("InTime(TH)"),
            Double.parseDouble(r.get("OutPeak(G)")), r.get("OutTime(TH)"));
}*/
            //       System.out.println("===== TOTAL PORTS (CONTAINING 'gei'): " + intfRecords.size() + " =====");
// =====================================================
// ✅ รวมข้อมูล ZTE: Optical + Interface + LLDP (แสดงทุกพอร์ต gei ทั้งหมด)
// =====================================================
            //System.out.println("🔄 Mapping all ZTE ports containing 'gei'...");
// 🔹 รวมชื่อพอร์ตทั้งหมดที่มี 'gei' จากทั้ง 3 แหล่ง (Optical / Interface / LLDP)
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

            //          System.out.println("✅ Total GEI ports found: " + allGeiPorts.size());
// =====================================================
// ✅ รวมค่าจากทุกส่วน (ใส่ค่าว่างถ้าไม่มีข้อมูล)
// =====================================================
            for (String port : allGeiPorts) {

                // ====== Optical ======
                Map<String, String> opticRec = null;
                for (Map<String, String> o : opticalRecords) {
                    String optPort = o.get("Port");
                    if (optPort == null || port == null) {
                        continue;
                    }

                    // Normalize เพื่อให้เทียบแบบไม่พลาด
                    String normOptPort = normalizePort(optPort);
                    String normPort = normalizePort(port);

// ✅ เทียบให้แม่นทั้งแบบเต็มและ prefix (ไม่มีลูกแม่)
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

                // ====== เขียนรวมทั้งหมด ======
                Str_LLDP_all += csvSafe(siteCode) + "," + csvSafe(ipLoopback) + "," + csvSafe(port) + ","
                        + csvSafe(oper) + "," + csvSafe(desc) + "," + csvSafe(neighName) + ","
                        + csvSafe(formatNeighborPort(neighIf)) + "," + csvSafe(bwOptic) + ","
                        + csvSafe(inPeak) + "," + csvSafe(inTime) + ","
                        + csvSafe(outPeak) + "," + csvSafe(outTime) + ","
                        + csvSafe(wl) + "," + csvSafe(distanceOpt) + ","
                        + csvSafe(tx) + "," + csvSafe(rx) + "," + csvSafe(warnOptic) + ","
                        + csvSafe(crc) + "," + csvSafe(version) + "," + csvSafe(equipment) + "\n";
            }

        } // ✅ NOKIA placeholder (ยังไม่ต้องเขียน logic)
        else if (path.contains("_N-LLDP-Link_OPTIC_")) {

            String siteCode = "", ipLoopback = "", version = "", equipment = "";
            String currentPort = "", currentState = "", desc = "";
            String bw = "", wavelength = "", rxPower = "", txPower = "", warn = "", crc = "0";

            // 🔹 Extract IP from filename
            try {
                String fileName = new File(path).getName();
                String part = fileName.substring(fileName.indexOf("]") + 1);
                ipLoopback = part.split("_")[0].trim();
            } catch (Exception e) {
                System.out.println("⚠️ Cannot parse IP from filename: " + path);
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

// ✅ หา Version + Equipment
            for (String l : lines) {
                // ✅ ตรวจบรรทัดที่ตรงกับ "Type                              :"
                if (l.contains("  Type                              :")) {
                    equipment = l.split(": ")[1];
                }
                // ✅ หา TiMOS version
                if (version.isEmpty() && l.contains("TiMOS")) {
                    Matcher mVer = Pattern.compile("(TiMOS-[A-Z]-[0-9\\.R]+)", Pattern.CASE_INSENSITIVE).matcher(l);
                    if (mVer.find()) {
                        version = mVer.group(1).trim(); // เช่น TiMOS-C-19.10.R12
                    }
                }
            }
// ✅ เก็บข้อมูล LLDP Remote Peer แยกไว้ก่อน
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

                // 🎯 เริ่มบล็อกใหม่เมื่อเจอ "Port x/x/x Bridge nearest-bridge Remote Peer Information"
                if (l.matches("(?i).*ethernet\\s+lldp\\s+remote-info.*")) {

                    //               System.out.println(l);
                    // 🔹 ถ้ามีบล็อกก่อนหน้าให้เก็บไว้ก่อน
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

                    // 🔄 เริ่มบล็อกใหม่
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

                // 🧩 ดึงข้อมูลหลักจากแต่ละบรรทัด
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

// ✅ เก็บบล็อกสุดท้าย
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

            //       System.out.println("✅ Parsed Nokia LLDP Remote Info: " + siteCode + " (" + ipLoopback + "), Records = " + lldpRecords.size());
// ✅ เก็บ Optical Data แยกไว้ก่อน (ยังไม่รวมกับ LLDP)
// ✅ เก็บ Optical Data แยกไว้ก่อน (ยังไม่รวมกับ LLDP)
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

                // ✅ ปิด block เดิมถ้าเจอ show port ใหม่หรือ logout
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

                    // 🧹 Reset ตัวแปรทั้งหมด
                    inShowPort = false;
                    portName = description = operSpeed = adminState = operState = opticWavelength
                            = opticalCompliance = linkLength = opticRxPower = opticTxPower
                            = serialNo = partNo = modelNo = "";
                }

                // 🎯 เริ่ม block ใหม่เมื่อเจอ "Description :"
                // Nokia บางพอร์ตตัด description ลงหลายบรรทัดก่อนถึง "Interface"
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
                            i = j - 1; // ให้รอบถัดไป parse บรรทัด Interface ตามปกติ
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

                // ✅ Parsing รายละเอียดใน block ปัจจุบัน
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
                    // ✅ ดึงเฉพาะส่วนที่มีตัวเลขตามด้วย km
                    Matcher m = Pattern.compile("([0-9]+\\s*km)", Pattern.CASE_INSENSITIVE).matcher(val);
                    if (m.find()) {
                        linkLength = m.group(1).replaceAll("\\s+", "");  // เช่น "40km"
                    } else {
                        linkLength = "";
                    }
                } else if (l.startsWith("Rx Optical Power")) {
                    // ดึงค่าทั้ง 5 ช่อง: Value, HighAlarm, HighWarn, LowWarn, LowAlarm
                    Matcher m = Pattern.compile(
                            "(?i)Rx Optical Power.*?\\)\\s*([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)"
                    ).matcher(l);

                    if (m.find()) {
                        opticRxPower = m.group(1).trim();  // ✅ ค่าจริง เช่น -4.52
                        rxLowWarn = m.group(4).trim();  // ✅ Low Warn เช่น -18.01
                    }
                } else if (l.startsWith("Tx Output Power")) {
                    Matcher m = Pattern.compile("(?i)Tx Output Power.*?\\)\\s*([-0-9\\.]+)").matcher(l);
                    if (m.find()) {
                        opticTxPower = m.group(1).trim();
                    }
                } else if (l.toLowerCase().startsWith("lane rx optical")) {

                    // 🔹 เก็บค่า LowWarn จากบรรทัด Lane Rx Optical Pwr (avg dBm)
                    Matcher m = Pattern.compile(
                            "(?i)Lane Rx Optical Pwr.*?([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)\\s+([-0-9\\.]+)"
                    ).matcher(l);
                    if (m.find()) {
                        rxLowWarn = m.group(3).trim();  // ✅ -11.60
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
                            //   System.out.printf("📶 Lane %s → Tx=%.2f Rx=%.2f%n", lane.group(1), tx, rx);

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

                                //  System.out.println("✅ Avg Tx=" + opticTxPower + " dBm, Avg Rx=" + opticRxPower + " dBm (" + portName + ")");
                                txVals.clear();
                                rxVals.clear();
                            }

                        } catch (Exception e) {
                            System.err.println("❌ Parse error line: " + l);
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
                        fcsErrors = m.group(1).trim();  // ✅ ได้ค่า "857"
                    }
                }

            }

// ✅ เพิ่มบล็อกสุดท้าย (กรณีไฟล์จบโดยไม่มี logout)
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

            //       System.out.println("✅ Parsed Nokia Optical: " + siteCode + " (" + ipLoopback + "), Records = " + opticalRecords.size());
            //   for (Map<String, String> rec : opticalRecords) {
            //       System.out.println(rec);
            //    }
            //     for (int i = 0; i < opticalRecords.size(); i++) {
            //           System.out.println(opticalRecords.get(i));
            //  }
            // =====================================================
// ✅ รวมข้อมูล Optical กับ LLDP (Map)
// =====================================================
            //System.out.println("🔄 Merging Nokia LLDP + Optical Records...");
            for (Map<String, String> lldp : lldpRecords) {
                String lp = lldp.getOrDefault("Port", "");
                String site = lldp.getOrDefault("SiteCode", "");
                String ip = lldp.getOrDefault("IP", "");
                String neighName = lldp.getOrDefault("SystemName", "");
                String neighIf = lldp.getOrDefault("PortId", "");
                String neighPortDesc = lldp.getOrDefault("PortDescription", "");
                String ver2 = lldp.getOrDefault("Version", "");
                String eq2 = lldp.getOrDefault("Equipment", "");

                // ✅ หาคู่ใน Optical ตาม Port
                Map<String, String> opticRec = null;
                for (Map<String, String> o : opticalRecords) {
                    String portVal = o.get("Port");
                    if (portVal != null && portVal.equalsIgnoreCase(lp)) {
                        opticRec = o;
                        break;
                    }
                }

                // ✅ กรณี port พิเศษ (2/1/c6/1) ให้ใช้ข้อมูลจาก base port (2/1/c6)
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
                        System.out.printf("🔗 LLDP Port %-15s → ใช้ข้อมูลจาก Optical %-15s%n", lp, specialBasePort);
                    }
                }

                // ✅ ตัวแปรผลลัพธ์
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

                // ✅ Description ของ Nokia ให้ใช้จาก show port เท่านั้น
                // ไม่ override ด้วย Port Description จาก lldp remote-info
                if (desc2 != null) {
                    desc2 = desc2.trim();
                }
// ✅ ถ้าเป็นพอร์ตลูก (เช่น 2/1/c1/1) แต่ไม่มีข้อมูล Wavelength → ใช้ค่าจากพอร์ตแม่ (2/1/c1)
                if ((wavelength2.isEmpty() || tx2.isEmpty() || rx2.isEmpty() || rxLow2.isEmpty() || length2.isEmpty())
                        && lp.matches(".+/[0-9]+$")) {

                    String parentPort = lp.replaceAll("/[0-9]+$", "");
                    for (Map<String, String> o : opticalRecords) {
                        String portVal = o.get("Port");
                        if (portVal != null && portVal.equalsIgnoreCase(parentPort)) {

                            // ✅ ใช้ข้อมูลจากพอร์ตแม่ ถ้ายังไม่มีในพอร์ตลูก
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

                            //  System.out.printf("↩️ ใช้ Optical จากพอร์ตแม่ %s → สำหรับลูก %s%n", parentPort, lp);
                            break;
                        }
                    }
                }

                // ✅ ให้แสดงพอร์ตจริงทั้งหมด (รวมถึง /1)
                String displayPort = formatInterface(lp);

                // ✅ รวมข้อมูลทั้งหมดลงใน CSV
                Str_LLDP_all += csvSafe(site) + "," + csvSafe(ip) + "," + csvSafe(displayPort) + "," + csvSafe(state2) + "," + csvSafe(desc2) + ","
                        + csvSafe(neighName) + "," + csvSafe(formatNeighborPort(cleanNokiaNeighborPort(neighIf, neighPortDesc))) + "," + csvSafe(bw2) + ","
                        + "0.00," + "" + "," + "0.00," + "" + ","
                        + csvSafe(wavelength2) + "," + csvSafe(length2) + "," + csvSafe(tx2) + "," + csvSafe(rx2) + "," + csvSafe(rxLow2) + ","
                        + csvSafe(fcs2) + "," + csvSafe(ver2) + "," + csvSafe(eq2) + "\n";
            }

            //    System.out.println("✅ Merged LLDP + Optical complete: " + lldpRecords.size() + " records matched");
        }

        if (path.contains("LLDP")) {
            System.out.println("Done " + path);
        }
        // System.out.println(Str_LLDP_all);
        return Str_LLDP_all; // ✅ ปกติ return เมื่อไม่มี error

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
        // ✅ ถ้าเป็นพอร์ตที่เป็นตัวเลข เช่น 1/1/2, 9:1, 0/0/11 → ครอบด้วย ' '
        if (port.matches("^[0-9:/]+$")) {
            return "'" + port;
        }
        return port; // ถ้าเป็นชื่อ interface เช่น xgei-1/1/0/2 หรือ GigabitEthernet ไม่ต้องครอบ
    }


    private static String cleanNokiaShowPortDescription(String desc) {
        if (desc == null) {
            return "";
        }
        String value = desc.trim();

        // ถ้า format มาแบบ Nokia port description เช่น
        // 1/1/26, 10-Gig Ethernet, To_CPE-...
        // ให้ตัดเหลือเฉพาะ Description จริงด้านหลัง
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

        // ✅ ถ้า PortId ว่าง ให้ลองตัดจาก Port Description แทน
        if (value.isEmpty() && portDescription != null) {
            value = portDescription.trim();
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(colon + 1).trim();
            }
        }

        // ✅ Nokia มักส่งมาเป็น 2/2/7, 10-Gig Ethernet, ...
        if (value.contains(",")) {
            value = value.split(",", 2)[0].trim();
        }

value = value.replace("*", "").replace("\"", "").trim();
        return value;
    }
// ✅ ฟังก์ชันแทรกเพื่อบันทึกค่า optical power เข้า Map

    private static void putPower(Map<String, String[]> map, String port,
            String bw, String wavelength, String distance,
            float rx, float tx, String warn) {

        if (port == null || port.isEmpty()) {
            return;
        }

        // ถ้าเคยมีอยู่แล้ว — อัปเดตค่าใหม่
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

    String v = value.replace("\"", "\"\"");   // escape quote ก่อน

    if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
        return "\"" + v + "\"";
    }
    return v;
}
// ✅ ฟังก์ชันสำหรับใส่ ' หน้าชื่อ Interface ถ้าเป็นตัวเลขล้วน เช่น 1/1/2001

    private static String formatInterface(String iface) {
        if (iface == null || iface.isEmpty()) {
            return "";
        }
        // ถ้าไม่มีตัวอักษรเลย (เฉพาะตัวเลข, /, หรือ :)
        if (iface.matches("^[0-9/:]+$")) {
            return "'" + iface;
        }
        return iface;
    }
// 🕒 แปลงเวลา UTC → ไทย (+7 ชั่วโมง)

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
