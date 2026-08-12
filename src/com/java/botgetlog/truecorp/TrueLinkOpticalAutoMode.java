package com.java.botgetlog.truecorp;

import com.java.tools.linkoptical.Link_Optical;
import java.awt.GraphicsEnvironment;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

final class TrueLinkOpticalAutoMode {

    static final String ARG_ENABLE = "--auto-link-optical";
    static final String ARG_ALL = "--link-optical-all";
    static final String ARG_INCREMENTAL = "--link-optical-incremental";
    private static final String ARG_SITE_PREFIX = "--link-optical-site=";
    private static final String ARG_SITES_PREFIX = "--link-optical-sites=";
    private static final String ARG_NEIGHBOR_FILE_PREFIX = "--link-optical-neighbor-file=";
    private static final String ARG_THREADS_PREFIX = "--link-optical-threads=";
    private static final String ARG_EXPORT_MODE_PREFIX = "--link-optical-export-mode=";
    private static final String ARG_EXPORT_SINCE_FILE_PREFIX = "--link-optical-export-since-file=";
    private static final String ARG_NEW_SITE_QUEUE_FILE_PREFIX = "--link-optical-new-site-queue-file=";
    private static final String ARG_SKIP_GLOBAL_DEDUP = "--link-optical-skip-global-dedup";
    private static final String CMDSET_TOKEN = "-LLDP-Link_OPTIC";
    private static final int PREVIEW_LIMIT = 12;
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");
    private static final Pattern LOG_ROW_PATTERN = Pattern.compile("^\\[(\\d+)\\].*\\.txt$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOG_IP_PATTERN = Pattern.compile("^\\[\\d+\\]([^_]+)_.*\\.txt$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW_SELECTOR_PATTERN = Pattern.compile("^(?:#|row\\s*)(\\d+)$",
            Pattern.CASE_INSENSITIVE);

    private TrueLinkOpticalAutoMode() {
    }

    enum ExportMode {
        FULL,
        PRESCAN,
        SKIP
    }

    static final class Selection {

        private final boolean enabled;
        private final boolean allSites;
        private final boolean incremental;
        private final String query;
        private final LinkedHashMap<Integer, List<String>> cmdSetsByRow;
        private final List<String> previewRows;

        private Selection(boolean enabled, boolean allSites, boolean incremental, String query,
                LinkedHashMap<Integer, List<String>> cmdSetsByRow, List<String> previewRows) {
            this.enabled = enabled;
            this.allSites = allSites;
            this.incremental = incremental;
            this.query = safeValue(query);
            this.cmdSetsByRow = cmdSetsByRow == null
                    ? new LinkedHashMap<Integer, List<String>>()
                    : cmdSetsByRow;
            this.previewRows = previewRows == null
                    ? new ArrayList<String>()
                    : previewRows;
        }

        static Selection disabled() {
            return new Selection(false, false, false, "", null, null);
        }

        boolean isEnabled() {
            return enabled;
        }

        boolean isAllSites() {
            return allSites;
        }

        boolean isIncremental() {
            return incremental;
        }

        String getModeName() {
            if (incremental) {
                return "INCREMENTAL-DOWN";
            }
            if (allSites) {
                return "ALL";
            }
            return getSiteCount() > 1 ? "SELECTED" : "ONE";
        }

        String getQuery() {
            return query;
        }

        int getSiteCount() {
            return cmdSetsByRow.size();
        }

        int getCommandCount() {
            int total = 0;
            for (List<String> cmdSets : cmdSetsByRow.values()) {
                total += cmdSets.size();
            }
            return total;
        }

        boolean includesRow(Row row) {
            if (row == null) {
                return false;
            }
            return cmdSetsByRow.containsKey(row.getRowNum() + 1);
        }

        List<String> getCmdSetsForRow(Row row) {
            if (row == null) {
                return Collections.emptyList();
            }
            List<String> cmdSets = cmdSetsByRow.get(row.getRowNum() + 1);
            return cmdSets == null ? Collections.<String>emptyList() : cmdSets;
        }

        Map<Integer, List<String>> getCmdSetsByRow() {
            return Collections.unmodifiableMap(cmdSetsByRow);
        }

        List<String> getPreviewRows() {
            return Collections.unmodifiableList(previewRows);
        }
    }

    static Selection resolveSelection(String[] args, Sheet deviceSheet) {
        if (!isEnabled(args)) {
            return Selection.disabled();
        }
        if (deviceSheet == null) {
            JOptionPane.showMessageDialog(
                    null,
                    "Missing deviceList_TRUE sheet. TRUE Link Optical Auto cannot continue.",
                    "TRUE Link Optical Auto",
                    JOptionPane.ERROR_MESSAGE
            );
            return null;
        }

        String queryArg = getSiteQueryArg(args);
        if (!queryArg.isEmpty()) {
            List<String> queries = splitQueries(queryArg);
            if (queries.size() > 1) {
                return resolveMultiSiteSelection(deviceSheet, queries, false);
            }
            return resolveOneSiteSelection(deviceSheet, queryArg, false);
        }
        String queriesArg = getSiteQueriesArg(args);
        if (!queriesArg.isEmpty()) {
            return resolveMultiSiteSelection(deviceSheet, splitQueries(queriesArg), false);
        }
        if (hasArg(args, ARG_INCREMENTAL)) {
            return resolveIncrementalSelection(deviceSheet, getNeighborFileArg(args));
        }
        if (hasArg(args, ARG_ALL)) {
            return resolveAllSiteSelection(deviceSheet, false);
        }

        Object[] options = {"Select Nodes", "All Site", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                null,
                "Select TRUE Link Optical mode.",
                "TRUE Link Optical Auto",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 1) {
            return resolveAllSiteSelection(deviceSheet, true);
        }
        if (choice == 0) {
            return promptMultiSiteSelection(deviceSheet);
        }
        return null;
    }

    static boolean shouldRunRow(Selection selection, Row row) {
        if (selection == null || !selection.isEnabled()) {
            return BotGetLog_TrueCorp.getCell(row, 0).equalsIgnoreCase("Y");
        }
        return selection.includesRow(row);
    }

    static List<String> getCmdSetsForRow(Selection selection, Row row) {
        if (selection == null || !selection.isEnabled()) {
            List<String> cmdSets = new ArrayList<String>();
            if (row == null) {
                return cmdSets;
            }
            for (int k = 4; k < row.getLastCellNum(); k++) {
                String cmdSet = BotGetLog_TrueCorp.getCell(row, k).trim();
                if (!cmdSet.isEmpty()) {
                    cmdSets.add(cmdSet);
                }
            }
            return cmdSets;
        }
        return selection.getCmdSetsForRow(row);
    }

    static boolean shouldClearSelectedLogsBeforeManualRun(Selection selection) {
        return shouldClearSelectedLogsBeforeRerun(selection)
                && !selection.isIncremental();
    }

    static boolean shouldClearSelectedLogsBeforeRerun(Selection selection) {
        return selection != null
                && selection.isEnabled()
                && !selection.isAllSites()
                && selection.getSiteCount() > 0;
    }

    static int clearSelectedLogsBeforeManualRun(File logDir, Selection selection) {
        return clearSelectedLogsBeforeManualRun(logDir, selection, null);
    }

    static int clearSelectedLogsBeforeManualRun(File logDir, Selection selection, Sheet deviceSheet) {
        if (!shouldClearSelectedLogsBeforeManualRun(selection)) {
            return 0;
        }
        return clearSelectedLogsBeforeRerun(logDir, selection, deviceSheet);
    }

    static int clearSelectedLogsBeforeRerun(File logDir, Selection selection) {
        return clearSelectedLogsBeforeRerun(logDir, selection, null);
    }

    static int clearSelectedLogsBeforeRerun(File logDir, Selection selection, Sheet deviceSheet) {
        if (!shouldClearSelectedLogsBeforeRerun(selection)
                || logDir == null
                || !logDir.isDirectory()) {
            return 0;
        }
        Set<String> selectedIps = selectedIps(selection, deviceSheet);
        if (selectedIps.isEmpty()) {
            System.out.println("[AUTO-LINK] Selected-log cleanup skipped: no valid selected IP identity was found.");
            return 0;
        }
        File[] logs = logDir.listFiles((dir, name) -> name != null
                && name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (logs == null || logs.length == 0) {
            return 0;
        }

        int moved = 0;
        String reason = selection.isIncremental()
                ? "incremental down rerun before batch"
                : "manual selected rerun before batch";
        for (File log : logs) {
            if (!isSelectedLinkOpticalLog(log, selectedIps)) {
                continue;
            }
            if (Telnet_Multi.moveLogToArchiveIfInactive(log, reason)) {
                moved++;
            }
        }
        return moved;
    }

    static File[] findSelectedCompletedLogs(File logDir, Selection selection) {
        return findSelectedCompletedLogs(logDir, selection, 0L);
    }

    static File[] findSelectedCompletedLogs(File logDir, Selection selection, long modifiedSince) {
        if (logDir == null || !logDir.isDirectory() || selection == null || !selection.isEnabled()) {
            return new File[0];
        }
        File[] allLogs = logDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (allLogs == null || allLogs.length == 0) {
            return new File[0];
        }
        Arrays.sort(allLogs, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return Long.compare(f2.lastModified(), f1.lastModified());
            }
        });

        Set<Integer> selectedRows = selection.getCmdSetsByRow().keySet();
        Map<Integer, List<File>> logsByRow = new HashMap<Integer, List<File>>();
        for (File log : allLogs) {
            if (log == null || !log.isFile()) {
                continue;
            }
            if (!isModifiedAtOrAfter(log, modifiedSince)) {
                continue;
            }
            Matcher matcher = LOG_ROW_PATTERN.matcher(log.getName());
            if (!matcher.matches()) {
                continue;
            }
            int rowNum;
            try {
                rowNum = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!selectedRows.contains(rowNum)) {
                continue;
            }
            List<File> rowLogs = logsByRow.get(rowNum);
            if (rowLogs == null) {
                rowLogs = new ArrayList<File>();
                logsByRow.put(rowNum, rowLogs);
            }
            rowLogs.add(log);
        }

        LinkedHashMap<String, File> selectedLogs = new LinkedHashMap<String, File>();
        for (Map.Entry<Integer, List<String>> entry : selection.getCmdSetsByRow().entrySet()) {
            int rowNum = entry.getKey();
            List<File> rowLogs = logsByRow.get(rowNum);
            if (rowLogs == null || rowLogs.isEmpty()) {
                continue;
            }
            for (String cmdSet : entry.getValue()) {
                File latestComplete = findLatestCompleteLog(rowLogs, rowNum, cmdSet);
                if (latestComplete != null) {
                    selectedLogs.put(canonicalPath(latestComplete), latestComplete);
                }
            }
        }
        return selectedLogs.values().toArray(new File[0]);
    }

    static boolean isModifiedAtOrAfter(File file, long modifiedSince) {
        return file != null && (modifiedSince <= 0L || file.lastModified() >= modifiedSince);
    }

    static File[] findChangedCompletedLinkOpticalLogs(File logDir, long modifiedSince) {
        if (logDir == null || !logDir.isDirectory()) {
            return new File[0];
        }
        File[] logs = logDir.listFiles((dir, name) -> name != null
                && name.toLowerCase(Locale.ROOT).endsWith(".txt")
                && name.toLowerCase(Locale.ROOT).contains(CMDSET_TOKEN.toLowerCase(Locale.ROOT)));
        if (logs == null || logs.length == 0) {
            return new File[0];
        }
        Arrays.sort(logs, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });

        List<File> completed = new ArrayList<File>();
        for (File log : logs) {
            if (!isModifiedAtOrAfter(log, modifiedSince)) {
                continue;
            }
            String cmdSet = extractLinkOpticalCmdSetFromFileName(log.getName());
            if (!cmdSet.isEmpty() && BotGetLog_TrueCorp.isLogCompleteForCmdSet(log, cmdSet)) {
                completed.add(log);
            }
        }
        return completed.toArray(new File[0]);
    }

    static String extractLinkOpticalCmdSetFromFileName(String fileName) {
        String name = safeValue(fileName);
        String lower = name.toLowerCase(Locale.ROOT);
        String token = CMDSET_TOKEN.toLowerCase(Locale.ROOT);
        int tokenStart = lower.lastIndexOf(token);
        if (tokenStart < 0) {
            return "";
        }
        int cmdStart = name.lastIndexOf('_', tokenStart);
        int cmdEnd = tokenStart + CMDSET_TOKEN.length();
        if (cmdStart < 0 || cmdEnd > name.length() || cmdStart + 1 >= cmdEnd) {
            return "";
        }
        return name.substring(cmdStart + 1, cmdEnd);
    }

    static void runConfiguredLinkOpticalExport(PathFile fileInput, Selection selection, String[] args) {
        ExportMode mode = getExportMode(args);
        if (mode == ExportMode.SKIP) {
            System.out.println("[AUTO-LINK] Full Link Optical export skipped for this collection pass; final all-completed publication will export once after checkpoint retry.");
            return;
        }
        if (mode == ExportMode.PRESCAN) {
            runLinkOpticalPreScan(fileInput, selection, getExportSinceFile(args),
                    getNewSiteQueueFile(args));
            return;
        }
        runLinkOpticalExport(fileInput, selection);
    }

    static void runLinkOpticalPreScan(PathFile fileInput, Selection selection, File sinceFile,
            File newSiteQueueFile) {
        if (fileInput == null || selection == null || !selection.isEnabled()) {
            return;
        }
        if (sinceFile == null || !sinceFile.isFile()) {
            System.out.println("[AUTO-LINK] Pre-scan skipped because the run marker file is missing; full export remains deferred to final publication.");
            return;
        }

        File tempDir = null;
        try {
            long modifiedSince = sinceFile.lastModified();
            File[] changedLogs = findChangedCompletedLinkOpticalLogs(
                    new File(fileInput.getLog()), modifiedSince);
            System.out.printf(Locale.ROOT,
                    "[AUTO-LINK] Pre-scan marker=%s modifiedSince=%d selectedChangedLogs=%d%n",
                    sinceFile.getAbsolutePath(), modifiedSince, changedLogs.length);
            if (changedLogs.length == 0) {
                System.out.println("[AUTO-LINK] Pre-scan found no new or modified completed Link Optical logs.");
                writeNewSiteQueue(newSiteQueueFile, Collections.<String>emptyList());
                return;
            }

            File systemLogDir = new File(new File(fileInput.getCurrentFolder(), "_output"), "System_Log");
            systemLogDir.mkdirs();
            tempDir = new File(systemLogDir, ".linkoptical-prescan-" + System.currentTimeMillis());
            if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
                throw new IllegalStateException("Unable to create pre-scan directory: " + tempDir.getAbsolutePath());
            }

            Link_Optical.ProcessResult result = Link_Optical.processFiles(changedLogs, tempDir, false);
            TrueLinkOpticalInputUpdater.UpdateResult updateResult
                    = TrueLinkOpticalInputUpdater.updateFromLinkOptical(fileInput, result);
            if (!updateResult.successful) {
                throw new IllegalStateException(
                        "UserInterface_Input update did not complete; the next-site queue was not replaced.");
            }
            writeNewSiteQueue(newSiteQueueFile, updateResult.addedDevices);
            System.out.printf(Locale.ROOT,
                    "[AUTO-LINK] Pre-scan node update: processedLogs=%d added=%d duplicateIp=%d duplicateDevice=%d duplicateInRun=%d queue=%s%n",
                    result == null ? 0 : result.getTotalFiles(),
                    updateResult.added,
                    updateResult.duplicateIp,
                    updateResult.duplicateDevice,
                    updateResult.duplicateInRun,
                    newSiteQueueFile == null ? "disabled" : newSiteQueueFile.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("[AUTO-LINK] Link Optical pre-scan failed: " + e.getMessage());
        } finally {
            deleteTemporaryTree(tempDir);
        }
    }

    static Link_Optical.ProcessResult runLinkOpticalExport(PathFile fileInput, Selection selection) {
        if (fileInput == null || selection == null || !selection.isEnabled()) {
            return null;
        }
        try {
            File[] selectedLogs = findSelectedCompletedLogs(new File(fileInput.getLog()), selection);
            System.out.printf("[AUTO-LINK] Found %d completed Link Optical log file(s) for export.%n",
                    selectedLogs.length);
            if (selectedLogs.length == 0) {
                showMessage(
                        "TRUE Link Optical completed, but no completed LLDP-Link_OPTIC logs were found for export.",
                        JOptionPane.WARNING_MESSAGE);
                return null;
            }

            File outputDir = new File(new File(fileInput.getCurrentFolder(), "_output"), "LLDP_Neighbor");
            Link_Optical.ProcessResult result = Link_Optical.processFiles(selectedLogs, outputDir, false);
            TrueLinkOpticalInputUpdater.UpdateResult updateResult
                    = TrueLinkOpticalInputUpdater.updateFromLinkOptical(fileInput, result);
            System.out.printf("[AUTO-INPUT] Missing neighbor update summary: added=%d duplicateIp=%d duplicateDevice=%d duplicateInRun=%d%n",
                    updateResult.added,
                    updateResult.duplicateIp,
                    updateResult.duplicateDevice,
                    updateResult.duplicateInRun);
            showExportResult(result);
            return result;
        } catch (Exception e) {
            System.out.println("[AUTO-LINK] Link Optical export failed: " + e.getMessage());
            showMessage("Link Optical export failed.\n" + e.getMessage(), JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    static ExportMode getExportMode(String[] args) {
        String raw = getArgValue(args, ARG_EXPORT_MODE_PREFIX).toLowerCase(Locale.ROOT);
        if (raw.isEmpty() || "full".equals(raw)) {
            return ExportMode.FULL;
        }
        if ("prescan".equals(raw) || "pre-scan".equals(raw)) {
            return ExportMode.PRESCAN;
        }
        if ("skip".equals(raw) || "none".equals(raw)) {
            return ExportMode.SKIP;
        }
        throw new IllegalArgumentException(
                ARG_EXPORT_MODE_PREFIX + " requires full, prescan, or skip.");
    }

    static File getExportSinceFile(String[] args) {
        String value = getArgValue(args, ARG_EXPORT_SINCE_FILE_PREFIX);
        return value.isEmpty() ? null : new File(value);
    }

    static File getNewSiteQueueFile(String[] args) {
        String value = getArgValue(args, ARG_NEW_SITE_QUEUE_FILE_PREFIX);
        return value.isEmpty() ? null : new File(value);
    }

    static boolean shouldSkipGlobalDuplicateCleanup(String[] args) {
        return hasArg(args, ARG_SKIP_GLOBAL_DEDUP);
    }

    static void writeNewSiteQueue(File queueFile, List<String> siteNames) throws Exception {
        if (queueFile == null) {
            return;
        }
        File absoluteQueueFile = queueFile.getAbsoluteFile();
        File parent = absoluteQueueFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException(
                    "Unable to create new-site queue directory: " + parent.getAbsolutePath());
        }

        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (siteNames != null) {
            for (String siteName : siteNames) {
                String normalized = safeValue(siteName).trim();
                if (!normalized.isEmpty()) {
                    unique.add(normalized);
                }
            }
        }

        File tempFile = new File(parent,
                absoluteQueueFile.getName() + ".tmp." + System.nanoTime());
        try {
            Files.write(tempFile.toPath(), new ArrayList<String>(unique), StandardCharsets.UTF_8);
            try {
                Files.move(tempFile.toPath(), absoluteQueueFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), absoluteQueueFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile.toPath());
        }
    }

    static boolean isEnabled(String[] args) {
        return hasArg(args, ARG_ENABLE)
                || hasArg(args, ARG_ALL)
                || hasArg(args, ARG_INCREMENTAL)
                || !getSiteQueriesArg(args).isEmpty()
                || !getSiteQueryArg(args).isEmpty();
    }

    private static boolean hasArg(String[] args, String expected) {
        if (args == null || expected == null) {
            return false;
        }
        for (String arg : args) {
            if (expected.equalsIgnoreCase(safeValue(arg))) {
                return true;
            }
        }
        return false;
    }

    private static String getSiteQueryArg(String[] args) {
        if (args == null) {
            return "";
        }
        for (String arg : args) {
            String value = safeValue(arg);
            if (value.toLowerCase(Locale.ROOT).startsWith(ARG_SITE_PREFIX)) {
                return value.substring(ARG_SITE_PREFIX.length()).trim();
            }
        }
        return "";
    }

    private static String getSiteQueriesArg(String[] args) {
        if (args == null) {
            return "";
        }
        for (String arg : args) {
            String value = safeValue(arg);
            if (value.toLowerCase(Locale.ROOT).startsWith(ARG_SITES_PREFIX)) {
                return value.substring(ARG_SITES_PREFIX.length()).trim();
            }
        }
        return "";
    }

    private static String getNeighborFileArg(String[] args) {
        return getArgValue(args, ARG_NEIGHBOR_FILE_PREFIX);
    }

    private static String getArgValue(String[] args, String prefix) {
        if (args == null || prefix == null) {
            return "";
        }
        for (String arg : args) {
            String value = safeValue(arg);
            if (value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return value.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    static Integer getThreadPoolSizeOverride(String[] args) {
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            String value = safeValue(arg);
            if (!value.toLowerCase(Locale.ROOT).startsWith(ARG_THREADS_PREFIX)) {
                continue;
            }
            String rawThreads = value.substring(ARG_THREADS_PREFIX.length()).trim();
            if (!rawThreads.matches("\\d+")) {
                throw new IllegalArgumentException(
                        ARG_THREADS_PREFIX + " requires a positive integer.");
            }
            try {
                int threads = Integer.parseInt(rawThreads);
                if (threads < 1) {
                    throw new IllegalArgumentException(
                            ARG_THREADS_PREFIX + " requires a value greater than zero.");
                }
                return threads;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        ARG_THREADS_PREFIX + " is outside the supported integer range.", ex);
            }
        }
        return null;
    }

    private static Selection promptMultiSiteSelection(Sheet deviceSheet) {
        String query = "";
        while (true) {
            JTextArea textArea = new JTextArea(query, 10, 48);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(520, 220));
            int answer = JOptionPane.showConfirmDialog(
                    null,
                    scrollPane,
                    "Enter TRUE nodes, IPs, or Excel rows (one per line or comma separated)",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return null;
            }
            query = textArea.getText();
            if (query == null) {
                return null;
            }
            Selection selection = resolveMultiSiteSelection(deviceSheet, splitQueries(query), true);
            if (selection != null) {
                return selection;
            }
        }
    }

    private static Selection resolveAllSiteSelection(Sheet deviceSheet, boolean confirm) {
        Selection selection = buildSelection(deviceSheet, (String) null, true);
        if (selection.getSiteCount() == 0) {
            JOptionPane.showMessageDialog(
                    null,
                    "No TRUE rows with LLDP-Link_OPTIC cmdSet were found.",
                    "TRUE Link Optical Auto",
                    JOptionPane.WARNING_MESSAGE
            );
            return null;
        }
        if (confirm && !confirmSelection(selection,
                "Run TRUE Link Optical for all matching rows?")) {
            return null;
        }
        return selection;
    }

    private static Selection resolveIncrementalSelection(Sheet deviceSheet, String neighborFilePath) {
        File neighborFile = new File(safeValue(neighborFilePath));
        if (!neighborFile.isFile()) {
            System.out.println("[AUTO-LINK] Incremental baseline file not found: "
                    + neighborFile.getAbsolutePath());
            return new Selection(true, false, true, "incremental-down", null, null);
        }

        Selection selection = buildIncrementalSelection(deviceSheet, neighborFile);
        if (selection.getSiteCount() == 0) {
            System.out.println("[AUTO-LINK] Incremental mode found no non-UP link with Description to refresh.");
        }
        return selection;
    }

    private static Selection resolveOneSiteSelection(Sheet deviceSheet, String query, boolean allowRetry) {
        Selection selection = buildSelection(deviceSheet, query, false);
        if (selection.getSiteCount() == 0) {
            JOptionPane.showMessageDialog(
                    null,
                    "No TRUE row matched '" + safeValue(query)
                    + "' with LLDP-Link_OPTIC cmdSet.",
                    "TRUE Link Optical Auto",
                    JOptionPane.WARNING_MESSAGE
            );
            return null;
        }
        if (allowRetry && !confirmSelection(selection, "Run TRUE Link Optical for this selection?")) {
            return null;
        }
        return selection;
    }

    private static Selection resolveMultiSiteSelection(Sheet deviceSheet, List<String> queries, boolean allowRetry) {
        Selection selection = buildSelection(deviceSheet, queries, false);
        if (selection.getSiteCount() == 0) {
            JOptionPane.showMessageDialog(
                    null,
                    "No TRUE row matched the selected nodes with LLDP-Link_OPTIC cmdSet.",
                    "TRUE Link Optical Auto",
                    JOptionPane.WARNING_MESSAGE
            );
            return null;
        }
        if (allowRetry && !confirmSelection(selection, "Run TRUE Link Optical for selected node(s)?")) {
            return null;
        }
        return selection;
    }

    private static boolean confirmSelection(Selection selection, String titleText) {
        StringBuilder message = new StringBuilder();
        message.append(titleText).append("\n\n");
        message.append("Sites: ").append(selection.getSiteCount())
                .append(" | Commands: ").append(selection.getCommandCount()).append("\n");
        if (!selection.getQuery().isEmpty()) {
            message.append("Search: ").append(selection.getQuery()).append("\n");
        }
        message.append("\n");
        for (String preview : selection.getPreviewRows()) {
            message.append(preview).append("\n");
        }
        if (selection.getSiteCount() > selection.getPreviewRows().size()) {
            message.append("... and ")
                    .append(selection.getSiteCount() - selection.getPreviewRows().size())
                    .append(" more row(s).\n");
        }

        int answer = JOptionPane.showConfirmDialog(
                null,
                message.toString(),
                "TRUE Link Optical Auto",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        return answer == JOptionPane.OK_OPTION;
    }

    private static Selection buildSelection(Sheet deviceSheet, String query, boolean allSites) {
        List<String> queries = query == null ? Collections.<String>emptyList() : Collections.singletonList(query);
        return buildSelection(deviceSheet, queries, allSites);
    }

    private static Selection buildSelection(Sheet deviceSheet, List<String> queries, boolean allSites) {
        LinkedHashMap<Integer, List<String>> cmdSetsByRow = new LinkedHashMap<Integer, List<String>>();
        List<String> preview = new ArrayList<String>();
        String queryText = join(queries, ", ");
        if (deviceSheet == null) {
            return new Selection(true, allSites, false, queryText, cmdSetsByRow, preview);
        }

        for (Row row : deviceSheet) {
            if (row == null || row.getRowNum() == 0) {
                continue;
            }
            String device = BotGetLog_TrueCorp.getCell(row, 2);
            String loopback = BotGetLog_TrueCorp.getCell(row, 3);
            if (device.trim().isEmpty() || loopback.trim().isEmpty()) {
                continue;
            }
            if (!allSites && !matchesAnyQuery(row, queries)) {
                continue;
            }

            List<String> cmdSets = readLinkOpticalCmdSets(row);
            if (cmdSets.isEmpty()) {
                continue;
            }

            int rowNum = row.getRowNum() + 1;
            cmdSetsByRow.put(rowNum, cmdSets);
            if (preview.size() < PREVIEW_LIMIT) {
                preview.add(describeRow(row, cmdSets));
            }
        }

        return new Selection(true, allSites, false, queryText, cmdSetsByRow, preview);
    }

    private static Selection buildIncrementalSelection(Sheet deviceSheet, File neighborFile) {
        LinkedHashMap<Integer, List<String>> cmdSetsByRow = new LinkedHashMap<Integer, List<String>>();
        List<String> preview = new ArrayList<String>();
        if (deviceSheet == null || neighborFile == null || !neighborFile.isFile()) {
            return new Selection(true, false, true, "incremental-down", cmdSetsByRow, preview);
        }

        DeviceIndex index = DeviceIndex.fromSheet(deviceSheet);
        int rowsRead = 0;
        int downWithDescription = 0;
        int sourceMatched = 0;
        int targetMatched = 0;
        int targetIpMatched = 0;
        int targetMissing = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(neighborFile))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new Selection(true, false, true, "incremental-down", cmdSetsByRow, preview);
            }

            Map<String, Integer> header = headerIndex(splitCsvLine(headerLine));
            int siteIdx = findIndex(header, "Site code");
            int ipIdx = findIndex(header, "IP loopback");
            int stateIdx = findIndex(header, "Current State");
            int descIdx = findIndex(header, "Description");
            int neighborIdx = findIndex(header, "Neighbor SysName");
            int neighborDesIdx = findIndex(header, "NeighborDes");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                rowsRead++;
                List<String> cols = splitCsvLine(line);
                String state = valueAt(cols, stateIdx);
                String description = valueAt(cols, descIdx);
                if (isUpState(state) || !hasMeaningfulDescription(description)) {
                    continue;
                }
                downWithDescription++;

                String sourceIp = normalizeIp(valueAt(cols, ipIdx));
                String siteCode = valueAt(cols, siteIdx);
                Row sourceRow = firstNonNull(index.byIp.get(sourceIp), index.findByName(siteCode));
                if (addRowSelection(cmdSetsByRow, preview, sourceRow, "source " + state)) {
                    sourceMatched++;
                }

                boolean matchedTarget = false;
                String neighbor = valueAt(cols, neighborIdx);
                String neighborDes = valueAt(cols, neighborDesIdx);
                Row targetRow = firstNonNull(index.findByName(neighborDes), index.findByName(neighbor));
                if (addRowSelection(cmdSetsByRow, preview, targetRow, "target " + state)) {
                    targetMatched++;
                    matchedTarget = true;
                }

                Set<String> ipsInDescription = extractIpv4s(description);
                for (String ip : ipsInDescription) {
                    if (ip.equals(sourceIp)) {
                        continue;
                    }
                    if (addRowSelection(cmdSetsByRow, preview, index.byIp.get(ip), "target-ip " + state)) {
                        targetIpMatched++;
                        matchedTarget = true;
                    }
                }
                if (!matchedTarget) {
                    targetMissing++;
                }
            }
        } catch (Exception e) {
            System.out.println("[AUTO-LINK] Incremental selection failed: " + e.getMessage());
        }

        System.out.printf(Locale.ROOT,
                "[AUTO-LINK] Incremental source=%s rows=%d downWithDescription=%d selectedSites=%d commands=%d sourceMatched=%d targetMatched=%d targetIpMatched=%d targetMissing=%d%n",
                neighborFile.getAbsolutePath(),
                rowsRead,
                downWithDescription,
                cmdSetsByRow.size(),
                countCommands(cmdSetsByRow),
                sourceMatched,
                targetMatched,
                targetIpMatched,
                targetMissing);
        return new Selection(true, false, true, "incremental-down", cmdSetsByRow, preview);
    }

    private static boolean addRowSelection(LinkedHashMap<Integer, List<String>> cmdSetsByRow,
            List<String> preview, Row row, String reason) {
        if (row == null) {
            return false;
        }
        List<String> cmdSets = readLinkOpticalCmdSets(row);
        if (cmdSets.isEmpty()) {
            return false;
        }
        int rowNum = row.getRowNum() + 1;
        if (cmdSetsByRow.containsKey(rowNum)) {
            return false;
        }
        cmdSetsByRow.put(rowNum, cmdSets);
        if (preview.size() < PREVIEW_LIMIT) {
            preview.add(describeRow(row, cmdSets) + " | " + safeValue(reason));
        }
        return true;
    }

    private static int countCommands(Map<Integer, List<String>> cmdSetsByRow) {
        int total = 0;
        if (cmdSetsByRow != null) {
            for (List<String> cmdSets : cmdSetsByRow.values()) {
                if (cmdSets != null) {
                    total += cmdSets.size();
                }
            }
        }
        return total;
    }

    private static Row firstNonNull(Row first, Row second) {
        return first != null ? first : second;
    }

    private static boolean isUpState(String state) {
        return "UP".equalsIgnoreCase(safeValue(state));
    }

    private static boolean hasMeaningfulDescription(String description) {
        String value = safeValue(description);
        if (value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return !"-".equals(value)
                && !"n/a".equals(lower)
                && !"na".equals(lower)
                && !"null".equals(lower);
    }

    private static Set<String> extractIpv4s(String text) {
        Set<String> ips = new LinkedHashSet<String>();
        Matcher matcher = IPV4_PATTERN.matcher(safeValue(text));
        while (matcher.find()) {
            String ip = normalizeIp(matcher.group(1));
            if (!ip.isEmpty()) {
                ips.add(ip);
            }
        }
        return ips;
    }

    private static Map<String, Integer> headerIndex(List<String> columns) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        if (columns != null) {
            for (int i = 0; i < columns.size(); i++) {
                map.put(normalizeHeader(columns.get(i)), i);
            }
        }
        return map;
    }

    private static int findIndex(Map<String, Integer> header, String name) {
        if (header == null) {
            return -1;
        }
        Integer idx = header.get(normalizeHeader(name));
        return idx == null ? -1 : idx.intValue();
    }

    private static String normalizeHeader(String value) {
        return safeValue(value).toLowerCase(Locale.ROOT).replace(" ", "");
    }

    private static String valueAt(List<String> cols, int idx) {
        if (idx < 0 || cols == null || idx >= cols.size()) {
            return "";
        }
        return safeValue(cols.get(idx));
    }

    private static List<String> splitCsvLine(String line) {
        List<String> cols = new ArrayList<String>();
        if (line == null) {
            return cols;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cols.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cols.add(current.toString());
        return cols;
    }

    private static String normalizeDeviceName(String value) {
        String v = safeValue(value).toUpperCase(Locale.ROOT);
        if (v.isEmpty()) {
            return "";
        }
        return v.replaceAll("\\s+", "_");
    }

    private static String normalizeIp(String value) {
        String ip = safeValue(value);
        return isValidIpv4(ip) ? ip : "";
    }

    private static boolean isValidIpv4(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                if (part.length() > 1 && part.startsWith("0")) {
                    return false;
                }
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return !"0.0.0.0".equals(ip) && !"255.255.255.255".equals(ip);
    }

    private static List<String> readLinkOpticalCmdSets(Row row) {
        List<String> cmdSets = new ArrayList<String>();
        if (row == null || row.getLastCellNum() <= 4) {
            return cmdSets;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (int k = 4; k < row.getLastCellNum(); k++) {
            String cmdSet = BotGetLog_TrueCorp.getCell(row, k).trim();
            if (isLinkOpticalCmdSet(cmdSet) && seen.add(cmdSet.toLowerCase(Locale.ROOT))) {
                cmdSets.add(cmdSet);
            }
        }
        return cmdSets;
    }

    private static boolean isLinkOpticalCmdSet(String cmdSet) {
        return safeValue(cmdSet).toLowerCase(Locale.ROOT)
                .contains(CMDSET_TOKEN.toLowerCase(Locale.ROOT));
    }

    private static boolean matchesAnyQuery(Row row, List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return false;
        }
        for (String query : queries) {
            if (matchesQuery(row, query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesQuery(Row row, String query) {
        String q = safeValue(query);
        if (q.isEmpty() || row == null) {
            return false;
        }
        int rowNum = row.getRowNum() + 1;
        String rowText = Integer.toString(rowNum);
        Matcher rowSelector = ROW_SELECTOR_PATTERN.matcher(q);
        if (rowSelector.matches()) {
            return rowText.equals(rowSelector.group(1));
        }
        if (q.matches("\\d+")) {
            return q.equals(rowText);
        }

        String haystack = BotGetLog_TrueCorp.getCell(row, 1) + " "
                + BotGetLog_TrueCorp.getCell(row, 2) + " "
                + BotGetLog_TrueCorp.getCell(row, 3);
        String lowerHaystack = haystack.toLowerCase(Locale.ROOT);
        String lowerQuery = q.toLowerCase(Locale.ROOT);
        if (lowerHaystack.contains(lowerQuery)) {
            return true;
        }
        String compactHaystack = compact(haystack);
        String compactQuery = compact(q);
        return !compactQuery.isEmpty() && compactHaystack.contains(compactQuery);
    }

    private static List<String> splitQueries(String queryText) {
        List<String> queries = new ArrayList<String>();
        String value = safeValue(queryText);
        if (value.isEmpty()) {
            return queries;
        }
        String[] parts = value.split("[,;\\r\\n\\t]+");
        Set<String> seen = new LinkedHashSet<String>();
        for (String part : parts) {
            String query = safeValue(part);
            if (!query.isEmpty() && seen.add(query.toLowerCase(Locale.ROOT))) {
                queries.add(query);
            }
        }
        return queries;
    }

    private static String describeRow(Row row, List<String> cmdSets) {
        int rowNum = row.getRowNum() + 1;
        return "Row " + rowNum + " | "
                + BotGetLog_TrueCorp.getCell(row, 2) + " | "
                + BotGetLog_TrueCorp.getCell(row, 3) + " | "
                + join(cmdSets, ", ");
    }

    private static File findLatestCompleteLog(List<File> rowLogs, int rowNum, String cmdSet) {
        if (rowLogs == null || cmdSet == null) {
            return null;
        }
        String prefix = "[" + rowNum + "]";
        String marker = "_" + cmdSet + "_";
        for (File log : rowLogs) {
            if (log == null) {
                continue;
            }
            String name = log.getName();
            if (!name.startsWith(prefix) || !name.contains(marker) || !name.contains("LLDP")) {
                continue;
            }
            if (BotGetLog_TrueCorp.isLogCompleteForCmdSet(log, cmdSet)) {
                return log;
            }
        }
        return null;
    }

    private static Set<String> selectedIps(Selection selection, Sheet deviceSheet) {
        Set<String> selectedIps = new LinkedHashSet<String>();
        if (selection == null || deviceSheet == null) {
            return selectedIps;
        }
        for (Integer rowNum : selection.getCmdSetsByRow().keySet()) {
            if (rowNum == null || rowNum.intValue() < 1) {
                continue;
            }
            Row row = deviceSheet.getRow(rowNum.intValue() - 1);
            String ip = normalizeIp(BotGetLog_TrueCorp.getCell(row, 3));
            if (!ip.isEmpty()) {
                selectedIps.add(ip);
            }
        }
        return selectedIps;
    }

    static boolean isSelectedLinkOpticalLog(File log, Set<String> selectedIps) {
        if (log == null || selectedIps == null || selectedIps.isEmpty()) {
            return false;
        }
        String name = log.getName();
        if (!name.toLowerCase(Locale.ROOT).contains(CMDSET_TOKEN.toLowerCase(Locale.ROOT))) {
            return false;
        }
        Matcher matcher = LOG_IP_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return false;
        }
        String logIp = normalizeIp(matcher.group(1));
        return !logIp.isEmpty() && selectedIps.contains(logIp);
    }

    private static void showExportResult(Link_Optical.ProcessResult result) {
        if (result == null || result.getTotalFiles() <= 0) {
            return;
        }
        StringBuilder message = new StringBuilder();
        message.append("Generated Link Optical files from ")
                .append(result.getTotalFiles())
                .append(" log file(s):\n\n");
        for (File output : result.getOutputFiles()) {
            message.append(output.getName()).append("\n");
        }
        showMessage(message.toString(), JOptionPane.INFORMATION_MESSAGE);
    }

    private static void showMessage(String message, int messageType) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[AUTO-LINK] " + safeValue(message).replace('\n', ' '));
            return;
        }
        JOptionPane.showMessageDialog(
                null,
                message,
                "TRUE Link Optical Auto",
                messageType
        );
    }

    private static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (sb.length() > 0) {
                    sb.append(separator);
                }
                sb.append(value);
            }
        }
        return sb.toString();
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception e) {
            return file == null ? "" : file.getAbsolutePath();
        }
    }

    private static void deleteTemporaryTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTemporaryTree(child);
            }
        }
        if (!file.delete() && file.exists()) {
            System.out.println("[AUTO-LINK] Unable to remove temporary pre-scan file: "
                    + file.getAbsolutePath());
        }
    }

    private static String compact(String value) {
        StringBuilder sb = new StringBuilder();
        String source = safeValue(value).toUpperCase(Locale.ROOT);
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class DeviceIndex {

        final Map<String, Row> byIp = new LinkedHashMap<String, Row>();
        final Map<String, Row> byName = new LinkedHashMap<String, Row>();
        final Map<String, Row> byCompactName = new LinkedHashMap<String, Row>();

        static DeviceIndex fromSheet(Sheet sheet) {
            DeviceIndex index = new DeviceIndex();
            if (sheet == null) {
                return index;
            }
            for (Row row : sheet) {
                if (row == null || row.getRowNum() == 0) {
                    continue;
                }
                String device = normalizeDeviceName(BotGetLog_TrueCorp.getCell(row, 2));
                String ip = normalizeIp(BotGetLog_TrueCorp.getCell(row, 3));
                if (!ip.isEmpty() && !index.byIp.containsKey(ip)) {
                    index.byIp.put(ip, row);
                }
                addName(index, device, row);
                addName(index, BotGetLog_TrueCorp.getCell(row, 1), row);
            }
            return index;
        }

        Row findByName(String name) {
            String normalized = normalizeDeviceName(name);
            if (normalized.isEmpty()) {
                return null;
            }
            Row row = byName.get(normalized);
            if (row != null) {
                return row;
            }
            String compactName = compact(normalized);
            if (compactName.isEmpty()) {
                return null;
            }
            row = byCompactName.get(compactName);
            if (row != null) {
                return row;
            }
            for (Map.Entry<String, Row> entry : byCompactName.entrySet()) {
                String key = entry.getKey();
                if (key.contains(compactName) || compactName.contains(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private static void addName(DeviceIndex index, String name, Row row) {
            String normalized = normalizeDeviceName(name);
            if (normalized.isEmpty()) {
                return;
            }
            if (!index.byName.containsKey(normalized)) {
                index.byName.put(normalized, row);
            }
            String compactName = compact(normalized);
            if (!compactName.isEmpty() && !index.byCompactName.containsKey(compactName)) {
                index.byCompactName.put(compactName, row);
            }
        }
    }
}
