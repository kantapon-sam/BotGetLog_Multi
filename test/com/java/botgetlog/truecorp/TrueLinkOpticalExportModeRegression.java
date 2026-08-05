package com.java.botgetlog.truecorp;

import java.io.File;
import java.io.FileOutputStream;

public final class TrueLinkOpticalExportModeRegression {

    private TrueLinkOpticalExportModeRegression() {
    }

    public static void main(String[] args) throws Exception {
        verifyExportModeParsing();
        verifySinceFileParsing();
        verifyModifiedSinceFilter();
        verifyCmdSetExtractionAfterVendorChange();
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
