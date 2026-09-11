package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LinkOpticalNokiaStateRegression {
    public static void main(String[] args) throws Exception {
        String log = "A:TEST#show version\nTiMOS-B-9.0.R8\n"
                + port("1/1/1", "Ethernet Interface", "Admin State : up\nOper State : up\n")
                + port("1/3/1", "GNSS Physical Interface", "Admin Status : up    Oper Status : up\n")
                + port("1/3/2", "GNSS Physical Interface", "Admin Status : up    Oper Status : down\n")
                + port("1/3/3", "GNSS Physical Interface", "Admin Status : down    Oper Status : up\n")
                + port("1/3/4", "GNSS Physical Interface", "Admin Status : up\nOper Status : down\n")
                + port("1/3/5", "GNSS Physical Interface", "Admin Status : up\n")
                + port("1/1/2", "Ethernet Interface", "Admin State : up\nOper State : down\n")
                + "A:TEST#logout\n";
        Map<String, String[]> rows = parse(log, "[1]10.0.0.1_TEST_N-LLDP-Link_OPTIC_2026-09-11.txt");
        if (rows.size() != 7) throw new AssertionError("Expected seven ports: " + rows.keySet());
        state(rows, "1/1/1", "up");
        state(rows, "1/3/1", "up");
        state(rows, "1/3/2", "down");
        state(rows, "1/3/3", "up");
        state(rows, "1/3/4", "down");
        state(rows, "1/3/5", "");
        state(rows, "1/1/2", "down");
        verifyLive(log);
        if (args.length > 0) {
            rows = parse(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8), args[0]);
            if (rows.size() != 9) throw new AssertionError("Expected nine saved-log ports");
            state(rows, "1/3/1", "up");
            state(rows, "1/1/1", "up");
            for (int i = 2; i <= 8; i++) state(rows, "1/1/" + i, "down");
            if (!"To_SKN0565_GPS1".equals(rows.get("1/3/1")[4])) throw new AssertionError("Description changed");
        }
        System.out.println("PASS LinkOpticalNokiaStateRegression");
    }

    private static void verifyLive(String log) {
        Map<String, String> states = new LinkedHashMap<>();
        for (LiveNodeHealthParser.PortSnapshot port : LiveNodeHealthParser.parsePorts(
                "TEST", "10.0.0.1", "N-LLDP-Link_OPTIC", log)) {
            states.put(port.port, port.portStatus);
        }
        String[][] expected = {{"1/1/1", "UP"}, {"1/3/1", "UP"}, {"1/3/2", "DOWN"},
            {"1/3/3", "UP"}, {"1/3/4", "DOWN"}, {"1/3/5", "UNKNOWN"}, {"1/1/2", "DOWN"}};
        for (String[] pair : expected) {
            if (!pair[1].equals(states.get(pair[0]))) throw new AssertionError("Live " + pair[0] + ": " + states.get(pair[0]));
        }
    }

    private static String port(String id, String type, String states) {
        return "A:TEST#show port " + id + " ethernet lldp remote-info\nNo remote peers found\n"
                + "A:TEST#show port " + id + "\n" + type + "\n"
                + "Description : Test_port\nInterface : " + id + "\n" + states + "Physical Link : Yes\n";
    }

    private static Map<String, String[]> parse(String log, String path) throws Exception {
        String csv = Check_Link_Optical.Sub(new BufferedReader(new StringReader(log)), path);
        Map<String, String[]> rows = new LinkedHashMap<>();
        for (String row : csv.split("\\r?\\n")) {
            if (row.trim().isEmpty()) continue;
            String[] cols = row.split(",", -1);
            if (cols.length != 20) throw new AssertionError("Unexpected columns: " + row);
            if (rows.put(cols[2].replace("'", ""), cols) != null) throw new AssertionError("Duplicate port");
        }
        return rows;
    }

    private static void state(Map<String, String[]> rows, String port, String expected) {
        String[] row = rows.get(port);
        if (row == null || !expected.equals(row[3])) {
            throw new AssertionError(port + ": expected [" + expected + "] got [" + (row == null ? "missing" : row[3]) + "]");
        }
    }
}
