package com.java.botgetlog.truecorp;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class MultiIpCollectionRegression {
    static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    public static void main(String[] args) throws Exception {
        Path plan = Files.createTempDirectory("multi-ip-test").resolve("groups.tsv");
        String a = "10.167.1.2", b = "10.85.1.3", c = "10.150.1.4";
        Files.write(plan, Arrays.asList("RN-A\t" + a + "\t" + b + "\t" + c), StandardCharsets.UTF_8);
        try (Workbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("deviceList_TRUE");
            sheet.createRow(0);
            for (String ip : Arrays.asList(c, b, a, a, "10.207.0.9")) {
                Row row = sheet.createRow(sheet.getLastRowNum() + 1);
                row.createCell(0).setCellValue("Y");
                row.createCell(2).setCellValue("RN-A");
                row.createCell(3).setCellValue(ip);
                row.createCell(4).setCellValue("Z-LLDP-Link_OPTIC");
            }
            MultiIpCollection policy = MultiIpCollection.load(plan, sheet);
            TrueLinkOpticalAutoMode.Selection selected = TrueLinkOpticalAutoMode.resolveSelection(
                    new String[]{"--auto-link-optical", "--link-optical-sites=#2"}, sheet);
            check(policy.route(selected, sheet, false, false).getCmdSetsByRow().keySet()
                    .equals(Collections.singleton(4)));
            check(policy.route(selected, sheet, false, true).getSiteCount() == 0);
            check(policy.route(selected, sheet, true, false).getSiteCount() == 3);
            check(policy.allowed(a) && !policy.allowed(b) && !policy.allowed(c));
            check(policy.allowed("10.207.0.9") && policy.rank(c) == 2);
            policy.unavailable(c); // a stale failure on a standby cannot advance the group.
            check(policy.allowed(a));
            policy.unavailable(a);
            check(!policy.allowed(a) && policy.allowed(b) && !policy.allowed(c));
            policy = MultiIpCollection.load(plan, sheet); // checkpoint in a separate JVM
            check(policy.allowed(b) && !policy.allowed(c));
            policy.unavailable(b);
            check(policy.allowed(c));
            policy.unavailable(c);
            check(policy.route(selected, sheet, false, false).getSiteCount() == 0);
            sheet.getRow(2).getCell(2).setCellValue("RN-B");
            check(!MultiIpCollection.load(plan, sheet).grouped(a));
        }
        check(Telnet_Multi.isNodeLoginPathFailure("[No login prompt before credentials - connection timed out]"));
        check(Telnet_Multi.isNodeLoginPathFailure("[Connection failed after password]"));
        for (String reason : Arrays.asList("[CmdSet missing]", "[Auth failed - SSH gateway]",
                "[Auth failed - account or IP blocked]", "[Incomplete log]", "[Connection failed]",
                "[Connection failed during session recovery before login prompt]")) {
            check(!Telnet_Multi.isNodeLoginPathFailure(reason));
        }
        System.out.println("PASS MultiIpCollectionRegression");
    }
}
