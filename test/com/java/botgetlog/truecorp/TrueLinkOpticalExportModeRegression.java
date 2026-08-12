package com.java.botgetlog.truecorp;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class TrueLinkOpticalExportModeRegression {

    private TrueLinkOpticalExportModeRegression() {
    }

    public static void main(String[] args) throws Exception {
        verifyExportModeParsing();
        verifySinceFileParsing();
        verifyModifiedSinceFilter();
        verifyCmdSetExtractionAfterVendorChange();
        verifyFailureLogClassification();
        verifyNodePromptIsolation();
        verifyAuthFailureIsolation();
        verifyLoginBannerDisconnectIsolation();
        verifySessionRecoveryFailureIsolation();
        verifyFailurePhaseTag();
        verifyExactRowSelection();
        verifySelectedLogIdentityUsesIpNotExcelRow();
        verifyThreadPoolHasNoHardCeiling();
        System.out.println("PASS TrueLinkOpticalExportModeRegression");
    }

    private static void verifyExportModeParsing() {
        assertEquals(TrueLinkOpticalAutoMode.ExportMode.FULL,
                TrueLinkOpticalAutoMode.getExportMode(new String[0]));
        assertEquals(TrueLinkOpticalAutoMode.ExportMode.FULL,
                TrueLinkOpticalAutoMode.getExportMode(new String[]{"--link-optical-export-mode=full"}));
        assertEquals(TrueLinkOpticalAutoMode.ExportMode.PRESCAN,
                TrueLinkOpticalAutoMode.getExportMode(new String[]{"--link-optical-export-mode=prescan"}));
        assertEquals(TrueLinkOpticalAutoMode.ExportMode.PRESCAN,
                TrueLinkOpticalAutoMode.getExportMode(new String[]{"--link-optical-export-mode=pre-scan"}));
        assertEquals(TrueLinkOpticalAutoMode.ExportMode.SKIP,
                TrueLinkOpticalAutoMode.getExportMode(new String[]{"--link-optical-export-mode=skip"}));

        boolean rejected = false;
        try {
            TrueLinkOpticalAutoMode.getExportMode(
                    new String[]{"--link-optical-export-mode=unexpected"});
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected, "invalid export mode must be rejected");
    }

    private static void verifySinceFileParsing() {
        File marker = TrueLinkOpticalAutoMode.getExportSinceFile(new String[]{
            "--link-optical-export-since-file=C:\\temp\\run-marker"
        });
        assertEquals(new File("C:\\temp\\run-marker").getPath(), marker.getPath());
        assertTrue(TrueLinkOpticalAutoMode.getExportSinceFile(new String[0]) == null,
                "missing since-file argument must return null");
    }

    private static void verifyModifiedSinceFilter() throws Exception {
        File temp = File.createTempFile("true-linkoptical-export-mode-", ".txt");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write('x');
        }
        long fileTime = System.currentTimeMillis() - 5000L;
        assertTrue(temp.setLastModified(fileTime), "test file timestamp must be set");
        assertTrue(TrueLinkOpticalAutoMode.isModifiedAtOrAfter(temp, fileTime),
                "file at marker timestamp must be included");
        assertTrue(TrueLinkOpticalAutoMode.isModifiedAtOrAfter(temp, fileTime - 1L),
                "file after marker timestamp must be included");
        assertTrue(!TrueLinkOpticalAutoMode.isModifiedAtOrAfter(temp, fileTime + 1L),
                "file before marker timestamp must be excluded");
        if (!temp.delete() && temp.exists()) {
            throw new AssertionError("Unable to delete temporary test file: " + temp);
        }
    }

    private static void verifyCmdSetExtractionAfterVendorChange() {
        assertEquals("N-LLDP-Link_OPTIC",
                TrueLinkOpticalAutoMode.extractLinkOpticalCmdSetFromFileName(
                        "[123]10.1.2.3_CPE-LPN0041_(LPN3006)_N-LLDP-Link_OPTIC_2026-08-05.txt"));
        assertEquals("HW-LLDP-Link_OPTIC",
                TrueLinkOpticalAutoMode.extractLinkOpticalCmdSetFromFileName(
                        "[124]10.1.2.4_DN-RYG4002-1-RYG0600300M_HW-LLDP-Link_OPTIC_2026-08-05.txt"));
        assertEquals("ZTE-LLDP-Link_OPTIC",
                TrueLinkOpticalAutoMode.extractLinkOpticalCmdSetFromFileName(
                        "[125]10.1.2.5_NODE_WITH_UNDERSCORE_ZTE-LLDP-Link_OPTIC_2026-08-05.txt"));
        assertEquals("",
                TrueLinkOpticalAutoMode.extractLinkOpticalCmdSetFromFileName(
                        "[126]10.1.2.6_NODE_OTHER-COMMAND_2026-08-05.txt"));
    }

    private static void verifyFailureLogClassification() {
        assertEquals("Node_NoLoginPrompt_20260809.txt",
                Telnet_Multi.failureLogFileNameForReason(
                        "[No login prompt before credentials]", "20260809"));
        assertEquals("Node_AuthFailed_20260809.txt",
                Telnet_Multi.failureLogFileNameForReason(
                        "[Auth failed - username or password rejected]", "20260809"));
        assertEquals("Node_IncompleteLog_20260809.txt",
                Telnet_Multi.failureLogFileNameForReason(
                        "[File too small - incomplete]", "20260809"));
        assertEquals("Node_ConnectionFailed_20260809.txt",
                Telnet_Multi.failureLogFileNameForReason(
                        "[Connection failed after login prompt]", "20260809"));
    }

    private static void verifyNodePromptIsolation() {
        String targetTimeout = "Trying to connect to 10.163.191.107, please wait...\n"
                + "connect telnet to login to 10.163.191.107\n"
                + "Trying 10.163.191.107...\n"
                + "telnet: connect to address 10.163.191.107: Connection timed out\n"
                + "Script done\nEnter IP address [press q/Q to quit]:";
        assertTrue(Telnet_Multi.containsTransportFailureText(targetTimeout),
                "target timeout must be recognized before credentials");
        assertTrue(!Telnet_Multi.containsAuthPromptText(targetTimeout),
                "the words 'login to' in gateway output are not a node login prompt");
        assertTrue(!Telnet_Multi.containsPasswordPromptText(targetTimeout),
                "target timeout must not be mistaken for a password prompt");
        assertTrue(Telnet_Multi.containsAuthPromptText("Username:"),
                "real username prompt must be recognized");
        assertTrue(Telnet_Multi.containsPasswordPromptText("Password:"),
                "real password prompt must be recognized");
    }

    private static void verifyAuthFailureIsolation() {
        String rejectedLogin = "Connected to 10.167.242.27.\n"
                + "Username:vdes2442@clls\nPassword:\n"
                + "Error: Username or password error.\nUsername:";
        assertTrue(Telnet_Multi.containsAuthPromptText(rejectedLogin),
                "connected node must expose a real authentication prompt");
        assertTrue(Telnet_Multi.containsLoginFailureText(rejectedLogin),
                "username/password rejection must be classified as authentication failure");
        assertTrue(!Telnet_Multi.containsTransportFailureText(rejectedLogin),
                "authentication failure must not be classified as no-login transport failure");
    }

    private static void verifyLoginBannerDisconnectIsolation() {
        String loginBannerDisconnect = "Connected to 10.167.106.3.\n"
                + "WARNING: Unauthorized access is forbidden.\n"
                + "Login: vdes2442@clls\nPassword:\n"
                + "Connection closed by foreign host.";
        assertTrue(Telnet_Multi.containsAuthPromptText(loginBannerDisconnect),
                "Login: prompt behind a vendor banner must be recognized");
        assertTrue(Telnet_Multi.containsPasswordPromptText(loginBannerDisconnect),
                "Password: prompt behind a vendor banner must be recognized");
        assertTrue(Telnet_Multi.containsTransportFailureText(loginBannerDisconnect),
                "post-password remote close must be recognized as a session failure");
        assertTrue(!Telnet_Multi.isTransportFailureBeforeAuthPrompt(loginBannerDisconnect),
                "a real Login/Password prompt must win over a later remote close");
        assertEquals("Node_ConnectionFailed_20260809.txt",
                Telnet_Multi.failureLogFileNameForReason(
                        "[Connection failed after password]", "20260809"));
    }

    private static void verifySessionRecoveryFailureIsolation() {
        String huaweiBanner = "Connected to 10.167.143.39.\n"
                + "WARNING: Unauthorized access is forbidden.\nUsername:";
        assertTrue(Telnet_Multi.containsAuthPromptText(huaweiBanner),
                "Huawei/ZTE Username prompt behind a warning banner must be recognized");
        assertTrue(Telnet_Multi.containsAuthPromptText("Login:"),
                "Nokia Login prompt must be recognized");
        assertTrue(Telnet_Multi.containsAuthPromptText("User name:"),
                "spaced user-name prompt must be recognized");
        assertTrue(Telnet_Multi.isTransportFailureBeforeAuthPrompt(
                "Trying 10.163.191.107... Connection timed out"),
                "a real initial transport timeout must remain a no-login failure");

        String initialReason = Telnet_Multi.loginPromptFailureReason(false);
        String recoveryReason = Telnet_Multi.loginPromptFailureReason(true);
        assertEquals("Node_NoLoginPrompt_20260810.txt",
                Telnet_Multi.failureLogFileNameForReason(initialReason, "20260810"));
        assertEquals("Node_ConnectionFailed_20260810.txt",
                Telnet_Multi.failureLogFileNameForReason(recoveryReason, "20260810"));
    }

    private static void verifyFailurePhaseTag() {
        assertEquals("PRIMARY_THREAD_20", Telnet_Multi.failurePhaseTag("primary-thread-20"));
        assertEquals("RETRY_THREAD_10", Telnet_Multi.failurePhaseTag(" retry thread 10 "));
        assertEquals("MANUAL", Telnet_Multi.failurePhaseTag(""));
    }

    private static void verifyExactRowSelection() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("deviceList_TRUE");
            org.apache.poi.ss.usermodel.Row similar = sheet.createRow(1);
            similar.createCell(2).setCellValue("CPE-ROW71-SIMILAR");
            similar.createCell(3).setCellValue("10.167.71.10");
            similar.createCell(4).setCellValue("N-LLDP-Link_OPTIC");

            org.apache.poi.ss.usermodel.Row exact = sheet.createRow(70);
            exact.createCell(2).setCellValue("CPE-EXACT0071");
            exact.createCell(3).setCellValue("10.167.1.71");
            exact.createCell(4).setCellValue("N-LLDP-Link_OPTIC");

            TrueLinkOpticalAutoMode.Selection selection = TrueLinkOpticalAutoMode.resolveSelection(
                    new String[]{"--auto-link-optical", "--link-optical-sites=#71"}, sheet);
            assertTrue(selection != null, "exact row selection must resolve");
            assertEquals(1, selection.getSiteCount());
            assertTrue(selection.includesRow(exact), "#71 must include only Excel row 71");
            assertTrue(!selection.includesRow(similar), "#71 must not compact-match another device or IP");
            assertTrue(TrueLinkOpticalAutoMode.shouldClearSelectedLogsBeforeRerun(selection),
                    "exact selected rerun must archive only its selected old row before recollection");
            assertTrue(TrueLinkOpticalAutoMode.shouldSkipGlobalDuplicateCleanup(
                    new String[]{"--link-optical-skip-global-dedup"}),
                    "targeted refresh must be able to preserve unrelated Total_Log duplicates");
            assertTrue(!TrueLinkOpticalAutoMode.shouldSkipGlobalDuplicateCleanup(
                    new String[]{"--auto-link-optical"}),
                    "normal scheduled runs must keep their existing duplicate-cleanup behavior");
        }
    }

    private static void verifySelectedLogIdentityUsesIpNotExcelRow() {
        Set<String> selectedIps = new LinkedHashSet<String>();
        selectedIps.add("10.167.1.71");

        File staleSameRow = new File(
                "[71]10.167.71.10_CPE-ROW71-SIMILAR_N-LLDP-Link_OPTIC_2026-08-11.txt");
        File selectedOldRow = new File(
                "[999]10.167.1.71_CPE-EXACT0071_N-LLDP-Link_OPTIC_2026-08-10.txt");
        File selectedWrongCommand = new File(
                "[999]10.167.1.71_CPE-EXACT0071_N-ARP_2026-08-10.txt");

        assertTrue(!TrueLinkOpticalAutoMode.isSelectedLinkOpticalLog(staleSameRow, selectedIps),
                "a reused Excel row must not archive another site's log");
        assertTrue(TrueLinkOpticalAutoMode.isSelectedLinkOpticalLog(selectedOldRow, selectedIps),
                "the selected IP must archive its old log even when its old Excel row differs");
        assertTrue(!TrueLinkOpticalAutoMode.isSelectedLinkOpticalLog(selectedWrongCommand, selectedIps),
                "selected cleanup must remain limited to Link Optical logs");
    }

    private static void verifyThreadPoolHasNoHardCeiling() {
        assertEquals(40, BotGetLog_TrueCorp.clampThreadPoolSize(40, 50000));
        assertEquals(100, BotGetLog_TrueCorp.clampThreadPoolSize(100, 50000));
        assertEquals(12, BotGetLog_TrueCorp.clampThreadPoolSize(100, 12));
        assertEquals(Telnet_Multi.NORMAL_TELNET_LIMIT,
                BotGetLog_TrueCorp.clampThreadPoolSize(0, 50000));
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
