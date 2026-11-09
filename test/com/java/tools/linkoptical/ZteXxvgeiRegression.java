package com.java.tools.linkoptical;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Covers collected physical ports through all native report outputs. */
public final class ZteXxvgeiRegression {
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static String port(int n) { return "xxvgei-1/1/0/" + n; }
    private static String detail(String name, String state, String bw, int crc) {
        return name + " is " + state + ", ifindex: 8200\n"
                + "  BW " + bw + "\n  In_CRC_ERROR " + crc + "\n";
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("zte-xxvgei-");
        Path input = dir.resolve("[1]10.0.0.1_CPE-TEST0001_ZTE-LLDP-Link_OPTIC_2026-11-09.txt");
        String log = "CPE-TEST0001#terminal length 0\nCPE-TEST0001#show lldp neighbor\n"
                + "Local Interface Scope Chassis ID Port ID Holdtime System Name\n"
                + port(13) + " NB aabb.ccdd.eeff 9/2/5 120 DN-TEST0002\n"
                + "CPE-TEST0001#show version\nZXCTN 6120H-S\n"
                + "CPE-TEST0001#show opt brief\n"
                + port(13) + " 10G-40km-SFP+ 1550nm -9.4/[-15.8,-1.0]\n0.6/[-4.7,5.0] Normal\n"
                + port(17) + " 1G-15km-SFP 1310nm -18.7/[-23.0,-3.0]\n-5.5/[-9.5,-3.0] Normal\n"
                + port(15) + " offline\n"
                + "CPE-TEST0001#show interface\n"
                + detail(port(13), "up", "10 Gbit/s", 2) + "  Description: To_DN-TEST0002\n"
                + detail(port(17), "up", "1 Gbit/s", 3) + "  Description: Customer\n"
                + detail(port(17) + ".100", "up", "100 Mbit/s", 99)
                + detail(port(15), "administratively down", "25 Gbit/s", 7)
                + detail(port(35), "administratively down", "100 Gbit/s", 0)
                + detail("smartgroup2", "up", "10 Gbit/s", 88) + "  Description: To_DN-TEST0002\n"
                + "CPE-TEST0001#show lacp internal\nSmartgroup:2\n"
                + port(13) + "[FA* ACTIVE 30 32768 0x221 0x3d CURRENT COLL&DIST\n]\n"
                + "CPE-TEST0001#quit\n";
        Files.write(input, log.getBytes(StandardCharsets.UTF_8));
        Link_Optical.ProcessResult out = Link_Optical.processFiles(new File[]{input.toFile()}, dir.toFile(), false);
        List<String> full = Files.readAllLines(out.getFullLldpFile().toPath(), StandardCharsets.UTF_8);
        Map<String,String[]> rows = new LinkedHashMap<>();
        for (String line : full.subList(1,full.size())) {
            String[] f=line.split(",",-1); equal(22,f.length); rows.put(f[2],f);
        }
        equal(4,rows.size());
        equal("10G",rows.get(port(13))[7]); equal("40km",rows.get(port(13))[13]);
        equal("-9.40",rows.get(port(13))[15]); equal("smartgroup2",rows.get(port(13))[20]);
        equal("1G",rows.get(port(17))[7]); equal("15km",rows.get(port(17))[13]);
        equal("25G",rows.get(port(15))[7]);
        equal("100G",rows.get(port(35))[7]);
        List<String> neighbors=Files.readAllLines(out.getNeighborFile().toPath(),StandardCharsets.UTF_8);
        if (!String.join("\n",neighbors).contains(port(13))) throw new AssertionError("xxvgei link missing from neighbor export");
        List<String> ports=Files.readAllLines(out.getPortFile().toPath(),StandardCharsets.UTF_8);
        String[] keys=ports.get(0).split(","), values=ports.get(1).split(",");
        Map<String,String> counts=new LinkedHashMap<>();
        for(int i=0;i<keys.length;i++) counts.put(keys[i],values[i]);
        equal("1",counts.get("Total_1G")); equal("2",counts.get("Total_10G"));
        equal("1",counts.get("Free_10G"));
        equal("1",counts.get("Total_100G")); equal("1",counts.get("Free_100G"));
        for (LiveNodeHealthParser.PortSnapshot p : LiveNodeHealthParser.parsePorts("CPE-TEST0001","10.0.0.1","ZTE-LLDP-Link_OPTIC",log)) {
            if(p.port.equals(port(13))) { equal("10G",p.speed); equal(Long.valueOf(2),p.crcTotal); }
            if(p.port.equals(port(17))) { equal("1G",p.speed); equal(Long.valueOf(3),p.crcTotal); }
            if(p.port.equals(port(15))) { equal("10G",p.speed); equal("DOWN",p.portStatus); equal(Long.valueOf(7),p.crcTotal); }
            if(p.port.equals(port(35))) { equal("100G",p.speed); equal("DOWN",p.portStatus); }
        }
        List<LiveNodeHealthParser.PortSnapshot> brief=LiveNodeHealthParser.parsePorts("CPE-TEST0001","10.0.0.1","ZTE-LLDP-Link_OPTIC",
                "CPE-TEST0001#show interface brief\n"+port(15)+" optical Duplex/full 25G up down down\n");
        equal(1,brief.size()); equal("10G",brief.get(0).speed);
        for(String[] sample:new String[][]{{"1G","1G"},{"10G","10G"},{"25G","10G"},{"100G","100G"},{"100M","1G"},{"1000000000","1G"},{"",""},{"0G",""},{"40G",""}})
            equal(sample[1],Link_Optical.normalizeBW(port(15),sample[0]));
        AggregationMembership incomplete=new AggregationMembership(input.toString());
        incomplete.accept("CPE-TEST0001#show lacp internal");incomplete.accept("Smartgroup:2");
        incomplete.accept(port(13)+"[FA* ACTIVE 30 32768 0x221 0x3d CURRENT COLL&DIST");
        incomplete.accept("CPE-TEST0001#show interface");incomplete.accept("]");
        equal("",incomplete.fields(port(13))[0]);
        System.out.println("PASS ZteXxvgeiRegression: LLDP, Rx/SFP, LACP, live, and 25G-as-10G Free port");
    }
}
