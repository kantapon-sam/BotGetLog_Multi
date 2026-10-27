package com.java.build;

import com.java.shared.HuaweiInterfaceCommands;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.apache.poi.ss.usermodel.*;

/** Adds the new command column to a release template without editing its source workbook. */
public final class HuaweiInterfaceWorkbook {
    private HuaweiInterfaceWorkbook() { }

    public static boolean addColumn(Workbook workbook) {
        Sheet sheet = workbook.getSheet("cmdSet");
        if (sheet == null) sheet = workbook.createSheet("cmdSet");
        Row header = sheet.getRow(0);
        if (header == null) header = sheet.createRow(0);
        DataFormatter formatter = new DataFormatter();
        for (Cell cell : header) {
            if (HuaweiInterfaceCommands.matches(formatter.formatCellValue(cell))) return false;
        }
        int column = Math.max(0, header.getLastCellNum());
        // Keep every existing column, including locally customized command sets.
        for (int i = 0; i < HuaweiInterfaceCommands.ROWS.size(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) row = sheet.createRow(i);
            Cell cell = row.createCell(column);
            cell.setCellValue(HuaweiInterfaceCommands.ROWS.get(i));
            if (column > 0 && row.getCell(column - 1) != null) cell.setCellStyle(row.getCell(column - 1).getCellStyle());
        }
        sheet.setColumnWidth(column, 36 * 256);
        return true;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: HuaweiInterfaceWorkbook INPUT.xlsx OUTPUT.xlsx");
        Path input = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        if (input.equals(output) || (Files.exists(output) && Files.isSameFile(input, output))) {
            throw new IllegalArgumentException("Use a separate output; the source workbook must be preserved.");
        }
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "hw-interface-template-", ".xlsx");
        try (InputStream stream = Files.newInputStream(input); Workbook workbook = WorkbookFactory.create(stream)) {
            addColumn(workbook);
            try (OutputStream target = Files.newOutputStream(temporary)) { workbook.write(target); }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
