package com.java.botgetlog.truecorp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.io.FileOutputStream;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class TrueLinkOpticalInputUpdaterRegression {

    private TrueLinkOpticalInputUpdaterRegression() {
    }

    public static void main(String[] args) throws Exception {
        verifyDestinationIpAfterUnderscore();
        verifySourceIpIsNotRediscovered();
        verifyGroupInference();
        verifyVendorCmdSetInference();
        verifyTrueAutoAddIpScope();
        verifyDiscoveryPolicy();
        verifyNeighborIdentityMatchesDescription();
        verifyCanonicalDeviceIdentityKey();
        verifyRecursiveNeighborDiscovery();
        verifyForeignNeighborAliasesNotDiscovered();
        verifyAtomicNextSiteQueue();
        verifySameNameDifferentDevices(false);
        verifySameNameDifferentDevices(true);
        System.out.println("PASS TrueLinkOpticalInputUpdaterRegression");
    }

    private static void verifyDestinationIpAfterUnderscore() {
        assertEquals("10.163.199.251", com.truelinkoptical.shared.DiscoveryPeer.destination(
                "10.167.33.41", "To_CPE-RYG0582_H910C-A_10G_0/2/3_10.163.199.251"));
    }

    private static void verifySourceIpIsNotRediscovered() {
        assertEquals("", com.truelinkoptical.shared.DiscoveryPeer.destination(
                "10.167.33.41", "To_DN-RYG2113_10.167.33.41"));
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
                "10.165.1.1")) {
            assertEquals("true", Boolean.toString(
                    TrueLinkOpticalInputUpdater.isAllowedTrueAutoAddIp(ip)));
        }
        for (String ip : Arrays.asList(
                "10.207.1.1",
                "10.168.1.1",
                "10.186.1.1",
                "192.168.1.1",
                "10.1670.1.1",
                "invalid")) {
            assertEquals("false", Boolean.toString(
                    TrueLinkOpticalInputUpdater.isAllowedTrueAutoAddIp(ip)));
        }
    }

    private static void verifyDiscoveryPolicy() {
        com.truelinkoptical.shared.AutoAddIpPolicy policy = new com.truelinkoptical.shared.AutoAddIpPolicy(
                "0.0.0.0/0", "10.207/16,10.163.5.128/25,10.85.0.1");
        for (String ip : Arrays.asList("10.207.255.255", "10.163.5.128", "10.85.0.1"))
            assertEquals("false", Boolean.toString(policy.allows(ip)));
        for (String ip : Arrays.asList("10.163.5.127", "10.85.0.2", "192.168.7.1"))
            assertEquals("true", Boolean.toString(policy.allows(ip)));
        String desc = "To_RN3-PSN0013_HCX16A_10G_2/1/18_10.163.0.163";
        assertEquals("true", Boolean.toString(com.truelinkoptical.shared.DiscoveryPeer.confirms(
                "RN-PSN0013-3_PLKPLK1004W", desc, "UP")));
        for (String wrong : Arrays.asList("", "RN-PSN0013-2_PLKPLK1002M", "RN-PSN0013-30_X"))
            assertEquals("false", Boolean.toString(com.truelinkoptical.shared.DiscoveryPeer.confirms(wrong, desc, "UP")));
        assertEquals("10.163.0.163", com.truelinkoptical.shared.DiscoveryPeer.destination("10.167.168.1", desc));
        assertEquals("", com.truelinkoptical.shared.DiscoveryPeer.destination("10.167.168.1", desc + "_10.163.0.164"));
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

    private static void verifySameNameDifferentDevices(boolean existingZte) throws Exception {
        final Path dir = Files.createTempDirectory("auto-input-hostname-collision-");
        final Path input = dir.resolve("UserInterface_Input.xlsx");
        File discovery = dir.resolve("quality.csv").toFile();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("deviceList_TRUE");
            Row header = sheet.createRow(0);
            String[] columns = {"Run", "Group", "Device-Name", "Loopback-IP", "CmdSet-1"};
            for (int i = 0; i < columns.length; i++) header.createCell(i).setCellValue(columns[i]);
            if (existingZte) {
                Row row = sheet.createRow(1);
                String[] values = {"Y", "CPE", "CPE-SSK0075", "10.167.119.215", "ZTE-LLDP-Link_OPTIC"};
                for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            }
            try (FileOutputStream out = new FileOutputStream(input.toFile())) { workbook.write(out); }
        }
        Files.write(discovery.toPath(), Arrays.asList(
                "CATEGORY,TARGET,TARGET_HAS_SITE_CODE,TARGET_IP_CANDIDATE,SOURCE_IP,DESCRIPTION,DETAIL",
                "ONE_WAY_LLDP_PAIR,CPE-SSK0075,false,10.167.119.215,10.167.1.1,To_CPE-SSK0075_ZXCTN_10.167.119.215,ZTE ZXCTN 6120H-SC",
                "ONE_WAY_LLDP_PAIR,CPE-SSK0075,false,10.167.120.112,10.167.1.1,To_CPE-SSK0075_H910C-A_10.167.120.112,Huawei ATN910C-A",
                "ONE_WAY_LLDP_PAIR,CPE-SSK0075,false,10.167.120.112,10.167.1.2,To_CPE-SSK0075_H910C-A_10.167.120.112,Huawei ATN910C-A"),
                StandardCharsets.UTF_8);
        Path reports = dir.resolve("_output/System_Log");
        Files.createDirectories(reports);
        Path previous = reports.resolve("node-userinput-sync-20260810-190000.csv");
        Files.write(previous, Arrays.asList("action,deviceName,ip,sourceSite,sourceIp,detail",
                "removed-unreachable-orphan,CPE-OLD9999,10.167.8.4,,,No login prompt",
                "removed-duplicate-device,RN-OLD9999,10.167.8.5,,,baseMac=00:11:22:33:44:55 keeper=RN-OLD9999"),
                StandardCharsets.UTF_8);
        Files.write(discovery.toPath(), Arrays.asList(
                "ONE_WAY_LLDP_PAIR,CPE-OLD9999,false,10.167.8.4,10.167.1.1,To_CPE-OLD9999_10.167.8.4,Huawei",
                "ONE_WAY_LLDP_PAIR,RN-OLD9999,false,10.167.8.5,10.167.1.1,To_RN-OLD9999_10.167.8.5,Nokia"),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        PathFile paths = new PathFile() {
            @Override public String getCurrentFolder() { return dir.toString(); }
            @Override public String getUserInterface_Input() { return input.toString(); }
        };
        TrueLinkOpticalInputUpdater.UpdateResult result = TrueLinkOpticalInputUpdater.updateFromDataQualityFile(paths, discovery);
        assertEquals("true", Boolean.toString(result.successful));
        assertEquals(existingZte ? "1" : "2", Integer.toString(result.added));
        assertEquals("0", Integer.toString(result.duplicateDevice));
        assertEquals("1", Integer.toString(result.duplicateInRun));
        assertEquals(existingZte ? "#3" : "#2|#3", String.join("|", result.addedRowSelectors));
        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(input))) {
            Sheet sheet = workbook.getSheet("deviceList_TRUE");
            assertEquals("2", Integer.toString(sheet.getLastRowNum()));
            assertEquals("10.167.119.215", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("ZTE-LLDP-Link_OPTIC", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("10.167.120.112", sheet.getRow(2).getCell(3).getStringCellValue());
            assertEquals("HW-LLDP-Link_OPTIC", sheet.getRow(2).getCell(4).getStringCellValue());
            TrueLinkOpticalAutoMode.Selection selection = TrueLinkOpticalAutoMode.resolveSelection(new String[]{
                "--auto-link-optical", "--link-optical-sites=" + String.join(",", result.addedRowSelectors)}, sheet);
            assertEquals(existingZte ? "1" : "2", Integer.toString(selection.getSiteCount()));
            assertEquals(Boolean.toString(!existingZte), Boolean.toString(selection.includesRow(sheet.getRow(1))));
            assertEquals("true", Boolean.toString(selection.includesRow(sheet.getRow(2))));
        }
        Files.delete(previous);
        try (Workbook saved = new XSSFWorkbook(Files.newInputStream(input))) {
            com.truelinkoptical.shared.DiscoveryHistory history = new com.truelinkoptical.shared.DiscoveryHistory(saved);
            assertEquals("previously verified duplicate device",
                    history.blockedReason("10.167.8.5", true, "RENAMED|N|00:11:22:33:44:55"));
            assertEquals("", history.blockedReason("10.167.8.5", true, "RENAMED|N|00:11:22:33:44:56"));
            assertEquals("", history.blockedReason("10.167.8.4", true, ""));
        }
        TrueLinkOpticalInputUpdater.UpdateResult repeated = TrueLinkOpticalInputUpdater.updateFromDataQualityFile(paths, discovery);
        assertEquals("0", Integer.toString(repeated.added));
        assertEquals("3", Integer.toString(repeated.duplicateIp));
        assertEquals("0", Integer.toString(repeated.addedRowSelectors.size()));
    }

    private static void assertEquals(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }
}
