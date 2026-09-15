package com.java.tools.linkoptical;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;

public final class AggregationMembershipRegression {
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static AggregationMembership parse(String vendor, String... lines) {
        AggregationMembership result = new AggregationMembership("[1]10.0.0.1_TEST_" + vendor + "-LLDP-Link_OPTIC_2026-09-15");
        for (String line : lines) result.accept(line);
        return result;
    }
    private static AggregationMembership file(String path) throws Exception {
        AggregationMembership result = new AggregationMembership(path);
        try (BufferedReader reader = result.reader(new FileReader(path))) { while (reader.readLine() != null) { } }
        return result;
    }
    public static void main(String[] args) throws Exception {
        AggregationMembership zte = file(args[0]), hw = file(args[1]), nokia = file(args[2]);
        equal("smartgroup1", zte.fields("xgei-1/1/0/30")[0]);
        equal("smartgroup2", zte.fields("xgei-1/1/0/25")[0]);
        equal("smartgroup3", zte.fields("xgei-1/1/0/26")[0]);
        for (String port : Arrays.asList("xgei-1/1/0/13", "xgei-1/1/0/14")) {
            equal("smartgroup21", zte.fields(port)[0]);
            equal("To_Gpon_OLT_ZTE_KKN07007G00_10.239.168.28", zte.fields(port)[1]);
            equal("ACTIVE / COLL&DIST", zte.fields(port)[2]);
        }
        equal("Eth-Trunk1", hw.fields("50|100GE3/0/0")[0]);
        equal("To_AGN-DNM-1_HCX16A_ETH-T20_10.85.161.51_MBAG", hw.fields("50|100GE3/0/0")[1]);
        equal("Eth-Trunk2", hw.fields("100GE1/0/0")[0]);
        equal("To_AGN-DNM-1_HCX16A_ETH-T21_10.185.19.51_BBAG", hw.fields("100GE1/0/0")[1]);
        equal("Eth-Trunk10", hw.fields("100GE8/0/0")[0]);
        equal("To_AN-DNM14-2_HCX8A_ETH-T10_10.85.159.48_MBAG", hw.fields("100GE8/0/0")[1]);
        equal("lag-121", nokia.fields("'1/1/3")[0]);
        equal("Active", nokia.fields("1/1/3")[2]);
        equal("", nokia.fields("1/1/3")[1]); // Port description is not a LAG description.
        equal("", zte.fields("xgei-1/1/0/1")[0]);
        AggregationMembership old = parse("ZTE", "smartgroup1 is up, ifindex: 1", "Description: To_MBAG",
                "xgei-1/1/0/30 is up, ifindex: 2", "Description: To_MBAG");
        equal("", old.fields("xgei-1/1/0/30")[0]);
        AggregationMembership inactive = parse("ZTE", "CPE-TEST#show lacp internal", "Smartgroup:21",
                "xgei-1/1/0/13[FA] INACTIVE 30 32768 0x1521 0x3d EXPIRED DETACHED",
                "CPE-TEST#show interface", "Smartgroup:99", "xgei-1/1/0/14[FA*] ACTIVE 30 32768 0x1521 0x3d CURRENT COLL&DIST");
        equal("INACTIVE / DETACHED", inactive.fields("xgei-1/1/0/13")[2]);
        equal("", inactive.fields("xgei-1/1/0/14")[0]);
        AggregationMembership standby = parse("N", "Port Admin Link Port Cfg Oper LAG/ Port Port",
                "1/1/c1/1 Up No Down 9212 9212 42 netw null", "Interface : 1/1/c1/1  Oper Speed : N/A",
                "Oper State : down - Standby in LAG 42  Config Duplex : N/A");
        equal("lag-42", standby.fields("'1/1/c1/1")[0]); equal("Standby", standby.fields("1/1/c1/1")[2]);
        AggregationMembership conflict = parse("HW", "Eth-Trunk1 current state : UP", "PortName Status Weight", "100GE1/0/0 UP 1",
                "Eth-Trunk2 current state : UP", "PortName Status Weight", "100GE1/0/0 DOWN 1");
        equal("", conflict.fields("100GE1/0/0")[0]); equal("CONFLICT", conflict.fields("100GE1/0/0")[2]);
        AggregationMembership quotes = parse("HW", "Eth-Trunk1 current state : UP", "Description: MB, \"primary\"", "PortName Status Weight", "100GE1/0/0 UP 1");
        String[] original = new String[20]; Arrays.fill(original, "x");original[4]="\"a,b\"";
        String row=String.join(",",original), enriched=quotes.append(row,"100GE1/0/0");
        equal("Group Interface", AggregationMembership.HEADER);
        equal(row+",Eth-Trunk1",enriched);
        equal(row+",NEIGHBOR,Eth-Trunk1",AggregationMembership.insertNeighborDescription(enriched,"NEIGHBOR"));
        equal(row+",", conflict.append(row,"100GE1/0/0"));
        System.out.println("AGGREGATION_MEMBERSHIP_OK: real ZTE/Huawei/Nokia, MB/BB trunk separation, subinterfaces, old logs, inactive members, command boundaries, conflicts and CSV escaping");
    }
}
