package com.java.botgetlog.truecorp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class DailyCollectionSelectionRegression {
    private static String selectionOutput;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("daily-selection-");
        System.setProperty("true.daily.collection.dir", root.resolve("budget").toString());
        DailyCollectionBudget.activate();
        DailyCollectionBudget budget = DailyCollectionBudget.active();
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("deviceList_TRUE");
            sheet.createRow(0);
            row(sheet, 1, "CPE-A", "10.0.0.1");
            row(sheet, 2, "DN-B", "10.0.0.2");
            row(sheet, 3, "CPE-C", "10.0.0.3");
            row(sheet, 4, "DN-D", "10.0.0.4");
            row(sheet, 5, "CPE-C-ALIAS", "10.0.0.3");
            for (int i=0; i<3; i++) budget.start("10.0.0.1", "PRIMARY_CPU_RETRY");
            Path pairs=root.resolve("pairs.tsv");
            Files.write(pairs, "10.0.0.1\t10.0.0.2\n".getBytes(StandardCharsets.UTF_8));
            System.setProperty("true.daily.collection.pairsFile", pairs.toString());
            TrueLinkOpticalAutoMode.Selection blocked = select(sheet, "#2,#3");
            check(blocked.getSiteCount()==0, "both exhausted-pair endpoints must be excluded");
            check(selectionOutput.contains("DEFER selected Row 2 10.0.0.1; reason=PAIR_RESERVATION_BLOCKED used=3/3")
                    && selectionOutput.contains("DEFER selected Row 3 10.0.0.2; reason=PAIR_RESERVATION_BLOCKED used=0/3"),
                    "pair diagnostics explain both endpoints without implying both reached the cap");
            Path logs=Files.createDirectory(root.resolve("logs"));
            Path old=logs.resolve("[2]10.0.0.1_CPE-A_N-LLDP-Link_OPTIC_2026-09-10.txt");
            Files.write(old, "retain me".getBytes(StandardCharsets.UTF_8));
            check(TrueLinkOpticalAutoMode.clearSelectedLogsBeforeRerun(logs.toFile(), blocked, sheet)==0,
                    "deferred selection must not archive old logs");
            check(Files.exists(old), "old log must remain");
            check(budget.used("10.0.0.2")==0, "deferred peer must not consume a slot");
            Files.write(pairs, "10.0.0.3\t10.0.0.4\n".getBytes(StandardCharsets.UTF_8));
            TrueLinkOpticalAutoMode.Selection admitted=select(sheet, "#4,#5,#6");
            check(admitted.getSiteCount()==2, "duplicate IP and command must be collected once");
            check(budget.used("10.0.0.3")==1 && budget.used("10.0.0.4")==1, "reserve both endpoints");
            check(budget.start("10.0.0.3", "PRIMARY") && budget.start("10.0.0.4", "PRIMARY"), "consume reservation");
            check(budget.used("10.0.0.3")==1, "must not double-charge collection");
            check(select(sheet, "#4,#5").getSiteCount()==0, "pair must not be selected twice in one day");
            System.clearProperty("true.daily.collection.pairsFile");
            TrueLinkOpticalAutoMode.Selection all=applyAndCapture(sheet,
                    new String[]{"--auto-link-optical", "--link-optical-all"});
            check(!TrueLinkOpticalAutoMode.shouldRunRow(all,sheet.getRow(1)), "all-sites must honor exhausted quota");
            check(TrueLinkOpticalAutoMode.shouldRunRow(all,sheet.getRow(2)), "unexhausted peer is allowed in next primary");
            check(selectionOutput.contains("DEFER selected Row 2 10.0.0.1; reason=DAILY_LIMIT used=3/3"),
                    "standalone exhausted selection reports daily limit");
            String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).toString();
            budget.registerDailyOnce(java.util.Collections.singletonList("10.0.0.2"),today);
            check(budget.start("10.0.0.2","PRIMARY"),"completion diagnostic first attempt");
            Path marker=root.resolve("budget").resolve(today).resolve("rx-completed/10.0.0.2.properties");
            Files.createDirectories(marker.getParent());
            Files.write(marker,"complete=1\n".getBytes(StandardCharsets.UTF_8));
            check(select(sheet,"#3").getSiteCount()==0,"completed Rx node remains excluded");
            check(selectionOutput.contains("DEFER selected Row 3 10.0.0.2; reason=RX_LOG_ALREADY_COMPLETE used=1/3")
                    && selectionOutput.contains("day="+today+"; existing logs retained"),
                    "selection distinguishes retained completed log at 1/3 from exhausted allowance");
            check(budget.used("10.0.0.2")==1,"selection diagnostics do not charge completed node");
        }
        System.out.println("PASS DailyCollectionSelectionRegression");
    }
    private static TrueLinkOpticalAutoMode.Selection select(Sheet sheet, String rows) throws Exception {
        return applyAndCapture(sheet,new String[]{"--auto-link-optical", "--link-optical-sites="+rows});
    }
    private static TrueLinkOpticalAutoMode.Selection applyAndCapture(Sheet sheet,String[] args) throws Exception {
        java.io.PrintStream previous=System.out;
        java.io.ByteArrayOutputStream captured=new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream output=new java.io.PrintStream(captured,true,"UTF-8")) {
            System.setOut(output);
            return TrueLinkOpticalAutoMode.applyDailyBudget(TrueLinkOpticalAutoMode.resolveSelection(args,sheet),sheet);
        } finally {
            System.setOut(previous);
            selectionOutput=captured.toString("UTF-8");
            previous.print(selectionOutput);
        }
    }
    private static void row(Sheet sheet,int index,String name,String ip) {
        Row row=sheet.createRow(index);
        row.createCell(0).setCellValue("Y");
        row.createCell(2).setCellValue(name);
        row.createCell(3).setCellValue(ip);
        row.createCell(4).setCellValue("N-LLDP-Link_OPTIC");
    }
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }
}
