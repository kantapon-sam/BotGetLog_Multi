package com.java.botgetlog.truecorp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Cold startup must validate retained logs from its already-open inventory. */
public final class DailyCollectionCacheRegression {
    private static final String SET = "HW-LLDP-Link_OPTIC";
    private static final String EMPTY_SET = "HW-EMPTY-LLDP-Link_OPTIC";
    private static final String MISSING_SET = "HW-MISSING-LLDP-Link_OPTIC";
    private static final String NODE = "CPE-CACHE-TEST";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void row(Sheet sheet, int index, String ip, String commandSet) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue("Y");
        row.createCell(2).setCellValue(NODE);
        row.createCell(3).setCellValue(ip);
        row.createCell(4).setCellValue(commandSet);
    }

    private static String transcript(boolean complete) {
        String prompt = "<" + NODE + ">";
        return prompt + "screen-length 0 temporary\n"
                + prompt + "display lldp neighbor\nNeighbor data\n"
                + prompt + "display version\nHuawei YunShan OS\n"
                + (complete ? prompt + "quit\n" : "");
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("daily-cache-regression-");
        Path budgetRoot = temp.resolve("System_Log/budget");
        Path logs = Files.createDirectories(temp.resolve("Total_Log"));
        String day = LocalDate.now(ZoneId.of("Asia/Bangkok")).toString();
        String[] ips = {"192.0.2.1", "192.0.2.2", "192.0.2.3", "192.0.2.4"};
        String[] sets = {SET, SET, EMPTY_SET, MISSING_SET};
        System.setProperty("true.daily.collection.dir", budgetRoot.toString());
        System.setProperty("true.daily.collection.logDir", logs.toString());
        System.clearProperty("true.daily.collection.pairsFile");
        SecurityManager previousSecurityManager = System.getSecurityManager();
        AtomicInteger workbookReads = new AtomicInteger();
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet devices = book.createSheet("deviceList_TRUE");
            devices.createRow(0);
            Sheet commands = book.createSheet("cmdSet");
            String[] values = {SET, "screen-length 0 temporary", "display lldp neighbor", "display version", "quit"};
            for (int i = 0; i < values.length; i++) commands.createRow(i).createCell(0).setCellValue(values[i]);
            commands.getRow(0).createCell(1).setCellValue(EMPTY_SET);
            DailyCollectionBudget.activate();
            DailyCollectionBudget budget = DailyCollectionBudget.active();
            budget.registerDailyOnce(Arrays.asList(ips), day);
            for (int i = 0; i < ips.length; i++) {
                row(devices, i + 1, ips[i], sets[i]);
                check(budget.start(ips[i], "PRIMARY"), "initial collection is admitted");
                Files.write(logs.resolve("[" + (i + 2) + "]" + ips[i] + "_" + NODE + "_" + sets[i] + "_" + day + ".txt"),
                        transcript(i != 1).getBytes(StandardCharsets.UTF_8));
            }
            // No disk workbook exists for this fixture. Fail even an attempted
            // lookup: a fallback catches IO/security errors and could otherwise
            // hide the performance regression by treating every node as incomplete.
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkRead(String file) {
                    if (file != null && file.endsWith("UserInterface_Input.xlsx")) {
                        workbookReads.incrementAndGet();
                        throw new SecurityException("Disk workbook access forbidden in cached selection");
                    }
                }
            });
            TrueLinkOpticalAutoMode.Selection selected = BotGetLog_TrueCorp.applyDailyBudgetWithWorkbookCache(
                    TrueLinkOpticalAutoMode.resolveSelection(new String[]{"--auto-link-optical", "--link-optical-all"}, devices), devices);
            check(!TrueLinkOpticalAutoMode.shouldRunRow(selected, devices.getRow(1)), "complete log is excluded at one attempt");
            check(TrueLinkOpticalAutoMode.shouldRunRow(selected, devices.getRow(2)), "incomplete log remains eligible");
            check(TrueLinkOpticalAutoMode.shouldRunRow(selected, devices.getRow(3)), "empty command set cannot prove completion");
            check(TrueLinkOpticalAutoMode.shouldRunRow(selected, devices.getRow(4)), "missing command set cannot prove completion");
            for (int i = 0; i < 5; i++) {
                check(budget.available(ips[1]), "repeated incomplete validation stays eligible");
                check(budget.available(ips[2]) && budget.available(ips[3]), "unknown boundaries stay fail-closed");
            }
            for (String ip : ips) check(budget.used(ip) == 1, "filtering does not consume another attempt");
            check(workbookReads.get() == 0, "selection and repeated validation must never reopen the workbook");
            // A subsequent inventory snapshot replaces command boundaries rather
            // than leaving an obsolete complete result in the command caches.
            commands.getRow(4).getCell(0).setCellValue("return");
            Path marker = budgetRoot.resolve(day).resolve("rx-completed").resolve(ips[0] + ".properties");
            Files.delete(marker);
            TrueLinkOpticalAutoMode.Selection changed = BotGetLog_TrueCorp.applyDailyBudgetWithWorkbookCache(
                    TrueLinkOpticalAutoMode.resolveSelection(new String[]{"--auto-link-optical", "--link-optical-all"}, devices), devices);
            check(TrueLinkOpticalAutoMode.shouldRunRow(changed, devices.getRow(1)), "new snapshot invalidates obsolete closing command");
            check(workbookReads.get() == 0, "new snapshot also uses the open workbook only");
            System.out.println("PASS DailyCollectionCacheRegression: complete/incomplete/empty/missing/snapshot refresh, diskWorkbookReads=0");
        } finally {
            System.setSecurityManager(previousSecurityManager);
            System.clearProperty("true.daily.collection.dir");
            System.clearProperty("true.daily.collection.logDir");
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.delete(path); }
                    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
            }
        }
    }
}
