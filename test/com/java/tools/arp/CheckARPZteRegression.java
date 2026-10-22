package com.java.tools.arp;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;

/** Standalone regression coverage for the wrapped tables emitted by ZXCTN. */
public final class CheckARPZteRegression {
    private static final String NODE = "ZTE-TEST";
    private static final String MAC = "0200.0000.0001";
    private static final String PATH = "[1]192.0.2.254_ZTE-TEST_ZTE-ARP_2026-10-22.txt";
    private static final String HEADER =
            "IP                       Hardware                    Exter  Inter  Sub\n"
            + "Address         Age      Address        Interface    VlanID VlanID Interface\n"
            + "--------------------------------------------------------------------------------\n";

    public static void main(String[] args) throws Exception {
        numericSubinterfaceContinuation();
        dotSubinterfaceContinuation();
        independentInterfaceContinuations();
        vpnPrecedenceAndFallback();
        finalRowAtEof();
        commandAndRowBoundaries();
        System.out.println("PASS ZTE ARP: numeric/dot/independent wraps, logical VRF precedence, "
                + "port fallback, descriptions/status, absent VRF, EOF and command/row boundaries");
    }

    private static String preamble(String config) {
        return NODE + "#show version\nZXCTN 6120H-SC\n"
                + NODE + "#show running-config\n" + config
                + NODE + "#show arp\n11:21:19 BKK Thu Oct 22 2026\n"
                + "Arp protect whole is disabled\n" + HEADER;
    }

    private static String vrf(String iface, String vpn) {
        return "interface " + iface + "\n"
                + (vpn.isEmpty() ? "" : "  ip vrf forwarding " + vpn + "\n") + "$\n";
    }

    // Saved ZXCTN rows start their fields at columns
    // 0, 16, 25, 40, 53, 60 and 67 (zero based). Continuations retain 40/67.
    private static String row(String ip, String age, String iface, String vlan, String sub) {
        return String.format("%-16s%-9s%-15s%-13s%-7s%-7s%s\n",
                ip, age, MAC, iface, vlan, "N/A", sub);
    }

    private static String continuation(String iface, String sub) {
        return spaces(40) + String.format("%-27s", iface) + sub + "\n";
    }

    private static String spaces(int count) {
        char[] result = new char[count];
        Arrays.fill(result, ' ');
        return new String(result);
    }

    private static String descriptions(String rows) {
        return NODE + "#show interface description\n"
                + "Interface                      AdminStatus  PhyStatus  Protocol  Description\n"
                + rows + NODE + "#quit\n";
    }

    private static String description(String iface, String phy, String protocol, String text) {
        return iface + " up " + phy + " " + protocol + " " + text + "\n";
    }

    private static String expectedRow(String ip, String age, String port, String vpn,
            String phy, String protocol, String description) {
        return NODE + ",6120H-SC,192.0.2.254," + ip + "," + MAC + "," + age + ","
                + port + "," + vpn + "," + phy + "," + protocol + ",\""
                + description.replace("\"", "\"\"") + "\"";
    }

    private static void check(String scenario, String log, String... expected) throws Exception {
        String csv = CheckARP.Sub(new BufferedReader(new StringReader(log)), PATH).trim();
        String[] actual = csv.isEmpty() ? new String[0] : csv.split("\\r?\\n");
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(scenario + "\nExpected:\n" + String.join("\n", expected)
                    + "\nActual:\n" + csv);
        }
    }

    private static void numericSubinterfaceContinuation() throws Exception {
        // The logical interface is already complete; only the
        // physical subinterface wraps from .3 to .322 on a separate line.
        String log = preamble(vrf("supervlan322", "OAM-VRF"))
                + row("192.0.2.249", "H", "supervlan322", "N/A", "N/A")
                + row("192.0.2.252", "03:56:42", "supervlan322", "322", "gei-1/1/0/2.3")
                + continuation("", "22")
                + row("192.0.2.250", "00:07:31", "supervlan322", "322", "gei-1/1/0/1.3")
                + continuation("", "22")
                + descriptions(description("supervlan322", "up", "up", "Huawei OAM")
                        + description("gei-1/1/0/2.322", "up", "down", "Site B")
                        + description("gei-1/1/0/1.322", "up", "up", "Site A"));
        check("numeric subinterface continuation", log,
                expectedRow("192.0.2.249", "H", "supervlan322", "OAM-VRF", "up", "up", "Huawei OAM"),
                expectedRow("192.0.2.252", "03:56:42", "gei-1/1/0/2.322", "OAM-VRF", "up", "down", "Site B"),
                expectedRow("192.0.2.250", "00:07:31", "gei-1/1/0/1.322", "OAM-VRF", "up", "up", "Site A"));
    }

    private static void dotSubinterfaceContinuation() throws Exception {
        // .322 belongs to the Sub Interface column. Appending it to
        // supervlan322 would lose its configured VRF even if the port is fixed.
        String log = preamble(vrf("supervlan322", "OAM-VRF")
                + vrf("supervlan322.322", "WRONG-VRF"))
                + row("198.51.100.2", "01:35:22", "supervlan322", "322", "xgei-1/1/0/10")
                + continuation("", ".322")
                + descriptions(description("xgei-1/1/0/10.322", "up", "up", "3G U2100 Huawei OAM"));
        check("dot-only subinterface continuation", log,
                expectedRow("198.51.100.2", "01:35:22", "xgei-1/1/0/10.322", "OAM-VRF",
                        "up", "up", "3G U2100 Huawei OAM"));
    }

    private static void independentInterfaceContinuations() throws Exception {
        String log = preamble(vrf("xgei-1/1/0/26.2000", "TRANSIT"))
                + row("203.0.113.61", "H", "xgei-1/1/0/2", "2000", "N/A")
                + continuation("6.2000", "")
                + row("203.0.113.62", "00:03:42", "xgei-1/1/0/2", "2000", "xgei-1/1/0/26")
                + continuation("6.2000", ".2000")
                + descriptions(description("xgei-1/1/0/26.2000", "up", "up", "Transit, \"primary\""));
        check("independent logical and subinterface fragments", log,
                expectedRow("203.0.113.61", "H", "xgei-1/1/0/26.2000", "TRANSIT",
                        "up", "up", "Transit, \"primary\""),
                expectedRow("203.0.113.62", "00:03:42", "xgei-1/1/0/26.2000", "TRANSIT",
                        "up", "up", "Transit, \"primary\""));
    }

    private static void vpnPrecedenceAndFallback() throws Exception {
        String log = preamble(vrf("supervlan322", "LOGICAL-VRF")
                + vrf("gei-1/1/0/2.322", "PORT-VRF")
                + vrf("supervlan500", "") + vrf("gei-1/1/0/1.500", "FALLBACK-VRF")
                + vrf("gei-1/1/0/24", "DIRECT-VRF") + vrf("gei-1/1/0/23", ""))
                + row("192.0.2.1", "00:01:00", "supervlan322", "322", "gei-1/1/0/2.3")
                + continuation("", "22")
                + row("192.0.2.2", "00:02:00", "supervlan500", "500", "gei-1/1/0/1.5")
                + continuation("", "00")
                + row("192.0.2.3", "H", "gei-1/1/0/24", "N/A", "N/A")
                + row("192.0.2.4", "H", "gei-1/1/0/23", "N/A", "N/A")
                + descriptions(description("gei-1/1/0/2.322", "up", "up", "Logical wins")
                        + description("gei-1/1/0/1.500", "up", "up", "Port fallback")
                        + description("gei-1/1/0/24", "down", "down", "Direct service")
                        + description("gei-1/1/0/23", "up", "up", "Global route"));
        check("VRF precedence, port fallback and no-wrap rows", log,
                expectedRow("192.0.2.1", "00:01:00", "gei-1/1/0/2.322", "LOGICAL-VRF", "up", "up", "Logical wins"),
                expectedRow("192.0.2.2", "00:02:00", "gei-1/1/0/1.500", "FALLBACK-VRF", "up", "up", "Port fallback"),
                expectedRow("192.0.2.3", "H", "gei-1/1/0/24", "DIRECT-VRF", "down", "down", "Direct service"),
                expectedRow("192.0.2.4", "H", "gei-1/1/0/23", "", "up", "up", "Global route"));
    }

    private static void finalRowAtEof() throws Exception {
        String log = preamble(vrf("supervlan322", "OAM-VRF"))
                + row("192.0.2.5", "H", "supervlan322", "N/A", "N/A")
                + row("192.0.2.6", "00:01:02", "supervlan322", "322", "gei-1/1/0/2.3")
                + continuation("", "22");
        check("last wrapped row at EOF", log.substring(0, log.length() - 1),
                expectedRow("192.0.2.5", "H", "supervlan322", "OAM-VRF", "", "", ""),
                expectedRow("192.0.2.6", "00:01:02", "gei-1/1/0/2.322", "OAM-VRF", "", "", ""));
        check("single no-wrap row at EOF", preamble("")
                + row("192.0.2.7", "H", "gei-1/1/0/24", "N/A", "N/A"),
                expectedRow("192.0.2.7", "H", "gei-1/1/0/24", "", "", "", ""));
    }

    private static void commandAndRowBoundaries() throws Exception {
        String log = preamble(vrf("supervlan322", "OAM-VRF"))
                + row("192.0.2.8", "00:01:00", "supervlan322", "322", "gei-1/1/0/2.3")
                + continuation("", "22")
                + row("192.0.2.9", "H", "gei-1/1/0/24", "N/A", "N/A")
                + NODE + "#show unrelated\n" + continuation(".9999", "99")
                + row("198.51.100.99", "H", "gei-1/1/0/24", "N/A", "N/A")
                + NODE + "#show arp\n" + HEADER
                + continuation(".8888", "88")
                + row("192.0.2.10", "H", "gei-1/1/0/23", "N/A", "N/A")
                + NODE + "#quit\n";
        check("continuations cannot cross ARP row or command boundaries", log,
                expectedRow("192.0.2.8", "00:01:00", "gei-1/1/0/2.322", "OAM-VRF", "", "", ""),
                expectedRow("192.0.2.9", "H", "gei-1/1/0/24", "", "", "", ""),
                expectedRow("192.0.2.10", "H", "gei-1/1/0/23", "", "", "", ""));
    }
}
