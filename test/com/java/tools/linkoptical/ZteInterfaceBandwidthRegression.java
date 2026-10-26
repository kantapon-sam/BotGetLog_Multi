package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ZteInterfaceBandwidthRegression {
    public static void main(String[] args) throws Exception {
        String log = "CPE-TEST0001#show version\nZXCTN 6120H-SC\n"
                + "ZTE ZXCTN Software, Version: ZXCTN 6120H-SC V5.10.20.20, Release software\n"
                + "CPE-TEST0001#show opt brief\n"
                + optic("xgei-1/1/0/26", "6G-10km-SFP")
                + optic("xgei-1/1/0/9", "10G-10km-SFP+")
                + optic("gei-1/1/0/5", "1G-10km-SFP")
                + optic("xgei-1/1/0/30", "10G-10km-SFP+")
                + "CPE-TEST0001#show interface\n"
                + port("xgei-1/1/0/26", "10 Gbit/s")
                + port("xgei-1/1/0/26.100", "100 Mbit/s")
                + port("xgei-1/1/0/9", "1 Gbit/s")
                + port("gei-1/1/0/5", "100 Mbit/s")
                + port("xgei-1/1/0/30", "")
                + port("smartgroup1", "100 Gbit/s")
                + "CPE-TEST0001#quit\n";
        Map<String, String[]> rows = parse(log);
        if (rows.size() != 4) throw new AssertionError("Physical ports only: " + rows.keySet());
        speed(rows, "xgei-1/1/0/26", "10G");
        speed(rows, "xgei-1/1/0/9", "1G");
        speed(rows, "gei-1/1/0/5", "100M");
        speed(rows, "xgei-1/1/0/30", "10G");
        for (String[] row : rows.values()) {
            if (!"up".equals(row[3]) || !"1310nm".equals(row[12])) throw new AssertionError("State/optics changed");
        }
        // Unit handling and state reset are independent of the transceiver label.
        for (String[] sample : new String[][] {{"2.5 Gbit/s", "2.5G"}, {"1000 Kbit/s", "1000Kbps"},
                {"1000000000 bit/s", "1000000000"}, {"0 Gbit/s", "6G"}}) {
            String single = "CPE-TEST0001#show opt brief\n" + optic("xgei-1/1/0/26", "6G-10km-SFP")
                    + "CPE-TEST0001#show interface\n" + port("xgei-1/1/0/26", sample[0]) + "CPE-TEST0001#quit\n";
            speed(parse(single), "xgei-1/1/0/26", sample[1]);
        }
        if (args.length > 0) {
            String saved = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])),
                    java.nio.charset.StandardCharsets.UTF_8);
            Map<String, String[]> savedRows = parse(saved);
            speed(savedRows, "xgei-1/1/0/26", "10G");
            speed(savedRows, "xgei-1/1/0/9", "1G");
            speed(savedRows, "xxvgei-1/1/0/32", "100M");
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0] + ".corrected.csv"),
                    Check_Link_Optical.Sub(new BufferedReader(new StringReader(saved)),
                            "[1]10.163.195.65_CPE-CCS8515_ZTE-LLDP-Link_OPTIC_2026-10-24.txt")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        System.out.println("PASS ZteInterfaceBandwidthRegression");
    }
    private static String optic(String port, String type) {
        return port + "  " + type + "  1310nm  -11.8/[-13.8,0.5]  -2.9/[-8.4,0.5]  Normal\n";
    }
    private static String port(String port, String bw) {
        return port + " is up, ifindex: 8219\n  Description: To_PN-TEST0002\n"
                + (bw.isEmpty() ? "" : "  BW " + bw + "\n");
    }
    private static Map<String, String[]> parse(String log) throws Exception {
        String csv = Check_Link_Optical.Sub(new BufferedReader(new StringReader(log)),
                "[1]10.0.0.1_CPE-TEST0001_ZTE-LLDP-Link_OPTIC_2026-10-26.txt");
        Map<String, String[]> rows = new LinkedHashMap<>();
        for (String line : csv.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(",", -1);
            if (fields.length != 20 || rows.put(fields[2], fields) != null) throw new AssertionError(line);
        }
        return rows;
    }
    private static void speed(Map<String, String[]> rows, String port, String expected) {
        if (!rows.containsKey(port) || !expected.equals(rows.get(port)[7])) {
            throw new AssertionError(port + " expected " + expected + " actual " + (rows.containsKey(port) ? rows.get(port)[7] : "missing"));
        }
    }
}
