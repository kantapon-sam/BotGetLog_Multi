package com.java.botgetlog.truecorp;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class NokiaArpCommandsRegression {
    public static void main(String[] args) {
        String discovery = "A:CPE-CBR0008# show service service-using vprn\n"
                + "ServiceId Type Adm Opr CustomerId Service Name\n"
                + "100 VPRN Up Up 1 VPN-A\n"
                + "200 VPRN Up Down 1 VPN-B\n"
                + "100 VPRN Up Up 1 VPN-A\n"
                + "300 VPLS Up Up 1 L2\n"
                + "Matching Services : 3\n";
        assertCommands(Arrays.asList("show router 100 arp", "show router 100 interface",
                "show router 200 arp", "show router 200 interface"), discovery);
        assertCommands(Collections.<String>emptyList(), null);
        assertCommands(Collections.<String>emptyList(), "100 VPRN Up Up 1 OLD\n");
        assertCommands(Collections.<String>emptyList(), discovery
                + "A:CPE-CBR0008# show service service-using vprn\nMatching Services : 0\n");
        assertCommands(Arrays.asList("show router 12345 arp", "show router 12345 interface"),
                discovery + "*A:CPE-CBR0008# show service service-using vprn\n"
                + "12345 VPRN Up Up 1 CustomerNameIsNotTheId\n"
                + "*A:CPE-CBR0008# show something-else\n999 VPRN Up Up 1 UNRELATED\n");
        System.out.println("PASS NokiaArpCommandsRegression");
    }

    private static void assertCommands(List<String> expected, String log) {
        List<String> actual = Telnet_Multi.buildNokiaVprnArpCommands(log);
        if (!expected.equals(actual)) throw new AssertionError("Commands: " + actual);
    }
}
