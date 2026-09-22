package com.java.updater;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Existing user inventories keep their data while the eight Juniper types migrate. */
public final class JuniperNodeTypeUpdateRegression {
    private static final List<String[]> NODES = Arrays.asList(
            new String[]{"HAMMBKBD1KW", "10.185.0.11"},
            new String[]{"HAMMBKBD15W", "10.185.0.12"},
            new String[]{"HAMMBKBD26W", "10.185.0.13"},
            new String[]{"HAMMBKBD27W", "10.185.0.14"},
            new String[]{"CWTTNTBB2EW", "10.185.0.21"},
            new String[]{"CWTTNTBB24W", "10.185.0.22"},
            new String[]{"PTT021OP05M", "10.185.0.31"},
            new String[]{"PTT021OP06M", "10.185.0.32"});

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("juniper-cn-updater-");
        Path installed = Files.createDirectory(root.resolve("installed"));
        Path stage = Files.createDirectory(root.resolve("stage"));
        Files.createDirectory(stage.resolve("defaults"));
        Path user = installed.resolve("UserInterface_Input.xlsx");
        Path defaults = stage.resolve("defaults/UserInterface_Input.xlsx");
        makeWorkbook(user, false);
        makeWorkbook(defaults, true);
        Method sync = UpdaterMain.class.getDeclaredMethod(
                "synchronizeUserInputCmdSet", Path.class, Path.class);
        sync.setAccessible(true);
        sync.invoke(null, installed, stage);
        verify(user);
        sync.invoke(null, installed, stage);
        verify(user);
        require(Files.isDirectory(installed.resolve("_output/System_Log/Input_Backup")),
                "missing user workbook backup");
        System.out.println("PASS JuniperNodeTypeUpdateRegression");
    }

    private static void makeWorkbook(Path file, boolean source) throws Exception {
        try (Workbook book = new XSSFWorkbook()) {
            Sheet devices = book.createSheet("deviceList_TRUE");
            devices.createRow(0).createCell(0).setCellValue("Enabled");
            for (int i = 0; i < NODES.size(); i++) {
                String[] node = NODES.get(i);
                addDevice(devices, i + 1, source ? "CN" : "MX2020",
                        node[0], node[1], "J-LLDP-Link_OPTIC");
            }
            addDevice(devices, 9, "MX2020", "OTHER", "10.185.0.99", "J-LLDP-Link_OPTIC");
            addDevice(devices, 10, "MX2020", "HAMMBKBD1KW", "10.185.0.99", "J-LLDP-Link_OPTIC");
            addDevice(devices, 11, "MX2020", "HAMMBKBD1KW", "10.185.0.11", "HW-LLDP-Link_OPTIC");
            book.createSheet("cmdSet").createRow(0).createCell(0)
                    .setCellValue(source ? "NEW_COMMANDS" : "OLD_COMMANDS");
            book.createSheet("CustomData").createRow(0).createCell(0)
                    .setCellValue("keep user data");
            try (OutputStream output = Files.newOutputStream(file)) {
                book.write(output);
            }
        }
    }

    private static void addDevice(Sheet sheet, int index, String type,
            String node, String ip, String cmdSet) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue("Y");
        row.createCell(1).setCellValue(type);
        row.createCell(2).setCellValue(node);
        row.createCell(3).setCellValue(ip);
        row.createCell(4).setCellValue(cmdSet);
    }

    private static void verify(Path user) throws Exception {
        try (Workbook book = WorkbookFactory.create(user.toFile())) {
            Sheet devices = book.getSheet("deviceList_TRUE");
            for (int i = 1; i <= NODES.size(); i++) {
                require("CN".equals(devices.getRow(i).getCell(1).getStringCellValue()),
                        "Juniper type not migrated: " + i);
            }
            for (int i = 9; i <= 11; i++) {
                require("MX2020".equals(devices.getRow(i).getCell(1).getStringCellValue()),
                        "Unrelated node changed: " + i);
            }
            require("NEW_COMMANDS".equals(book.getSheet("cmdSet").getRow(0)
                    .getCell(0).getStringCellValue()), "cmdSet not synchronized");
            require("keep user data".equals(book.getSheet("CustomData").getRow(0)
                    .getCell(0).getStringCellValue()), "custom data lost");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
