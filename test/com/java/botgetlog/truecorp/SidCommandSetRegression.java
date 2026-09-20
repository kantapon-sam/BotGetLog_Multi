package com.java.botgetlog.truecorp;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public final class SidCommandSetRegression {
    public static void main(String[] args) throws Exception {
        Path workbook = Paths.get(args.length == 0 ? "UserInterface_Input.xlsx" : args[0]);
        assertCommands(workbook, "HW-SID", Arrays.asList(
                "screen-length 0 temporary",
                "display current-configuration interface LoopBack",
                "quit"));
        assertCommands(workbook, "ZTE-SID", Arrays.asList(
                "terminal length 0",
                "show running-config | include prefix-sid",
                "show ip interface brief | include loopback",
                "quit"));
        assertCommands(workbook, "N-SID", Arrays.asList(
                "environment no more",
                "show router interface | match expression \"system|LB\" post-lines 1",
                "admin display-config | match node-sid context all",
                "logout"));
        System.out.println("PASS SidCommandSetRegression");
    }

    private static void assertCommands(Path path, String set, List<String> expected) throws Exception {
        try (InputStream in = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheet("cmdSet");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int column = -1;
            for (Cell cell : sheet.getRow(0)) {
                if (set.equalsIgnoreCase(formatter.formatCellValue(cell).trim())) {
                    column = cell.getColumnIndex();
                }
            }
            check(column >= 0, "Missing command set " + set);
            for (int i = 0; i < expected.size(); i++) {
                Row row = sheet.getRow(i + 1);
                String actual = formatter.formatCellValue(row == null ? null : row.getCell(column)).trim();
                check(expected.get(i).equals(actual), set + " command " + (i + 1) + ": " + actual);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
