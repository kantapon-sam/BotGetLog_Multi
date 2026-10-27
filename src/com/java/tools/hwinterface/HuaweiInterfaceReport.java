package com.java.tools.hwinterface;

import com.java.shared.ToolDialogHelper;
import com.java.shared.AppMetadata;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/** One row per interface in the latest HW-Interface snapshot of each management IP. */
public final class HuaweiInterfaceReport {
    public static final String[] COLUMNS = {"Site", "IP node", "Interface", "Description", "PHY",
        "Protocol", "InUti", "OutUti", "InErrors", "OutErrors"};
    private static final Pattern FILE = Pattern.compile(
            "^\\[\\d+]([^_]+)_(.+)_HW-Interface_(\\d{4}-\\d{2}-\\d{2})\\.txt$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT = Pattern.compile("^\\s*<([^>\\r\\n]+)>\\s*(.*)$");
    private static final Pattern BRIEF = Pattern.compile(
            "^\\s*(\\S+)\\s+([*^]?(?:up|down)(?:\\([^)]*\\))*)\\s+"
            + "((?:up|down)(?:\\([^)]*\\))*)\\s+(\\S+)\\s+(\\S+)\\s+([\\d,]+|--)\\s+([\\d,]+|--)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DETAIL = Pattern.compile("^\\s*(\\S+)\\s+current state\\s*:\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROTOCOL = Pattern.compile("^\\s*Line protocol current state\\s*:\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTION = Pattern.compile("^\\s*Description\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d?]*[ -/]*[@-~]");

    private HuaweiInterfaceReport() { }

    public static void main(String[] args) {
        boolean cli = args.length > 0 || GraphicsEnvironment.isHeadless();
        try {
            File app = AppMetadata.getAppDirectory();
            File input = new File(app, "_output/Total_Log"), output = new File(app, "_output/HW-Interface");
            for (int i = 0; i < args.length; i++) {
                if ("--input".equals(args[i]) && i + 1 < args.length) input = new File(args[++i]);
                else if ("--output".equals(args[i]) && i + 1 < args.length) output = new File(args[++i]);
                else throw new IllegalArgumentException("Usage: java -jar HW-Interface.jar [--input LOG_FOLDER --output REPORT_FOLDER]");
            }
            File[] files = input.listFiles((dir, name) -> FILE.matcher(name).matches());
            if (files == null || files.length == 0) throw new IOException(
                    "No HW-Interface logs in " + input.getAbsolutePath()
                    + "\nCollect Huawei nodes with cmdSet HW-Interface in BotGetLog [TRUE] or [DTAC] first.");
            final Result result = processFiles(files, output);
            System.out.println("[HW-Interface] " + result.rows.size() + " interfaces / " + result.nodes
                    + " nodes -> " + result.csv.getAbsolutePath());
            for (String warning : result.warnings) System.out.println("[WARN] " + warning);
            if (!cli) SwingUtilities.invokeLater(() -> show(result));
        } catch (Exception ex) {
            System.err.println("[HW-Interface] " + ex.getMessage());
            if (cli) System.exit(1);
            else ToolDialogHelper.showInfo("HW-Interface", ex.getMessage());
        }
    }

    public static Result processFiles(File[] files, File output) throws IOException {
        List<File> ordered = new ArrayList<File>(Arrays.asList(files));
        // Filename collection date is authoritative; mtime breaks same-day ties only.
        Collections.sort(ordered, Comparator.comparing(HuaweiInterfaceReport::snapshotDate)
                .thenComparingLong(File::lastModified).thenComparing(File::getName));
        Map<String, File> latest = new LinkedHashMap<String, File>();
        for (File file : ordered) {
            Matcher metadata = FILE.matcher(file.getName());
            if (metadata.matches()) latest.put(metadata.group(1), file);
        }
        List<Row> rows = new ArrayList<Row>();
        List<String> warnings = new ArrayList<String>();
        for (File file : latest.values()) {
            Snapshot snapshot = parse(file);
            rows.addAll(snapshot.rows);
            warnings.addAll(snapshot.warnings);
        }
        if (rows.isEmpty()) throw new IOException("No Huawei interface rows found in the latest logs. Recollect HW-Interface.");
        Files.createDirectories(output.toPath());
        File csv = new File(output, "DataHW_Interface_" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss_SSS")) + ".csv");
        List<String> lines = new ArrayList<String>();
        lines.add("\uFEFF" + String.join(",", COLUMNS));
        for (Row row : rows) lines.add(row.toCsv());
        Files.write(csv.toPath(), lines, StandardCharsets.UTF_8);
        return new Result(rows, latest.size(), warnings, csv);
    }

    private static String snapshotDate(File file) {
        Matcher m = FILE.matcher(file.getName());
        return m.matches() ? m.group(3) : "";
    }

    public static Snapshot parse(File file) throws IOException {
        Matcher metadata = FILE.matcher(file.getName());
        if (!metadata.matches()) throw new IOException("Expected a Huawei HW-Interface log: " + file.getName());
        String ip = metadata.group(1), site = metadata.group(2);
        String text = ANSI.matcher(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).replaceAll("");
        Map<String, Row> rows = new LinkedHashMap<String, Row>();
        boolean brief = false, detail = false, briefSeen = false, detailSeen = false, detailReturned = false;
        Row current = null;
        String wrappedInterface = "";
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            Matcher prompt = PROMPT.matcher(line);
            if (prompt.matches()) {
                String command = prompt.group(2).trim();
                if (command.equalsIgnoreCase("display interface brief") || command.equalsIgnoreCase("display interface")) {
                    site = prompt.group(1).trim();
                }
                if (detail) detailReturned = true;
                brief = command.equalsIgnoreCase("display interface brief");
                detail = command.equalsIgnoreCase("display interface");
                briefSeen |= brief;
                detailSeen |= detail;
                current = null;
                wrappedInterface = "";
                continue;
            }
            if (brief) {
                if (!wrappedInterface.isEmpty()) {
                    line = wrappedInterface + " " + line;
                    wrappedInterface = "";
                }
                Matcher m = BRIEF.matcher(line);
                if (m.matches()) {
                    Row row = rows.computeIfAbsent(key(m.group(1)), k -> new Row());
                    row.values[2] = m.group(1);
                    for (int i = 2; i <= 7; i++) row.values[i + 2] = m.group(i);
                    row.fromBrief = true;
                } else if (line.matches("[A-Za-z][A-Za-z0-9/-]*[0-9][A-Za-z0-9./()_-]*")) {
                    wrappedInterface = line;
                }
            } else if (detail) {
                Matcher m = DETAIL.matcher(line);
                if (m.matches()) {
                    current = rows.computeIfAbsent(key(m.group(1)), k -> new Row());
                    if (current.values[2].isEmpty()) current.values[2] = m.group(1);
                    if (!current.fromBrief) current.values[4] = state(m.group(2));
                    current.fromDetail = true;
                } else if (current != null) {
                    m = DESCRIPTION.matcher(line);
                    if (m.matches()) current.values[3] = m.group(1).trim();
                    m = PROTOCOL.matcher(line);
                    if (m.matches() && !current.fromBrief) current.values[5] = state(m.group(1));
                }
            }
        }
        int missingBrief = 0, missingDetail = 0;
        for (Row row : rows.values()) {
            row.values[0] = site;
            row.values[1] = ip;
            if (!row.fromBrief) missingBrief++;
            if (!row.fromDetail) missingDetail++;
        }
        List<String> warnings = new ArrayList<String>();
        if (!briefSeen || !detailSeen || !detailReturned || missingBrief > 0 || missingDetail > 0) {
            warnings.add(file.getName() + ": incomplete snapshot (brief=" + briefSeen + ", detail="
                    + detailSeen + ", detail returned=" + detailReturned + ", missing brief=" + missingBrief
                    + ", missing detail=" + missingDetail + "). Missing values remain blank; recollect this node.");
        }
        return new Snapshot(new ArrayList<Row>(rows.values()), warnings);
    }

    static String key(String name) {
        String value = name.replaceAll("(?i)\\((?:[0-9.]+[GMK]?|[bc])\\)$", "").toLowerCase(Locale.ROOT);
        if (value.startsWith("gigabitethernet")) return "ge" + value.substring(15);
        if (value.startsWith("xgigabitethernet")) return "xge" + value.substring(16);
        return value;
    }

    private static String state(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (value.contains("administratively down")) return "*down";
        return value;
    }

    static String csv(String value) {
        // Device descriptions are untrusted spreadsheet text, never Excel formulas.
        if (!"--".equals(value) && (value.matches("^[=+@-].*") || value.startsWith("\t"))) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void show(Result result) {
        ToolDialogHelper.setWindowsLookAndFeel();
        JFrame frame = new JFrame("HW-Interface | Huawei interface report");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (Row row : result.rows) model.addRow(row.values);
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(26);
        table.setFillsViewportHeight(true);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<DefaultTableModel>(model);
        table.setRowSorter(sorter);
        Comparator<String> numeric = (a, b) -> {
            try {
                return new java.math.BigDecimal(a.replace("%", "").replace(",", ""))
                        .compareTo(new java.math.BigDecimal(b.replace("%", "").replace(",", "")));
            } catch (NumberFormatException ex) {
                boolean aNumber = a.matches("[0-9.,]+%?");
                boolean bNumber = b.matches("[0-9.,]+%?");
                return aNumber != bNumber ? (aNumber ? -1 : 1) : a.compareTo(b);
            }
        };
        for (int col = 6; col < 10; col++) sorter.setComparator(col, numeric);
        int[] widths = {200, 130, 210, 380, 85, 90, 85, 85, 105, 105};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        DefaultTableCellRenderer status = new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable t, Object value,
                    boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                if (!selected) setForeground(String.valueOf(value).contains("down") ? new Color(180, 40, 40) : new Color(0, 110, 85));
                return this;
            }
        };
        table.getColumnModel().getColumn(4).setCellRenderer(status);
        table.getColumnModel().getColumn(5).setCellRenderer(status);
        JPanel top = new JPanel(new BorderLayout(12, 8));
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel count = new JLabel(result.rows.size() + " interfaces | " + result.nodes + " Huawei nodes | CSV saved");
        JTextField search = new JTextField();
        search.setToolTipText("Search site, IP, interface, description or status");
        JPanel filters = new JPanel(new BorderLayout(8, 0));
        filters.add(new JLabel("Search"), BorderLayout.WEST);
        filters.add(search, BorderLayout.CENTER);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
            private void filter() {
                sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(search.getText())));
                count.setText(table.getRowCount() + " / " + result.rows.size() + " interfaces | " + result.nodes + " Huawei nodes | CSV saved");
            }
        });
        JButton open = new JButton("Open CSV folder");
        open.addActionListener(e -> {
            try { Desktop.getDesktop().open(result.csv.getParentFile()); }
            catch (Exception ex) { JOptionPane.showMessageDialog(frame, result.csv.getAbsolutePath()); }
        });
        filters.add(open, BorderLayout.EAST);
        top.add(count, BorderLayout.NORTH);
        top.add(filters, BorderLayout.CENTER);
        if (!result.warnings.isEmpty()) {
            JTextArea warnings = new JTextArea(String.join("\n", result.warnings), Math.min(3, result.warnings.size()), 80);
            warnings.setEditable(false);
            warnings.setForeground(new Color(150, 75, 0));
            top.add(new JScrollPane(warnings), BorderLayout.SOUTH);
        }
        frame.add(top, BorderLayout.NORTH);
        frame.add(new JScrollPane(table), BorderLayout.CENTER);
        JLabel footer = new JLabel("  All physical/logical interfaces, including down ports. Blank = missing; -- = device reports unavailable.  ");
        footer.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        frame.add(footer, BorderLayout.SOUTH);
        frame.setSize(new Dimension(1280, 700));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static final class Row {
        public final String[] values = new String[COLUMNS.length];
        boolean fromBrief, fromDetail;
        Row() { Arrays.fill(values, ""); }
        String toCsv() {
            List<String> fields = new ArrayList<String>();
            for (String value : values) fields.add(csv(value));
            return String.join(",", fields);
        }
    }

    public static final class Snapshot {
        public final List<Row> rows;
        public final List<String> warnings;
        Snapshot(List<Row> rows, List<String> warnings) { this.rows = rows; this.warnings = warnings; }
    }

    public static final class Result {
        public final List<Row> rows;
        public final int nodes;
        public final List<String> warnings;
        public final File csv;
        Result(List<Row> rows, int nodes, List<String> warnings, File csv) {
            this.rows = rows; this.nodes = nodes; this.warnings = warnings; this.csv = csv;
        }
    }
}
