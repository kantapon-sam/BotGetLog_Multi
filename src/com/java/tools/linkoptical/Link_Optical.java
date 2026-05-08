package com.java.tools.linkoptical;

import com.java.shared.AppConsole;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Link_Optical {

    private static final Pattern P_NODE_ACAGCO = Pattern.compile("(?i)([A-Z0-9]+-(?:AC|AG|CO)-\\d+)");
    private static final Pattern P_NODE_PREFIXED = Pattern.compile(
            "(?i)\\b((?:CPE|PN\\d?|PN|DN\\d?|DN|RN\\d?|RN|AGN\\d?|AGN|AN\\d?|AN)-[A-Z0-9-]+)\\b");
    private static final Pattern P_ALNUM7_ANY = Pattern.compile("(?i)(?<![A-Z0-9])([A-Z0-9]{7})(?![A-Z0-9])");

    static class PortSummary {

        String type = "";
        String siteCode = "";
        String ipLoopback = "";

        int total1G = 0;
        int used1G = 0;
        int reserved1G = 0;

        int total10G = 0;
        int used10G = 0;
        int reserved10G = 0;

        int total100G = 0;
        int used100G = 0;
        int reserved100G = 0;

        int reservedForRehoming = 0;
        int reservedForOLT = 0;
    }

    private static boolean isAlphaNum7Mix(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toUpperCase();
        if (t.length() != 7) {
            return false;
        }
        boolean hasAlpha = false, hasDigit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                hasAlpha = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else {
                return false;
            }
        }
        return hasAlpha && hasDigit;
    }

    private static boolean matchFile2ByNeighborSysName(String neighborSysName) {
        if (neighborSysName == null) {
            return false;
        }
        String n = neighborSysName.trim();
        if (n.isEmpty()) {
            return false;
        }

        if (P_NODE_ACAGCO.matcher(n).find()) {
            return true;
        }
        if (P_NODE_PREFIXED.matcher(n).find()) {
            return true;
        }

        Matcher m7 = P_ALNUM7_ANY.matcher(n.toUpperCase());
        while (m7.find()) {
            if (isAlphaNum7Mix(m7.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static String extractNeighborDesFromNeighborSysName(String neighborSysName) {
        if (neighborSysName == null) {
            return "";
        }
        String n = neighborSysName.trim();
        if (n.isEmpty()) {
            return "";
        }

        Matcher mAcAgCo = P_NODE_ACAGCO.matcher(n);
        if (mAcAgCo.find()) {
            return mAcAgCo.group(1).toUpperCase().trim();
        }

        Matcher mPrefix = P_NODE_PREFIXED.matcher(n);
        if (mPrefix.find()) {
            return mPrefix.group(1).toUpperCase().trim();
        }

        Matcher m7 = P_ALNUM7_ANY.matcher(n.toUpperCase());
        while (m7.find()) {
            String token = m7.group(1).toUpperCase().trim();
            if (isAlphaNum7Mix(token)) {
                return token;
            }
        }
        return "";
    }

    public static void main(String[] args) {
        AppConsole.install("Link Optical Console", "Link Optical - Console");
        Dialog.setLAF();
        System.out.println("[INFO] Link Optical started");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

        String file_fail = "";
        try {
            int TotalFile = 0;

            String header = "Site code,"
                    + "IP loopback,"
                    + "Interface,"
                    + "Current State,"
                    + "Description,"
                    + "Neighbor SysName,"
                    + "Neighbor PortID,"
                    + "Max BW,"
                    + "Input peak rate,"
                    + "Input peak time,"
                    + "Output peak rate,"
                    + "Output peak time,"
                    + "Wavelength,"
                    + "Transmission Distance(km),"
                    + "Tx Optical Power(dBm),"
                    + "Rx Optical Power(dBm),"
                    + "Rx Min warning range(dBm),"
                    + "CRC,"
                    + "Version,"
                    + "Equipment\n";

            PathFile f = new PathFile();
            File[] files = f.getFile().listFiles();
            if (files == null) {
                throw new Exception("Input folder not found or empty");
            }

            Arrays.sort(files, new Comparator<File>() {
                public int compare(File f1, File f2) {
                    return Long.compare(f1.lastModified(), f2.lastModified());
                }
            });

            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().contains("LLDP") && files[i].getName().toLowerCase().endsWith(".txt")) {
                    TotalFile++;
                }
            }
            System.out.println("[INFO] Found " + TotalFile + " LLDP file(s) to process");
            if (TotalFile == 0) {
                String message = "No LLDP input files found in _output\\Total_Log";
                System.out.println("[WARN] " + message);
                Dialog.Info(message);
                return;
            }

            // =========================
            // =========================
            LocalDateTime now1 = LocalDateTime.now();
            String formattedDateTime1 = now1.format(formatter);
            String output_LLDP = "DataLLDP_Neighbor_" + formattedDateTime1 + ".csv";
            File fullFile = new File(f.getLLDP() + "\\" + output_LLDP);
            if (fullFile.getParentFile() != null) {
                fullFile.getParentFile().mkdirs();
            }

            FileWriter fwAll = new FileWriter(fullFile, false);
            fwAll.write(header);

            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().contains("LLDP") && files[i].getName().toLowerCase().endsWith(".txt")) {
                    if (files.length > 1) {
                        file_fail = files[i].getName();
                    }
                    System.out.println("[PROCESS] Reading " + files[i].getName());

                    BufferedReader br = new BufferedReader(new FileReader(files[i]));
                    String pathOutput = files[i].getName().split(".txt")[0];

                    String chunk = Check_Link_Optical.Sub(br, pathOutput);
                    fwAll.write(chunk);
                    br.close();
                }
            }
            fwAll.close();
            System.out.println("[INFO] Generated " + output_LLDP);

            // =========================
            // =========================
            LocalDateTime now2 = now1.plusSeconds(1);
            String formattedDateTime2 = now2.format(formatter);
            if (formattedDateTime2.equals(formattedDateTime1)) {
                now2 = now2.plusSeconds(1);
                formattedDateTime2 = now2.format(formatter);
            }

            String output_LLDP_2 = "DataLLDP_Neighbor_" + formattedDateTime2 + ".csv";
            File filteredFile = new File(f.getLLDP() + "\\" + output_LLDP_2);
            if (filteredFile.getParentFile() != null) {
                filteredFile.getParentFile().mkdirs();
            }

            BufferedReader csvReader = new BufferedReader(new FileReader(fullFile));
            FileWriter fw2 = new FileWriter(filteredFile, false);

            String header2 = header.replace("\n", "") + ",NeighborDes\n";
            fw2.write(header2);

            String row;
            boolean firstLine = true;
            while ((row = csvReader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue;
                }

                if (row.trim().isEmpty()) {
                    continue;
                }

                String[] cols = splitCsvLineSimple(row);
                if (cols.length < 6) {
                    continue;
                }

                String neigh = cols[5];

                if (matchFile2ByNeighborSysName(neigh)) {
                    String neighborDes = extractNeighborDesFromNeighborSysName(neigh);

                    if (neighborDes == null || neighborDes.trim().isEmpty()) {
                        continue;
                    }

                    fw2.write(row + "," + neighborDes + "\n");
                }
            }

            csvReader.close();
            fw2.close();
            System.out.println("[INFO] Generated " + output_LLDP_2);

            // =========================
            // =========================
            LocalDateTime now3 = now2.plusSeconds(1);
            String formattedDateTime3 = now3.format(formatter);
            if (formattedDateTime3.equals(formattedDateTime2)) {
                now3 = now3.plusSeconds(1);
                formattedDateTime3 = now3.format(formatter);
            }

            String output_PORT = "DataPort_" + formattedDateTime3 + ".csv";
            File portFile = new File(f.getLLDP() + "\\" + output_PORT);
            if (portFile.getParentFile() != null) {
                portFile.getParentFile().mkdirs();
            }

            BufferedReader portReader = new BufferedReader(new FileReader(fullFile));
            FileWriter fw3 = new FileWriter(portFile, false);

            String header3 = "Type,Site code,IP loopback,"
                    + "Total_1G,Used_1G,Free_1G,Reserved_1G,"
                    + "Total_10G,Used_10G,Free_10G,Reserved_10G,"
                    + "Total_100G,Used_100G,Free_100G,Reserved_100G,"
                    + "Reserved_For_Rehoming,Reserved_For_OLT\n";

            fw3.write(header3);

            Map<String, PortSummary> portMap = new LinkedHashMap<String, PortSummary>();

            String portRow;
            boolean firstLinePort = true;

            while ((portRow = portReader.readLine()) != null) {
                if (firstLinePort) {
                    firstLinePort = false;
                    continue;
                }

                if (portRow.trim().isEmpty()) {
                    continue;
                }

                String[] cols = splitCsvLineSimple(portRow);
                if (cols.length < 8) {
                    continue;
                }

                String siteCode = nz(cols[0]);
                String ipLoopback = nz(cols[1]);
                String iface = nz(cols[2]);
                String currentState = nz(cols[3]);
                String description = nz(cols[4]);
                String maxBW = normalizeBW(iface, cols[7]);

                if (siteCode.isEmpty() && ipLoopback.isEmpty()) {
                    continue;
                }

                if (maxBW.isEmpty()) {
                    continue;
                }

                String key = siteCode + "|" + ipLoopback;
                PortSummary ps = portMap.get(key);
                if (ps == null) {
                    ps = new PortSummary();
                    ps.type = detectType(siteCode);
                    ps.siteCode = siteCode;
                    ps.ipLoopback = ipLoopback;
                    portMap.put(key, ps);
                }

                String d = description == null ? "" : description.toLowerCase();

                boolean isUsed
                        = !d.isEmpty()
                        && !d.contains("huawei")
                        && !d.contains("ethernet");
                boolean isRehoming = d.contains("reser") && d.contains("rehom");
                boolean isOLT = d.contains("reser") && d.contains("olt");
                boolean isReserved = d.contains("reser") && !isRehoming && !isOLT;

                if (maxBW.equals("1G")) {
                    ps.total1G++;
                    if (isUsed) {
                        ps.used1G++;
                    }
                    if (isRehoming) {
                        ps.reservedForRehoming++;
                    } else if (isOLT) {
                        ps.reservedForOLT++;
                    } else if (isReserved) {
                        ps.reserved1G++;
                    }
                } else if (maxBW.equals("10G")) {
                    ps.total10G++;
                    if (isUsed) {
                        ps.used10G++;
                    }
                    if (isRehoming) {
                        ps.reservedForRehoming++;
                    } else if (isOLT) {
                        ps.reservedForOLT++;
                    } else if (isReserved) {
                        ps.reserved10G++;
                    }
                } else if (maxBW.equals("100G")) {
                    ps.total100G++;
                    if (isUsed) {
                        ps.used100G++;
                    }
                    if (isRehoming) {
                        ps.reservedForRehoming++;
                    } else if (isOLT) {
                        ps.reservedForOLT++;
                    } else if (isReserved) {
                        ps.reserved100G++;
                    }
                }
            }

            portReader.close();

            for (PortSummary ps : portMap.values()) {
                int free1G = ps.total1G - ps.used1G;
                int free10G = ps.total10G - ps.used10G;
                int free100G = ps.total100G - ps.used100G;

                fw3.write(
                        ps.type + "," + ps.siteCode + "," + ps.ipLoopback + ","
                        + ps.total1G + "," + ps.used1G + "," + free1G + "," + ps.reserved1G + ","
                        + ps.total10G + "," + ps.used10G + "," + free10G + "," + ps.reserved10G + ","
                        + ps.total100G + "," + ps.used100G + "," + free100G + "," + ps.reserved100G + ","
                        + ps.reservedForRehoming + "," + ps.reservedForOLT + "\n"
                );
            }

            fw3.close();
            System.out.println("[INFO] Generated " + output_PORT);

            // 4) DataDescription_MB_xxx.csv reads from the first full LLDP CSV.
            LocalDateTime now4 = now3.plusSeconds(1);
            String formattedDateTime4 = now4.format(formatter);
            if (formattedDateTime4.equals(formattedDateTime3)) {
                now4 = now4.plusSeconds(1);
                formattedDateTime4 = now4.format(formatter);
            }

            File descriptionFile = DescriptionChecker.process(fullFile, new File(f.getLLDP()), formattedDateTime4);
            String output_DESCRIPTION = descriptionFile.getName();

            System.out.println(output_PORT);
            System.out.println(output_DESCRIPTION);
            System.out.println(output_LLDP_2);
            System.out.println(output_LLDP);
            System.out.println(TotalFile + " Node");
            Dialog.Success(
                    "Generated 4 Link Optical file(s)\n"
                    + output_PORT + "\n"
                    + output_DESCRIPTION + "\n"
                    + output_LLDP_2 + "\n"
                    + output_LLDP,
                    TotalFile
            );

        } catch (Exception ex) {
            System.out.println(ex.toString() + file_fail);
        }
    }

    private static String[] splitCsvLineSimple(String line) {
        List<String> cols = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cols.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        cols.add(sb.toString());

        return cols.toArray(new String[0]);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

   private static String detectType(String siteCode) {
    String s = nz(siteCode).toUpperCase();

    if (s.contains("-AC-") || s.startsWith("CPE-")) {
        return "CPE";
    }
    if (s.contains("-AG-")) {
        return "AG";
    }
    if (s.contains("-CO-")) {
        return "CO";
    }

    if (s.matches("^.+_[A-Z]{3}\\d{4}$")) {
        return "PN";
    }

    if (s.matches("^[A-Z0-9]{7}$")) {
        boolean hasAlpha = false;
        boolean hasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                hasAlpha = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else {
                return "";
            }
        }
        if (hasAlpha && hasDigit) {
            return "CPE";
        }
    }

    if (s.startsWith("AGN-")) {
        return "AGN";
    }
    if (s.matches("^DN\\d?-.*")) {
        return "DN";
    }
    if (s.matches("^PN\\d?-.*")) {
        return "PN";
    }
    if (s.matches("^RN\\d?-.*")) {
        return "RN";
    }
    if (s.matches("^AN\\d?-.*")) {
        return "AN";
    }

    return "";
}
    private static String normalizeBW(String iface, String bw) {
        String ifx = iface == null ? "" : iface.trim().toLowerCase();
        if (ifx.startsWith("gei-")) {
            return "1G";
        }
        if (ifx.startsWith("xgei-")) {
            return "10G";
        }
        if (ifx.startsWith("cgei-")) {
            return "100G";
        }

        if (bw == null) {
            return "";
        }

        String raw = bw.trim();
        if (raw.isEmpty()) {
            return "";
        }

        String b = raw.toUpperCase().replaceAll("\\s+", "");

        if (b.equals("1G") || b.equals("1GBPS")) {
            return "1G";
        }
        if (b.equals("10G") || b.equals("10GBPS")) {
            return "10G";
        }
        if (b.equals("100G") || b.equals("100GBPS")) {
            return "100G";
        }

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*([MGT])(?:BPS)?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(raw);

        if (m.find()) {
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2).toUpperCase();

            if ("M".equals(unit)) {
                return "1G";
            }

            if ("G".equals(unit)) {
                if (value < 10.0) {
                    return "1G";
                }
                if (value < 100.0) {
                    return "10G";
                }
                return "100G";
            }

            if ("T".equals(unit)) {
                return "100G";
            }
        }

        return "";
    }
}

