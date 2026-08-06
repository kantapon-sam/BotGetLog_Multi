package com.java.botgetlog.truecorp;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

final class TrueDeviceInventoryUpdater {

    private static final String DEVICE_SHEET = "deviceList_TRUE";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ConcurrentMap<Integer, PendingUpdate> PENDING = new ConcurrentHashMap<Integer, PendingUpdate>();

    private TrueDeviceInventoryUpdater() {
    }

    static void reset() {
        PENDING.clear();
    }

    static void recordVendorAdjustment(int rowNum, String loopback, String configuredDevice,
            String oldCmdSet, String newCmdSet) {
        String oldValue = safe(oldCmdSet);
        String newValue = safe(newCmdSet);
        if (rowNum <= 1 || oldValue.isEmpty() || newValue.isEmpty() || oldValue.equalsIgnoreCase(newValue)) {
            return;
        }
        PendingUpdate update = pending(rowNum, loopback, configuredDevice);
        synchronized (update) {
            update.oldCmdSet = oldValue;
            update.newCmdSet = newValue;
        }
    }

    static void recordDeviceName(int rowNum, String loopback, String configuredDevice, String actualDeviceName) {
        String actual = normalizeDeviceName(actualDeviceName);
        String configured = normalizeDeviceName(configuredDevice);
        if (rowNum <= 1 || actual.isEmpty() || actual.equalsIgnoreCase(configured)) {
            return;
        }
        PendingUpdate update = pending(rowNum, loopback, configuredDevice);
        synchronized (update) {
            update.actualDeviceName = actual;
        }
    }

    static InventoryUpdateResult applyPendingUpdates(PathFile fileInput) {
        if (fileInput == null || PENDING.isEmpty()) {
            return InventoryUpdateResult.empty();
        }
        File excelFile = new File(fileInput.getUserInterface_Input());
        if (!excelFile.isFile()) {
            System.out.println("[AUTO-INPUT] Inventory update skipped, workbook not found: "
                    + excelFile.getAbsolutePath());
            return InventoryUpdateResult.empty();
        }

        List<PendingUpdate> updates = new ArrayList<PendingUpdate>(PENDING.values());
        Collections.sort(updates, new Comparator<PendingUpdate>() {
            @Override
            public int compare(PendingUpdate a, PendingUpdate b) {
                return Integer.compare(a.rowNum, b.rowNum);
            }
        });

        File backupFile = null;
        File reportFile = null;
        int rowsChanged = 0;
        int namesChanged = 0;
        int vendorsChanged = 0;
        int missingRows = 0;

        try {
            reportFile = createReportFile(fileInput);
            List<String[]> reportRows = new ArrayList<String[]>();

            try (Workbook workbook = WorkbookFactory.create(new BufferedInputStream(new FileInputStream(excelFile)))) {
                Sheet sheet = workbook.getSheet(DEVICE_SHEET);
                if (sheet == null) {
                    System.out.println("[AUTO-INPUT] Inventory update skipped, missing sheet: " + DEVICE_SHEET);
                    return InventoryUpdateResult.empty();
                }

                for (PendingUpdate update : updates) {
                    Row row = sheet.getRow(update.rowNum - 1);
                    if (row == null) {
                        missingRows++;
                        continue;
                    }

                    boolean changed = false;
                    String oldName = getCell(row, 2);
                    String newName = safe(update.actualDeviceName);
                    if (!newName.isEmpty() && !newName.equalsIgnoreCase(normalizeDeviceName(oldName))) {
                        setCell(row, 2, newName);
                        namesChanged++;
                        changed = true;
                    } else {
                        newName = "";
                    }

                    String changedCmdCol = "";
                    String oldCmd = safe(update.oldCmdSet);
                    String newCmd = safe(update.newCmdSet);
                    if (!oldCmd.isEmpty() && !newCmd.isEmpty() && !oldCmd.equalsIgnoreCase(newCmd)) {
                        int last = Math.max(row.getLastCellNum(), 5);
                        for (int col = 4; col < last; col++) {
                            String cellValue = getCell(row, col);
                            if (cellValue.equalsIgnoreCase(oldCmd)) {
                                setCell(row, col, newCmd);
                                vendorsChanged++;
                                changed = true;
                                changedCmdCol = "CmdSet-" + (col - 3);
                                break;
                            }
                        }
                    }

                    if (changed) {
                        rowsChanged++;
                        reportRows.add(new String[]{
                            Integer.toString(update.rowNum),
                            safe(update.loopback),
                            oldName,
                            newName,
                            oldCmd,
                            newCmd,
                            changedCmdCol
                        });
                    }
                }

                if (rowsChanged > 0) {
                    backupFile = backupWorkbook(fileInput, excelFile);
                    try (FileOutputStream out = new FileOutputStream(excelFile)) {
                        workbook.write(out);
                    }
                }
            }

            writeReport(reportFile, reportRows, backupFile);
            PENDING.clear();
        } catch (Exception e) {
            System.out.println("[AUTO-INPUT] Inventory update failed: " + e.getMessage());
        }

        System.out.printf(Locale.ROOT,
                "[AUTO-INPUT] Inventory update summary: rowsChanged=%d namesChanged=%d vendorsChanged=%d missingRows=%d pending=%d backup=%s report=%s%n",
                rowsChanged, namesChanged, vendorsChanged, missingRows, updates.size(),
                backupFile == null ? "" : backupFile.getAbsolutePath(),
                reportFile == null ? "" : reportFile.getAbsolutePath());
        return new InventoryUpdateResult(rowsChanged, namesChanged, vendorsChanged, missingRows,
                updates.size(), backupFile, reportFile);
    }

    private static PendingUpdate pending(int rowNum, String loopback, String configuredDevice) {
        PendingUpdate created = new PendingUpdate(rowNum, loopback, configuredDevice);
        PendingUpdate existing = PENDING.putIfAbsent(rowNum, created);
        return existing == null ? created : existing;
    }

    private static File backupWorkbook(PathFile fileInput, File excelFile) throws Exception {
        File dir = new File(new File(new File(fileInput.getCurrentFolder(), "_output"), "System_Log"), "Input_Backup");
        dir.mkdirs();
        File backup = new File(dir, "UserInterface_Input_inventory_before_" + LocalDateTime.now().format(TS) + ".xlsx");
        Files.copy(excelFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static File createReportFile(PathFile fileInput) {
        File dir = new File(new File(fileInput.getCurrentFolder(), "_output"), "System_Log");
        dir.mkdirs();
        return new File(dir, "true-linkoptical-inventory-updates-" + LocalDateTime.now().format(TS) + ".csv");
    }

    private static void writeReport(File reportFile, List<String[]> rows, File backupFile) {
        if (reportFile == null) {
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(reportFile, false))) {
            writer.write("backup," + csv(backupFile == null ? "" : backupFile.getAbsolutePath()));
            writer.newLine();
            writer.write("row,loopback,oldDeviceName,newDeviceName,oldCmdSet,newCmdSet,cmdSetColumn");
            writer.newLine();
            for (String[] row : rows) {
                writer.write(csv(row[0]) + "," + csv(row[1]) + "," + csv(row[2]) + ","
                        + csv(row[3]) + "," + csv(row[4]) + "," + csv(row[5]) + "," + csv(row[6]));
                writer.newLine();
            }
        } catch (Exception e) {
            System.out.println("[AUTO-INPUT] Inventory report failed: " + e.getMessage());
        }
    }

    private static String getCell(Row row, int col) {
        if (row == null) {
            return "";
        }
        return BotGetLog_TrueCorp.getCell(row, col);
    }

    private static void setCell(Row row, int col, String value) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private static String normalizeDeviceName(String value) {
        String v = safe(value).toUpperCase(Locale.ROOT);
        return v.replaceAll("\\s+", "_");
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static final class InventoryUpdateResult {

        final int rowsChanged;
        final int namesChanged;
        final int vendorsChanged;
        final int missingRows;
        final int pendingUpdates;
        final File backupFile;
        final File reportFile;

        InventoryUpdateResult(int rowsChanged, int namesChanged, int vendorsChanged,
                int missingRows, int pendingUpdates, File backupFile, File reportFile) {
            this.rowsChanged = rowsChanged;
            this.namesChanged = namesChanged;
            this.vendorsChanged = vendorsChanged;
            this.missingRows = missingRows;
            this.pendingUpdates = pendingUpdates;
            this.backupFile = backupFile;
            this.reportFile = reportFile;
        }

        static InventoryUpdateResult empty() {
            return new InventoryUpdateResult(0, 0, 0, 0, 0, null, null);
        }
    }

    private static final class PendingUpdate {

        final int rowNum;
        final String loopback;
        final String configuredDevice;
        volatile String actualDeviceName = "";
        volatile String oldCmdSet = "";
        volatile String newCmdSet = "";

        PendingUpdate(int rowNum, String loopback, String configuredDevice) {
            this.rowNum = rowNum;
            this.loopback = safe(loopback);
            this.configuredDevice = safe(configuredDevice);
        }
    }
}
