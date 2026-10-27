package com.java.tools.hwinterface;

import com.java.shared.HuaweiInterfaceCommands;
import com.java.botgetlog.truecorp.BotGetLog_TrueCorp;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HuaweiInterfaceReportRegression {
    private static final String HEADER = "Interface PHY Protocol InUti OutUti inErrors outErrors\n";
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("hw-interface-regression-");
        String brief = "<HW_TEST>display interface brief\n" + HEADER
                + "GE0/1/0(10G) up up 0.01% 1.23% 4294967296 0\n"
                + "GE0/1/0.100 up up -- -- 0 0\n"
                + "Eth-Trunk1 up down 1% 0% 2 3\n"
                + "  GigabitEthernet0/1/1 *down down 0% 0% 0 0\n"
                + "LoopBack0 ^down up(s) -- -- 0 0\n"
                + "Tunnel0/0/123456789\n up up 0.2% 0.3% 0 0\n";
        String details = "<HW_TEST>display interface\n"
                + "GigabitEthernet0/1/0 current state : UP\nLine protocol current state : UP\nDescription:Core, \"north\"\n"
                + "GigabitEthernet0/1/0.100 current state : UP\nDescription:Customer VLAN\n"
                + "Eth-Trunk1 current state : UP\nDescription:Bundle\n"
                + "GigabitEthernet0/1/1 current state : Administratively DOWN\nDescription:\n"
                + "LoopBack0 current state : UP\nDescription:=1+1\n"
                + "Tunnel0/0/123456789 current state : UP\nDescription:Tunnel test\n<HW_TEST>quit\n";
        File file = write(root, "[1]192.0.2.1_HW_TEST_HW-Interface_2026-10-27.txt", brief + details);
        HuaweiInterfaceReport.Snapshot result = HuaweiInterfaceReport.parse(file);
        equal(6, result.rows.size(), "all interfaces without alias duplicates");
        equal(0, result.warnings.size(), "complete log");
        equal("Core, \"north\"", result.rows.get(0).values[3], "description join");
        equal("4294967296", result.rows.get(0).values[8], "large counter stays exact");
        equal("--", result.rows.get(1).values[6], "unavailable stays unavailable");
        equal("*down", result.rows.get(3).values[4], "administrative down retained");
        equal("^down", result.rows.get(4).values[4], "standby flag retained");
        equal("up(s)", result.rows.get(4).values[5], "protocol flag retained");
        equal("Tunnel test", result.rows.get(5).values[3], "wrapped name");
        File old = write(root, "[1]192.0.2.1_HW_TEST_HW-Interface_2026-10-26.txt", brief.replace("1.23%", "99%") + details);
        old.setLastModified(System.currentTimeMillis() + 60000);
        File other = write(root, "[2]192.0.2.2_ZTE_TEST_ZTE-Interface_2026-10-27.txt", brief + details);
        HuaweiInterfaceReport.Result report = HuaweiInterfaceReport.processFiles(new File[]{old, file, other}, root.resolve("out").toFile());
        equal(1, report.nodes, "only Huawei; latest per IP");
        equal(6, report.rows.size(), "no older duplicates");
        equal("1.23%", report.rows.get(0).values[7], "mtime does not override collection date");
        String csv = new String(Files.readAllBytes(report.csv.toPath()), StandardCharsets.UTF_8);
        check(csv.startsWith("\uFEFFSite,IP node,Interface,Description,PHY,Protocol,InUti,OutUti,InErrors,OutErrors"), "Excel header/BOM");
        check(csv.contains("\"Core, \"\"north\"\"\""), "CSV quoting");
        check(csv.contains("\"'=1+1\""), "description stays text in spreadsheets");
        File partial = write(root, "[3]192.0.2.3_PARTIAL_HW-Interface_2026-10-27.txt", brief);
        check(!HuaweiInterfaceReport.parse(partial).warnings.isEmpty(), "partial log is flagged");
        File detailOnly = write(root, "[4]192.0.2.4_DETAIL_HW-Interface_2026-10-27.txt", details);
        HuaweiInterfaceReport.Snapshot detailResult = HuaweiInterfaceReport.parse(detailOnly);
        equal(6, detailResult.rows.size(), "detail-only rows retained");
        equal("", detailResult.rows.get(0).values[8], "no fabricated zero");
        File empty = write(root, "[5]192.0.2.5_EMPTY_HW-Interface_2026-10-27.txt", "<EMPTY>display interface brief\nError\n");
        try { HuaweiInterfaceReport.processFiles(new File[]{empty}, root.toFile()); throw new AssertionError("empty success"); }
        catch (java.io.IOException expected) { }
        try (org.apache.poi.ss.usermodel.Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("cmdSet");
            sheet.createRow(0).createCell(0).setCellValue("Custom-command");
            sheet.createRow(1).createCell(0).setCellValue("keep this command");
            check(com.java.build.HuaweiInterfaceWorkbook.addColumn(wb), "new template column added");
            equal("HW-Interface", sheet.getRow(0).getCell(1).getStringCellValue(), "command header");
            equal("display interface", sheet.getRow(3).getCell(1).getStringCellValue(), "detail command in template");
            equal("keep this command", sheet.getRow(1).getCell(0).getStringCellValue(), "existing commands retained");
            check(!com.java.build.HuaweiInterfaceWorkbook.addColumn(wb), "no duplicate columns");
        }
        java.lang.reflect.Method candidates = com.java.botgetlog.dtac.BotGetLog_DTAC.class
                .getDeclaredMethod("buildCmdSetCandidates", String.class);
        candidates.setAccessible(true);
        equal(java.util.Collections.singletonList("HW-Interface"), candidates.invoke(null, "HW-Interface"), "DTAC stays Huawei only");
        java.lang.reflect.Method boundaries = com.java.botgetlog.dtac.BotGetLog_DTAC.class
                .getDeclaredMethod("loadTaskBoundaryCommands", org.apache.poi.ss.usermodel.Workbook.class, java.util.List.class);
        boundaries.setAccessible(true);
        Object boundary = boundaries.invoke(null, null, java.util.Collections.singletonList("HW-Interface"));
        java.lang.reflect.Field last = boundary.getClass().getDeclaredField("lastCommands"); last.setAccessible(true);
        equal(java.util.Collections.singletonList("quit"), last.get(boundary), "DTAC old workbook completion boundary");
        String[] commands = new String[10];
        equal(5, BotGetLog_TrueCorp.copyCachedCommands("HW-Interface", commands), "old workbook uses built-in commands");
        equal("display interface brief", commands[2], "brief command");
        equal("display interface", commands[3], "detail command");
        equal("quit", BotGetLog_TrueCorp.getCachedLastCommand("HW-Interface"), "completion boundary");
        equal(4, BotGetLog_TrueCorp.getCachedCommandCount("HW-Interface"), "command count");
        check(HuaweiInterfaceCommands.permitsVendor("HW-Interface", "HW"), "Huawei allowed");
        check(!HuaweiInterfaceCommands.permitsVendor("HW-Interface", "ZTE"), "ZTE rejected");
        check(!HuaweiInterfaceCommands.permitsVendor("HW-Interface", "N"), "Nokia rejected");
        check(HuaweiInterfaceCommands.permitsVendor("HW-SID", "N"), "existing family mapping unchanged");
        System.out.println("HuaweiInterfaceReportRegression PASS (parser, CSV, latest snapshots, built-in commands, vendor guard)");
    }
    private static File write(Path root, String name, String text) throws Exception {
        Path file = root.resolve(name); Files.write(file, text.getBytes(StandardCharsets.UTF_8)); return file.toFile();
    }
    private static void equal(Object expected, Object actual, String reason) {
        if (!expected.equals(actual)) throw new AssertionError(reason + ": expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
