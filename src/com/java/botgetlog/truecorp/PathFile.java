package com.java.botgetlog.truecorp;

import com.java.shared.AppMetadata;
import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

public class PathFile {

    private static final String USER_INPUT_FILE_NAME = "UserInterface_Input.xlsx";
    private static final String DEFAULT_INPUT_RELATIVE_PATH = "defaults" + File.separator + USER_INPUT_FILE_NAME;
    private static final String LEGACY_LOG_WORK_RELATIVE_PATH = "JAR\\log";
    private static final String CMDSET_SHEET = "cmdSet";
    private static final Object CMDSET_LOCK = new Object();
    private static volatile boolean cmdSetSynced = false;

    private String CurrentFolder;
    private String UserInterface_Input;
    private String Node_Connection_failed;
    private String Log;
    private File FileRow;
    private File FolderCurrent;
    private File[] FileBot;
    private String LogWork;
    private String row;

    public PathFile() {
        try {
            CurrentFolder = AppMetadata.getAppDirectory().getCanonicalPath();
            FolderCurrent = new File(CurrentFolder);
            FileBot = FolderCurrent.listFiles();
            File userWorkbook = ensureUserInputWorkbook(FolderCurrent);
            UserInterface_Input = userWorkbook.getCanonicalPath();
            synchronizeCmdSetSheet(userWorkbook, new File(FolderCurrent, DEFAULT_INPUT_RELATIVE_PATH));
            Log = new File(new File(FolderCurrent, "_output"), "Total_Log").getCanonicalPath()
                    + File.separator;
            FileRow = new File(CurrentFolder);
            File logWorkDir = AppMetadata.getBotWorkLogDirectory().getCanonicalFile();
            migrateLegacyWorkLogs(FolderCurrent, logWorkDir);
            LogWork = logWorkDir.getCanonicalPath() + File.separator;
            new File(Log).mkdirs();
            logWorkDir.mkdirs();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public String getCurrentFolder() {
        return CurrentFolder;
    }

    public String getUserInterface_Input() {
        return UserInterface_Input;
    }

    public String getLog() {
        return Log;
    }

    public File getFileRow() {
        return FileRow;
    }

    public File getFolderCurrent() {
        return FolderCurrent;
    }

    public File[] getFileBot() {
        return FileBot;
    }

    public String getLogWork() {
        return LogWork;
    }

    private static File ensureUserInputWorkbook(File appDir) throws IOException {
        File userWorkbook = new File(appDir, USER_INPUT_FILE_NAME);
        if (userWorkbook.isFile()) {
            return userWorkbook;
        }

        File bundledDefault = new File(appDir, DEFAULT_INPUT_RELATIVE_PATH);
        if (bundledDefault.isFile()) {
            Path targetPath = userWorkbook.toPath();
            Files.copy(bundledDefault.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            return userWorkbook;
        }

        return userWorkbook;
    }

    private static void synchronizeCmdSetSheet(File userWorkbook, File defaultWorkbook) {
        synchronized (CMDSET_LOCK) {
            if (cmdSetSynced) {
                return;
            }

            if (userWorkbook == null || defaultWorkbook == null
                    || !userWorkbook.isFile() || !defaultWorkbook.isFile()) {
                return;
            }
            if (userWorkbook.equals(defaultWorkbook)) {
                return;
            }

            try (Workbook targetWorkbook = WorkbookFactory.create(
                    new BufferedInputStream(new FileInputStream(userWorkbook)));
                    Workbook defaultWorkbookIn = WorkbookFactory.create(
                            new BufferedInputStream(new FileInputStream(defaultWorkbook)))) {
                Sheet sourceSheet = defaultWorkbookIn.getSheet(CMDSET_SHEET);
                if (sourceSheet == null) {
                    System.out.println("[AUTO-INPUT] No cmdSet sheet found in defaults workbook, skip sync.");
                    cmdSetSynced = true;
                    return;
                }

                int existingIndex = targetWorkbook.getSheetIndex(CMDSET_SHEET);
                if (existingIndex >= 0) {
                    targetWorkbook.removeSheetAt(existingIndex);
                }
                Sheet targetSheet = targetWorkbook.createSheet(CMDSET_SHEET);
                if (existingIndex >= 0 && existingIndex < targetWorkbook.getNumberOfSheets()) {
                    targetWorkbook.setSheetOrder(CMDSET_SHEET, existingIndex);
                }

                copySheetContent(sourceSheet, targetSheet);

                try (FileOutputStream out = new FileOutputStream(userWorkbook)) {
                    targetWorkbook.write(out);
                }
                cmdSetSynced = true;
                System.out.println("[AUTO-INPUT] Synchronized cmdSet sheet from defaults.");
            } catch (Exception e) {
                System.out.println("[AUTO-INPUT] Failed to synchronize cmdSet sheet: " + e.getMessage());
            }
        }
    }

    private static void copySheetContent(Sheet source, Sheet target) {
        if (source == null || target == null) {
            return;
        }
        int maxColumn = 0;
        for (int rowIndex = 0; rowIndex <= source.getLastRowNum(); rowIndex++) {
            Row sourceRow = source.getRow(rowIndex);
            if (sourceRow == null) {
                continue;
            }
            Row targetRow = target.createRow(rowIndex);
            targetRow.setHeight(sourceRow.getHeight());
            int lastCell = sourceRow.getLastCellNum();
            for (int col = 0; col < lastCell; col++) {
                copyCell(sourceRow, targetRow, col);
            }
            if (lastCell > maxColumn) {
                maxColumn = lastCell;
            }
        }
        for (int col = 0; col < maxColumn; col++) {
            target.setColumnWidth(col, source.getColumnWidth(col));
            if (source.isColumnHidden(col)) {
                target.setColumnHidden(col, true);
            }
        }
        for (int i = 0; i < source.getNumMergedRegions(); i++) {
            CellRangeAddress region = source.getMergedRegion(i);
            target.addMergedRegion(new CellRangeAddress(region.getFirstRow(), region.getLastRow(),
                    region.getFirstColumn(), region.getLastColumn()));
        }
    }

    private static void copyCell(Row sourceRow, Row targetRow, int col) {
        Cell sourceCell = sourceRow.getCell(col);
        if (sourceCell == null) {
            return;
        }
        Cell targetCell = targetRow.createCell(col);
        CellType type = sourceCell.getCellType();
        switch (type) {
            case STRING:
                targetCell.setCellValue(sourceCell.getStringCellValue());
                break;
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(sourceCell)) {
                    targetCell.setCellValue(sourceCell.getDateCellValue());
                } else {
                    targetCell.setCellValue(sourceCell.getNumericCellValue());
                }
                break;
            case BOOLEAN:
                targetCell.setCellValue(sourceCell.getBooleanCellValue());
                break;
            case FORMULA:
                targetCell.setCellFormula(sourceCell.getCellFormula());
                break;
            case ERROR:
                targetCell.setCellErrorValue(sourceCell.getErrorCellValue());
                break;
            case BLANK:
                break;
            default:
                targetCell.setCellValue(sourceCell.toString());
                break;
        }
    }

    private static void migrateLegacyWorkLogs(File appDir, File targetDir) throws IOException {
        if (appDir == null || targetDir == null) {
            return;
        }

        File legacyDir = new File(appDir, LEGACY_LOG_WORK_RELATIVE_PATH).getCanonicalFile();
        if (!legacyDir.isDirectory()) {
            return;
        }

        if (legacyDir.equals(targetDir.getCanonicalFile())) {
            return;
        }

        targetDir.mkdirs();
        File[] legacyChildren = legacyDir.listFiles();
        if (legacyChildren == null) {
            return;
        }

        for (File legacyChild : legacyChildren) {
            File migratedChild = new File(targetDir, legacyChild.getName());
            if (migratedChild.exists()) {
                continue;
            }
            Files.move(legacyChild.toPath(), migratedChild.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

}

