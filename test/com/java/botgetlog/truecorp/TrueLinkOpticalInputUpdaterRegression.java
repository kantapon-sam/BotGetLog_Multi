package com.java.botgetlog.truecorp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TrueLinkOpticalInputUpdaterRegression {

    private TrueLinkOpticalInputUpdaterRegression() {
    }

    public static void main(String[] args) throws Exception {
        verifyDestinationIpAfterUnderscore();
        verifySourceIpIsNotRediscovered();
        verifyGroupInference();
        verifyVendorCmdSetInference();
        verifyRecursiveNeighborDiscovery();
        verifyAtomicNextSiteQueue();
        System.out.println("PASS TrueLinkOpticalInputUpdaterRegression");
    }

    private static void verifyDestinationIpAfterUnderscore() {
        assertEquals("10.163.199.251",
                TrueLinkOpticalInputUpdater.extractDestinationIp(
                        "10.167.33.41",
                        "To_CPE-RYG0582_H910C-A_10G_0/2/3_10.163.199.251",
                        "CPE-RYG0582",
                        "CPE-RYG0582"));
    }

    private static void verifySourceIpIsNotRediscovered() {
        assertEquals("",
                TrueLinkOpticalInputUpdater.extractDestinationIp(
                        "10.167.33.41",
                        "To_DN-RYG2113_10.167.33.41",
                        "DN-RYG2113-1_RYG0600100M",
                        "DN-RYG2113-1_RYG0600100M"));
    }

    private static void verifyGroupInference() {
        assertEquals("CPE", TrueLinkOpticalInputUpdater.inferGroup("CPE-RYG0582"));
        assertEquals("DN", TrueLinkOpticalInputUpdater.inferGroup("DN-RYG2113-1_RYG0600100M"));
        assertEquals("PN", TrueLinkOpticalInputUpdater.inferGroup("PN2-YLA1017"));
    }

    private static void verifyVendorCmdSetInference() {
        assertEquals("HW-LLDP-Link_OPTIC",
                TrueLinkOpticalInputUpdater.inferCmdSet(
                        "CPE-RYG0582",
                        "To_CPE-RYG0582_H910C-A_10G_0/2/3_10.163.199.251",
                        "Huawei ATN 910C-A"));
        assertEquals("ZTE-LLDP-Link_OPTIC",
                TrueLinkOpticalInputUpdater.inferCmdSet(
                        "CPE-TEST0001", "To_CPE-TEST0001_ZXCTN", "ZTE"));
        assertEquals("N-LLDP-Link_OPTIC",
                TrueLinkOpticalInputUpdater.inferCmdSet(
                        "CPE-TEST0002", "To_CPE-TEST0002_7750", "Nokia"));
    }

    private static void verifyRecursiveNeighborDiscovery() throws Exception {
        File csv = File.createTempFile("ryg0582-to-ryg0039-", ".csv");
        try {
            Files.write(csv.toPath(), Arrays.asList(
                    "IP loopback,Description,Neighbor SysName,NeighborDes",
                    "10.163.199.251,To_CPE-RYG0039_H910C-A_10G_0/2/4_10.163.199.249,CPE-RYG0039,"),
                    StandardCharsets.UTF_8);
            List<String> names = TrueLinkOpticalInputUpdater.readDiscoveredDeviceNamesForTesting(csv);
            assertEquals("CPE-RYG0039", names.size() == 1 ? names.get(0) : "");
        } finally {
            Files.deleteIfExists(csv.toPath());
        }
    }

    private static void verifyAtomicNextSiteQueue() throws Exception {
        File dir = Files.createTempDirectory("new-site-queue-").toFile();
        File queue = new File(dir, "queue.txt");
        try {
            TrueLinkOpticalAutoMode.writeNewSiteQueue(queue,
                    Arrays.asList("CPE-RYG0039", "CPE-RYG0039", "", "CPE-RYG0040"));
            List<String> lines = Files.readAllLines(queue.toPath(), StandardCharsets.UTF_8);
            assertEquals("CPE-RYG0039|CPE-RYG0040", String.join("|", lines));

            TrueLinkOpticalAutoMode.writeNewSiteQueue(queue, Collections.<String>emptyList());
            if (!queue.isFile() || queue.length() != 0L) {
                throw new AssertionError("Queue closure must produce an existing empty file.");
            }

            File parsed = TrueLinkOpticalAutoMode.getNewSiteQueueFile(new String[]{
                "--link-optical-new-site-queue-file=" + queue.getAbsolutePath()
            });
            assertEquals(queue.getAbsolutePath(), parsed == null ? "" : parsed.getAbsolutePath());
        } finally {
            Files.deleteIfExists(queue.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }
}
