package com.java.tools.arp;

import java.io.BufferedReader;
import java.io.StringReader;

public final class CheckARPNokiaRegression {
    public static void main(String[] args) throws Exception {
        String log = command("show system information")
                + "System Name : IGNORED-SYSTEM-NAME\nName : IGNORED-NAME\nSystem Type : 7250 IXR-e\n"
                + command("show router arp")
                + arp("192.0.2.1", "shared")
                + command("show router interface")
                + "shared Up Up/Down Network 1/1/1\n   192.0.2.254/24 n/a\n"
                + command("show service service-using vprn")
                + "100 VPRN Up Up 1 Customer Alpha\n200 VPRN Up Up 1 Customer-B\n"
                + "Matching Services : 2\n"
                + command("show router 100 arp")
                + arp("198.51.100.1", "shared")
                + arp("198.51.100.2", "long-interface-name")
                + arp("198.51.100.3", "loopback-if")
                + arp("198.51.100.5", "Intf_nb_CBR0008_5GSA_2nd.33*")
                + arp("198.51.100.6", "ambiguous-*")
                + command("show router 100 interface")
                + "shared Up Down/Down VPRN 1/1/2:100\n   198.51.100.254/24 n/a\n"
                + "long-interface-name\n      Up Up/Down VPRN lag-2:100\n"
                + "   198.51.100.253/24 n/a\n"
                + "loopback-if Up Up/Down VPRN loopback\n"
                + "Intf_nb_CBR0008_5GSA_2nd.3340 Up Up/Down VPRN 1/1/19:3340\n"
                + "ambiguous-one Up Up/Down VPRN 1/1/4:100\n"
                + "ambiguous-two Up Up/Down VPRN 1/1/5:100\n"
                + "Interfaces : 3\n"
                + command("show router 200 arp") + arp("203.0.113.1", "shared")
                + command("show router 200 interface")
                + "shared Down Down/Down VPRN 1/1/3:200\n"
                + "Intf_nb_CBR0008_5GSA_2nd.3333 Up Down/Down VPRN 1/1/20:3333\n"
                + command("show router 999 arp") + arp("203.0.113.2", "shared")
                + command("show router 999 interface") + "Interfaces : 0\n"
                + command("show port description")
                + "1/1/1  Base uplink\n1/1/2  Customer port\n1/1/3  Other port\n"
                + "1/1/19  5GSA port\n"
                + command("show lag description") + "2(IEEE) Up Up Customer bundle\n"
                + command("show service id 100 arp")
                + "198.51.100.4 00:11:22:33:44:55 00h01m00s Dyn legacy 1/1/2:100\n"
                + command("logout");
        String actual = CheckARP.Sub(new BufferedReader(new StringReader(log)),
                "[1]10.163.192.105_IGNORED-FILENAME_N-ARP_2026-09-09.txt");
        assertRow(actual, "192.0.2.1", "'1/1/1,Base,Up,Up,\"Base uplink\"");
        assertRow(actual, "198.51.100.1", "'1/1/2:100,Customer Alpha,Up,Down,\"Customer port\"");
        assertRow(actual, "198.51.100.2", "lag2:100,Customer Alpha,Up,Up,\"Customer bundle\"");
        assertRow(actual, "198.51.100.3", "loopback,Customer Alpha,Up,Up,\"loopback-if\"");
        assertRow(actual, "198.51.100.5", "'1/1/19:3340,Customer Alpha,Up,Up,\"5GSA port\"");
        assertRow(actual, "198.51.100.6", "ambiguous-,Customer Alpha,,,\"ambiguous-\"");
        assertRow(actual, "203.0.113.1", "'1/1/3:200,Customer-B,Down,Down,\"Other port\"");
        assertRow(actual, "203.0.113.2", "shared,999,,,\"shared\"");
        assertRow(actual, "198.51.100.4", "'1/1/2:100,Customer Alpha,,,\"Customer port\"");
        if (actual.trim().split("\\r?\\n").length != 9) throw new AssertionError(actual);
        String legacyMetadata = CheckARP.Sub(new BufferedReader(new StringReader(
                log.replace("System Name :", "Name :").replace("System Type :", "Type :"))),
                "[1]10.163.192.105_IGNORED-FILENAME_N-ARP_2026-09-09.txt");
        if (!actual.equals(legacyMetadata)) throw new AssertionError("Legacy chassis metadata changed");
        System.out.println("PASS CheckARPNokiaRegression");
    }

    private static String command(String command) {
        return "*A:CPE-CBR0008# " + command + "\n";
    }

    private static String arp(String ip, String iface) {
        return ip + " 00:11:22:33:44:55 00h01m00s Dyn " + iface + "\n";
    }

    private static void assertRow(String actual, String ip, String expectedSuffix) {
        String expected = "CPE-CBR0008,7250 IXR-e,10.163.192.105," + ip
                + ",00:11:22:33:44:55,Dyn," + expectedSuffix;
        if (!actual.contains("\n" + expected)) throw new AssertionError("Missing: " + expected + "\n" + actual);
    }
}
