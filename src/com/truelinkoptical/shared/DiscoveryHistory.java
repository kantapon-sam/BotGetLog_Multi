package com.truelinkoptical.shared;

import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

/** Save the removal reason in the same atomic workbook write as the removed row. */
public final class DiscoveryHistory {
    private static final String SHEET = "_TRUE_Discovery_History";
    private final Workbook book;
    private Sheet sheet;
    private final Map<String, Row> entries = new HashMap<>();

    public DiscoveryHistory(Workbook book) {
        this.book = book;
        sheet = book.getSheet(SHEET);
        if (sheet != null) for (Row row : sheet) {
            if (row.getRowNum() != 0) entries.put(text(row, 0), row);
        }
    }

    private void ensureSheet() {
        if (sheet == null) {
            sheet = book.createSheet(SHEET);
            book.setSheetHidden(book.getSheetIndex(sheet), true);
            Row header = sheet.createRow(0);
            String[] names = {"IP", "Reason", "Verified identity", "Recorded epoch ms"};
            for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
        }
    }

    public boolean importedReports() { return sheet != null && "imported".equals(text(sheet.getRow(0), 4)); }

    public void finishImport() {
        ensureSheet();
        sheet.getRow(0).createCell(4).setCellValue("imported");
    }

    public void remember(String ip, String reason, String identity) {
        ensureSheet();
        Row row = entries.get(ip);
        if (row == null) {
            row = sheet.createRow(sheet.getLastRowNum() + 1);
            entries.put(ip, row);
        }
        row.createCell(0).setCellValue(ip);
        row.createCell(1).setCellValue(reason);
        row.createCell(2).setCellValue(identity);
        row.createCell(3).setCellValue(Long.toString(System.currentTimeMillis()));
    }

    public boolean manual(String ip) { return "manual".equals(text(entries.get(ip), 1)); }

    public long recordedAt(String ip) {
        Row row = entries.get(ip);
        return row == null ? 0L : Long.parseLong(text(row, 3));
    }

    public String blockedReason(String ip, boolean confirmed, String physicalIdentity) {
        Row row = entries.get(ip);
        String reason = text(row, 1);
        String saved = text(row, 2);
        if ("duplicate".equals(reason)) {
            String mac = saved.startsWith("mac:") ? saved.substring(4) : saved.substring(saved.lastIndexOf('|') + 1);
            if (physicalIdentity.isEmpty() || mac.isEmpty() || physicalIdentity.endsWith("|" + mac))
                return "previously verified duplicate device";
        }
        if ("unreachable".equals(reason) && !confirmed) return "previously unreachable; awaiting verified management IP";
        return "";
    }

    public void forget(String ip) {
        Row row = entries.remove(ip);
        if (row != null) sheet.removeRow(row);
    }

    private static String text(Row row, int col) {
        return row == null || row.getCell(col) == null ? "" : row.getCell(col).getStringCellValue();
    }
}
