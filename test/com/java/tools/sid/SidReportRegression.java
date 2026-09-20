package com.java.tools.sid;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class SidReportRegression {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sid-report-regression-");
        File huawei = log(root, "[27745]10.167.91.102_PN2-NKR0043_HW-SID_2026-09-20.txt",
                "<PN2-NKR0043>screen-length 0 temporary\n"
                + "<PN2-NKR0043>display current-configuration interface LoopBack\n"
                + "interface LoopBack11\n ip address 10.163.102.91 255.255.255.255\n isis prefix-sid index 2566\n#\n"
                + "interface LoopBack15\n ip address 10.163.112.91 255.255.255.255\n isis prefix-sid index 7266\n#\n"
                + "interface LoopBack19\n ip address 10.165.102.91 255.255.255.255\n isis prefix-sid index 4566\n#\n"
                + "interface LoopBack20\n ip address 10.167.91.102 255.255.255.255\n isis prefix-sid index 5102\n#\n"
                + "interface LoopBack99\n ip address 10.101.91.102 255.255.255.255\n#\n"
                + "interface LoopBack1023\n ip address 167.91.102.102 255.255.255.255\n#\n<PN2-NKR0043>\n");
        File nokia = log(root, "[27239]10.167.92.1_PN-SSK1332-1_SSKSSK0400M_N-SID_2026-09-20.txt",
                "A:PN-SSK1332-1_SSKSSK0400M#environment no more\n"
                + "A:PN-SSK1332-1_SSKSSK0400M#show router interface | match expression \"system|LB\" post-lines 1\n"
                + "LB11 Up Up Network\n  10.163.1.92/32\nLB15 Up Up Network\n  10.163.11.92/32\n"
                + "LB19 Up Up Network\n  10.165.1.92/32\nsystem Up Up Network\n  10.167.92.1/32\n"
                + "A:PN-SSK1332-1_SSKSSK0400M#admin display-config | match node-sid context all\n"
                + "interface \"LB11\"\n ipv4-node-sid index 2530\ninterface \"LB15\"\n ipv4-node-sid index 2530\n"
                + "interface \"LB19\"\n ipv4-node-sid index 7230\ninterface \"system\"\n ipv4-node-sid index 4530\n"
                + "A:PN-SSK1332-1_SSKSSK0400M#\n");
        File zte = log(root, "[27028]10.167.130.1_PN-CMI1000-1_CMICMI540ZW_ZTE-SID_2026-09-20.txt",
                "PN-CMI1000-1_CMICMI540ZW#terminal length 0\n"
                + "PN-CMI1000-1_CMICMI540ZW#show running-config | include prefix-sid\n"
                + "isis prefix-sid 10.163.1.130/32 index 2302\n"
                + "isis prefix-sid 10.163.11.130/32 index 7002\n"
                + "isis prefix-sid 10.165.1.130/32 index 4302\n"
                + "isis prefix-sid 10.167.130.1/32 index 5002\n"
                + "PN-CMI1000-1_CMICMI540ZW#show ip interface brief | include loopback\n"
                + "loopback11 10.163.1.130 up up\nloopback15 10.163.11.130 up up\n"
                + "loopback19 10.165.1.130 up up\nloopback20 10.167.130.1 up up\n"
                + "loopback99 10.101.130.1 up up\nPN-CMI1000-1_CMICMI540ZW#\n");

        assertRow(SidReport.parseLogFile(huawei), "PN2-NKR0043",
                "10.163.102.91", "2566", "10.163.112.91", "7266",
                "10.165.102.91", "4566", "10.167.91.102", "5102",
                "10.101.91.102", "", "167.91.102.102");
        assertRow(SidReport.parseLogFile(nokia), "PN-SSK1332-1_SSKSSK0400M",
                "10.163.1.92", "2530", "10.163.11.92", "2530",
                "10.165.1.92", "7230", "10.167.92.1", "4530", "", "", "");
        assertRow(SidReport.parseLogFile(zte), "PN-CMI1000-1_CMICMI540ZW",
                "10.163.1.130", "2302", "10.163.11.130", "7002",
                "10.165.1.130", "4302", "10.167.130.1", "5002",
                "10.101.130.1", "", "");

        Path output = root.resolve("out");
        SidReport.ProcessResult result = SidReport.processFiles(
                new File[]{zte, nokia, huawei}, output.toFile());
        check(result.getRows() == 3, "Expected one row per node");
        List<String> csv = Files.readAllLines(result.getOutputFiles().get(0).toPath(), StandardCharsets.UTF_8);
        check(csv.size() == 4, "Expected header plus three rows");
        check(csv.get(0).equals(SidReport.CSV_HEADER), "Unexpected SID CSV header");
        check(csv.stream().noneMatch(line -> line.contains("UNVERIFIED")), "Unexpected input row");
        System.out.println("PASS SidReportRegression (HW, ZTE, Nokia, blanks and requested column order)");
    }

    private static File log(Path root, String name, String body) throws Exception {
        Path file = root.resolve(name);
        Files.write(file, body.getBytes(StandardCharsets.UTF_8));
        return file.toFile();
    }

    private static void assertRow(SidReport.SidRow row, String node, String... expected) {
        List<String> actual = Arrays.asList(
                row.getAddress("11"), row.getSid("11"),
                row.getAddress("15"), row.getSid("15"),
                row.getAddress("19"), row.getSid("19"),
                row.getAddress("20"), row.getSid("20"),
                row.getAddress("99"), row.getAddress("98"), row.getAddress("1023"));
        check(row.getNode().equals(node), "Unexpected node: " + row.getNode());
        check(actual.equals(Arrays.asList(expected)), "Unexpected SID row for " + node + ": " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
