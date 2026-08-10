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
        verifyTrueAutoAddIpScope();
        verifyNeighborIdentityMatchesDescription();
        verifyCanonicalDeviceIdentityKey();
        verifyRecursiveNeighborDiscovery();
        verifyForeignNeighborAliasesNotDiscovered();
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

    private static void verifyTrueAutoAddIpScope() {
        for (String ip : Arrays.asList(
                "10.185.1.1",
                "10.85.1.1",
                "10.150.1.1",
                "10.163.1.1",
                "10.167.1.1",
                "10.207.1.1",
                "10.165.1.1")) {
            assertEquals("true", Boolean.toString(
                    TrueLinkOpticalInputUpdater.isAllowedTrueAutoAddIp(ip)));
        }
        for (String ip : Arrays.asList(
                "10.168.1.1",
                "10.186.1.1",
                "192.168.1.1",
                "10.1670.1.1",
                "invalid")) {
            assertEquals("false", Boolean.toString(
                    TrueLinkOpticalInputUpdater.isAllowedTrueAutoAddIp(ip)));
        }
    }

    private static void verifyNeighborIdentityMatchesDescription() {
        assertEquals("true", Boolean.toString(
                TrueLinkOpticalInputUpdater.descriptionMatchesDiscoveredNode(
                        "CPE-RNG0015_(RNG7115)",
                        "To_CPE-RNG0015_H910C-A_10G_10.167.223.116")));
        assertEquals("true", Boolean.toString(
                TrueLinkOpticalInputUpdater.descriptionMatchesDiscoveredNode(
                        "CPE-RNG0015_(RNG7115)",
                        "To_RNG7115_H910C-A_10G_10.167.223.116")));
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.descriptionMatchesDiscoveredNode(
                        "CPE-RNG0015_(RNG7115)",
                        "To_CPE-RNG6713_H910C-A_10G_10.167.223.117")));
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "RN-T15U-AC-R700-1")));
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "PG-PANO-AG-B740-2")));
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "CPE-BKA7535-AC-01")));
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "CPE-PANO001-AG-01")));
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "CPE-ERST000-CO-07")));
        for (String blocked : Arrays.asList(
                "TYB0001-NWCNCR-01",
                "BKK0022-BBCGNX-07",
                "SNK0001-MBCNER-02")) {
            assertEquals("true", Boolean.toString(
                    TrueLinkOpticalInputUpdater.isBlockedDiscoveredNodeName(blocked)));
            assertEquals("false", Boolean.toString(
                    TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(blocked)));
        }
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "RN-NKT0020-")));
        assertEquals("false", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "RN-CBR0208-")));
        assertEquals("true", Boolean.toString(
                TrueLinkOpticalInputUpdater.isEligibleDiscoveredNodeName(
                        "RN-CBR0208-2_CBICBI1402M")));
    }

    private static void verifyCanonicalDeviceIdentityKey() {
        assertEquals(
                TrueLinkOpticalInputUpdater.deviceIdentityKey("AN-BBT20-1_NTB03001S00"),
                TrueLinkOpticalInputUpdater.deviceIdentityKey("AN-BBT20-1-NTB03001S00"));
        assertEquals(
                TrueLinkOpticalInputUpdater.deviceIdentityKey("RN-CBR0208-2_CBICBI1402M"),
                TrueLinkOpticalInputUpdater.deviceIdentityKey("RN-CBR0208-2-CBICBI1402M"));
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

    private static void verifyForeignNeighborAliasesNotDiscovered() throws Exception {
        File csv = File.createTempFile("foreign-neighbor-aliases-", ".csv");
        try {
            Files.write(csv.toPath(), Arrays.asList(
                    "IP loopback,Description,Neighbor SysName,NeighborDes",
                    "10.167.223.117,To_CPE-RNG0015_(RNG7115)_H910C-A_1G_0/2/13_10.167.223.116,RN-T15U-AC-R700-1,",
                    "10.167.222.2,To_CPE-PNA1019_H910C-A_1G_0/2/12_10.167.222.1,PG-PANO-AG-B740-2,",
                    "10.85.81.17,To_Interconnect-CATMEGA_BANGNA(Main),SP-MGBS-AC-3800-1,",
                    "10.167.1.1,To_CPE-BKA7535-AC-01_10G_10.167.1.2,CPE-BKA7535-AC-01,",
                    "10.163.199.251,To_CPE-OUT0001_H910C-A_10G_0/2/5_192.168.1.10,CPE-OUT0001,",
                    "10.163.199.251,To_CPE-RYG0039_H910C-A_10G_0/2/4_10.163.199.249,CPE-RYG0039,"),
                    StandardCharsets.UTF_8);
            List<String> names = TrueLinkOpticalInputUpdater.readDiscoveredDeviceNamesForTesting(csv);
            assertEquals("CPE-RYG0039", String.join("|", names));
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
