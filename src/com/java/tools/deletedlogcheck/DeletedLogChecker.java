package com.java.tools.deletedlogcheck;

import java.awt.GraphicsEnvironment;
import com.java.botgetlog.dtac.BotGetLog_DTAC;
import com.java.botgetlog.truecorp.BotGetLog_TrueCorp;
import com.java.shared.AppConsole;
import com.java.shared.AppMetadata;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

public class DeletedLogChecker {

    private static final Pattern ROW_PATTERN = Pattern.compile("^\\[(\\d+)]");
    private static final Pattern DELETED_TAG_PATTERN =
            Pattern.compile("(?i)^(.+_deleted_\\d{8}_\\d{9})(?:_.+)?$");
    private static final Pattern LOG_REQUEST_PATTERN =
            Pattern.compile("^\\[(\\d+)](.+)_(\\d{4}-\\d{2}-\\d{2})(?:_deleted_.*)?\\.txt$",
                    Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final long DEFAULT_RERUN_WAIT_TIMEOUT_MS = 2L * 60L * 60L * 1000L;

    public static void main(String[] args) {
        AppConsole.install("Deleted Log Check Console", "Deleted Log Check - Console");
        setLookAndFeel();
        System.out.println("[INFO] Deleted Log Check started");

        int exitCode = 0;
        try {
            File outputDir = AppMetadata.getOutputDirectory();
            File deletedDir = new File(outputDir, "Deleted_Log");
            File totalLogDir = new File(outputDir, "Total_Log");

            System.out.println("[PATH] Deleted_Log: " + deletedDir.getAbsolutePath());
            System.out.println("[PATH] Total_Log  : " + totalLogDir.getAbsolutePath());

            CheckResult result = check(deletedDir, totalLogDir);
            RerunTarget rerunTarget = chooseRerunTarget(result);
            RerunResult rerunResult = triggerMissingReruns(result, rerunTarget);
            waitForRerunCompletion(rerunResult);
            Path reportPath = writeReport(result);
            printResult(result, reportPath, rerunResult);
            showSummary(result, rerunResult, reportPath);
        } catch (Exception e) {
            exitCode = 1;
            System.out.println("[ERROR] Deleted Log Check failed: " + e.getMessage());
            showError(e);
        } finally {
            closeConsoleAndExit(exitCode);
        }
    }

    private static void closeConsoleAndExit(int exitCode) {
        AppConsole.close();
        System.exit(exitCode);
    }

    private static CheckResult check(File deletedDir, File totalLogDir) {
        List<File> deletedFiles = listTextFiles(deletedDir);
        List<File> totalFiles = listTextFiles(totalLogDir);
        TotalLogIndex totalIndex = TotalLogIndex.from(totalFiles);
        List<RowCheck> rows = new ArrayList<RowCheck>();

        for (File deletedFile : deletedFiles) {
            String row = extractRow(deletedFile.getName());
            String lookupKey = normalizeLogName(deletedFile.getName());
            List<File> rowFiles = row.isEmpty()
                    ? Collections.<File>emptyList()
                    : totalIndex.byRow.get(row);

            if (rowFiles != null && !rowFiles.isEmpty()) {
                rows.add(RowCheck.rowFound(row, deletedFile, lookupKey, rowFiles));
            } else {
                rows.add(RowCheck.missing(row, deletedFile, lookupKey));
            }
        }

        return new CheckResult(deletedFiles.size(), totalFiles.size(), rows);
    }

    private static List<File> listTextFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = dir.listFiles((parent, name) ->
                name != null && name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<File> list = new ArrayList<File>();
        Collections.addAll(list, files);
        Collections.sort(list, new Comparator<File>() {
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return list;
    }

    private static String extractRow(String fileName) {
        if (fileName == null) {
            return "";
        }
        Matcher matcher = ROW_PATTERN.matcher(fileName.trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String normalizeLogName(String fileName) {
        String base = fileName == null ? "" : fileName.trim();
        if (base.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            base = base.substring(0, base.length() - 4);
        }

        Matcher matcher = DELETED_TAG_PATTERN.matcher(base);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return base;
    }

    private static RerunTarget chooseRerunTarget(CheckResult result) {
        if (result == null || result.missingCount() == 0) {
            return RerunTarget.NONE;
        }

        String configured = System.getProperty("deletedlogcheck.rerunBot", "").trim();
        if (configured.equalsIgnoreCase("true")) {
            return RerunTarget.TRUE;
        }
        if (configured.equalsIgnoreCase("dtac")) {
            return RerunTarget.DTAC;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[RERUN-SKIP] Missing rows found, but rerun bot was not selected in headless mode.");
            return RerunTarget.NONE;
        }

        Object[] options = {"TRUE", "DTAC", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                null,
                "Missing rows were found in Total_Log.\nSelect which bot should auto rerun these rows.",
                "Deleted Log Check - Auto Rerun",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 0) {
            return RerunTarget.TRUE;
        }
        if (choice == 1) {
            return RerunTarget.DTAC;
        }
        return RerunTarget.NONE;
    }

    private static RerunResult triggerMissingReruns(CheckResult result, RerunTarget rerunTarget) {
        RerunResult rerunResult = new RerunResult();
        rerunResult.target = rerunTarget == null ? RerunTarget.NONE : rerunTarget;
        if (result == null || result.rows == null || result.rows.isEmpty()) {
            return rerunResult;
        }

        Set<String> requestedKeys = new LinkedHashSet<String>();
        List<RerunCandidate> candidates = new ArrayList<RerunCandidate>();
        for (RowCheck row : result.rows) {
            if (row == null || row.found) {
                continue;
            }

            if (rerunResult.target == RerunTarget.NONE) {
                row.markRerunSkipped("rerun bot was not selected");
                rerunResult.skipped++;
                continue;
            }

            LogRequest request = parseLogRequest(row.deletedFile);
            if (request == null) {
                row.markRerunSkipped("cannot parse deleted log filename");
                rerunResult.skipped++;
                System.out.println("[RERUN-SKIP] Cannot parse deleted log filename: "
                        + row.deletedFile.getName());
                continue;
            }

            String rerunKey = request.rowKey();
            if (!requestedKeys.add(rerunKey)) {
                row.markRerunSkipped("duplicate missing row in this check");
                rerunResult.skipped++;
                System.out.println("[RERUN-SKIP] Duplicate missing request: "
                        + request.describe());
                continue;
            }

            candidates.add(new RerunCandidate(row, request));
        }

        if (candidates.isEmpty()) {
            return rerunResult;
        }

        if (!prepareRerunSession(rerunResult.target, candidates)) {
            for (RerunCandidate candidate : candidates) {
                candidate.row.markRerunSkipped("rerun login was cancelled or validation failed");
                rerunResult.skipped++;
            }
            return rerunResult;
        }

        for (RerunCandidate candidate : candidates) {
            LogRequest request = candidate.request;
            RowCheck row = candidate.row;
            try {
                row.markRerunRequested(request.describe());
                rerunResult.requested++;
                System.out.println("[RERUN] Missing in Total_Log, auto re-run requested: "
                        + request.describe() + " | bot=" + rerunResult.target.label);
                rerunNode(rerunResult.target, request);
            } catch (Exception | LinkageError e) {
                row.markRerunFailed(e.getMessage());
                rerunResult.failed++;
                System.out.println("[RERUN-FAIL] " + request.describe()
                        + " -> " + e.getMessage());
            }
        }

        return rerunResult;
    }

    private static void waitForRerunCompletion(RerunResult rerunResult) {
        if (rerunResult == null || rerunResult.requested <= 0) {
            return;
        }

        long timeoutMs = Long.getLong("deletedlogcheck.rerunWaitTimeoutMs",
                DEFAULT_RERUN_WAIT_TIMEOUT_MS);
        System.out.printf("[INFO] Waiting for %s rerun task(s) to finish (timeout %d sec)%n",
                rerunResult.target.label,
                Math.max(1L, timeoutMs / 1000L));

        boolean completed;
        if (rerunResult.target == RerunTarget.DTAC) {
            completed = BotGetLog_DTAC.waitForRerunTasksToFinish(timeoutMs);
        } else if (rerunResult.target == RerunTarget.TRUE) {
            completed = BotGetLog_TrueCorp.waitForRerunTasksToFinish(timeoutMs);
        } else {
            completed = true;
        }

        rerunResult.waitTimedOut = !completed;
        if (completed) {
            System.out.println("[INFO] Auto rerun tasks finished.");
        } else {
            System.out.println("[WARN] Timed out waiting for auto rerun tasks to finish.");
        }
    }

    private static boolean prepareRerunSession(RerunTarget rerunTarget, List<RerunCandidate> candidates) {
        if (rerunTarget == null || rerunTarget == RerunTarget.NONE) {
            return false;
        }
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }

        int[] rowNums = new int[candidates.size()];
        String[] loopbacks = new String[candidates.size()];
        String[] devices = new String[candidates.size()];
        String[] cmdSets = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            LogRequest request = candidates.get(i).request;
            rowNums[i] = request.rowNum;
            loopbacks[i] = request.loopback;
            devices[i] = request.device;
            cmdSets[i] = request.cmdSet;
        }

        if (rerunTarget == RerunTarget.DTAC) {
            return BotGetLog_DTAC.prepareRerunSession(rowNums, loopbacks, devices, cmdSets);
        }
        return BotGetLog_TrueCorp.prepareRerunSession(rowNums, loopbacks, devices, cmdSets);
    }

    private static void rerunNode(RerunTarget rerunTarget, LogRequest request) {
        if (rerunTarget == RerunTarget.DTAC) {
            BotGetLog_DTAC.RerunNode(
                    request.rowNum,
                    request.loopback,
                    request.device,
                    request.cmdSet);
            return;
        }
        BotGetLog_TrueCorp.RerunNode(
                request.rowNum,
                request.loopback,
                request.device,
                request.cmdSet);
    }

    private static LogRequest parseLogRequest(File deletedFile) {
        if (deletedFile == null) {
            return null;
        }

        Matcher matcher = LOG_REQUEST_PATTERN.matcher(deletedFile.getName());
        if (!matcher.matches()) {
            return null;
        }

        String identity = matcher.group(2);
        int firstUnderscore = identity.indexOf('_');
        int secondUnderscore = identity.indexOf('_', firstUnderscore + 1);
        if (firstUnderscore <= 0 || secondUnderscore <= firstUnderscore) {
            return null;
        }

        String rowText = matcher.group(1);
        String loopback = identity.substring(0, firstUnderscore).trim();
        String device = identity.substring(firstUnderscore + 1, secondUnderscore).trim();
        String cmdSet = identity.substring(secondUnderscore + 1).trim();
        if (loopback.isEmpty() || device.isEmpty() || cmdSet.isEmpty()) {
            return null;
        }

        try {
            int rowNum = Integer.parseInt(rowText);
            return new LogRequest(rowNum, loopback, device, cmdSet);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path writeReport(CheckResult result) throws IOException {
        File workDir = AppMetadata.getBotWorkLogDirectory();
        Files.createDirectories(workDir.toPath());

        Path reportPath = new File(workDir,
                "Deleted_Log_Check_" + LocalDateTime.now().format(REPORT_TIME) + ".csv").toPath();
        StringBuilder csv = new StringBuilder();
        csv.append("row,status,matchType,deletedFile,lookupKey,totalLogMatch,note,autoRerunStatus,autoRerunNote\n");
        for (RowCheck row : result.rows) {
            csv.append(csv(row.row)).append(',')
                    .append(csv(row.statusLabel())).append(',')
                    .append(csv(row.matchType)).append(',')
                    .append(csv(row.deletedFile.getName())).append(',')
                    .append(csv(row.lookupKey)).append(',')
                    .append(csv(row.totalLogMatch)).append(',')
                    .append(csv(row.note)).append(',')
                    .append(csv(row.autoRerunStatus)).append(',')
                    .append(csv(row.autoRerunNote)).append('\n');
        }

        Files.write(reportPath,
                csv.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return reportPath;
    }

    private static void printResult(CheckResult result, Path reportPath, RerunResult rerunResult) {
        if (result.deletedCount == 0) {
            System.out.println("[WARN] No .txt files found in Deleted_Log");
        }

        System.out.println("[INFO] Deleted_Log files: " + result.deletedCount);
        System.out.println("[INFO] Total_Log files  : " + result.totalLogCount);

        for (RowCheck row : result.rows) {
            if (row.found) {
                System.out.println("[OK] [" + row.row + "] found in Total_Log, skip connect fail check"
                        + " | " + row.deletedFile.getName()
                        + " -> " + row.totalLogMatch);
            } else {
                System.out.println("[MISSING] [" + row.row + "] not found in Total_Log"
                        + " | " + row.deletedFile.getName()
                        + " | autoRerun=" + row.autoRerunStatus);
            }
        }

        System.out.println("[SUMMARY] found=" + result.foundCount()
                + " missing=" + result.missingCount()
                + " deletedChecked=" + result.deletedCount
                + " autoRerunBot=" + rerunResult.target.label
                + " autoRerunRequested=" + rerunResult.requested
                + " autoRerunSkipped=" + rerunResult.skipped
                + " autoRerunFailed=" + rerunResult.failed
                + " autoRerunWaitTimedOut=" + rerunResult.waitTimedOut);
        System.out.println("[REPORT] " + reportPath.toAbsolutePath());
    }

    private static void showSummary(CheckResult result, RerunResult rerunResult, Path reportPath) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        String statusLine = rerunResult.requested > 0
                ? (rerunResult.waitTimedOut
                        ? "Deleted Log Check rerun wait timed out\n"
                        : "Deleted Log Check rerun complete\n")
                : "Deleted Log Check complete\n";
        String message = statusLine
                + "Deleted_Log checked: " + result.deletedCount + "\n"
                + "Found in Total_Log: " + result.foundCount() + "\n"
                + "Missing in Total_Log: " + result.missingCount() + "\n"
                + "Auto rerun bot: " + rerunResult.target.label + "\n"
                + "Auto rerun requested: " + rerunResult.requested + "\n"
                + "Auto rerun skipped: " + rerunResult.skipped + "\n"
                + "Auto rerun failed: " + rerunResult.failed + "\n"
                + "Report: " + reportPath.toAbsolutePath();
        JOptionPane.showMessageDialog(
                null,
                message,
                "Deleted Log Check",
                result.missingCount() == 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
        );
    }

    private static void showError(Exception e) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JOptionPane.showMessageDialog(
                null,
                "Deleted Log Check failed\n" + e.getMessage(),
                "Deleted Log Check",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception ignored) {
        }
    }

    private static final class TotalLogIndex {
        final Map<String, List<File>> byRow = new LinkedHashMap<String, List<File>>();

        static TotalLogIndex from(List<File> totalFiles) {
            TotalLogIndex index = new TotalLogIndex();
            for (File file : totalFiles) {
                String row = extractRow(file.getName());
                if (!row.isEmpty()) {
                    List<File> rowFiles = index.byRow.get(row);
                    if (rowFiles == null) {
                        rowFiles = new ArrayList<File>();
                        index.byRow.put(row, rowFiles);
                    }
                    rowFiles.add(file);
                }
            }
            return index;
        }
    }

    private enum RerunTarget {
        NONE("Not selected"),
        TRUE("TRUE"),
        DTAC("DTAC");

        final String label;

        RerunTarget(String label) {
            this.label = label;
        }
    }

    private static final class RerunResult {
        RerunTarget target = RerunTarget.NONE;
        int requested;
        int skipped;
        int failed;
        boolean waitTimedOut;
    }

    private static final class RerunCandidate {
        final RowCheck row;
        final LogRequest request;

        RerunCandidate(RowCheck row, LogRequest request) {
            this.row = row;
            this.request = request;
        }
    }

    private static final class LogRequest {
        final int rowNum;
        final String loopback;
        final String device;
        final String cmdSet;

        LogRequest(int rowNum, String loopback, String device, String cmdSet) {
            this.rowNum = rowNum;
            this.loopback = loopback;
            this.device = device;
            this.cmdSet = cmdSet;
        }

        String rowKey() {
            return String.valueOf(rowNum);
        }

        String describe() {
            return "Row " + rowNum + " | " + loopback + " | " + device + " | " + cmdSet;
        }
    }

    private static final class CheckResult {
        final int deletedCount;
        final int totalLogCount;
        final List<RowCheck> rows;

        CheckResult(int deletedCount, int totalLogCount, List<RowCheck> rows) {
            this.deletedCount = deletedCount;
            this.totalLogCount = totalLogCount;
            this.rows = rows;
        }

        int foundCount() {
            int count = 0;
            for (RowCheck row : rows) {
                if (row.found) {
                    count++;
                }
            }
            return count;
        }

        int missingCount() {
            return rows.size() - foundCount();
        }
    }

    private static final class RowCheck {
        final String row;
        final File deletedFile;
        final String lookupKey;
        final boolean found;
        final String matchType;
        final String totalLogMatch;
        final String note;
        String autoRerunStatus;
        String autoRerunNote;

        private RowCheck(String row, File deletedFile, String lookupKey,
                boolean found, String matchType, String totalLogMatch, String note) {
            this.row = row == null || row.isEmpty() ? "-" : row;
            this.deletedFile = deletedFile;
            this.lookupKey = lookupKey;
            this.found = found;
            this.matchType = matchType;
            this.totalLogMatch = totalLogMatch == null ? "" : totalLogMatch;
            this.note = note == null ? "" : note;
            this.autoRerunStatus = found ? "SKIPPED_FOUND" : "PENDING";
            this.autoRerunNote = found ? "found in Total_Log" : "";
        }

        static RowCheck rowFound(String row, File deletedFile, String lookupKey, List<File> rowFiles) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < rowFiles.size(); i++) {
                if (i > 0) {
                    names.append(" | ");
                }
                names.append(rowFiles.get(i).getName());
            }
            return new RowCheck(row, deletedFile, lookupKey, true, "ROW_FOUND",
                    names.toString(), "row exists in Total_Log; connect fail check skipped");
        }

        static RowCheck missing(String row, File deletedFile, String lookupKey) {
            return new RowCheck(row, deletedFile, lookupKey, false, "NOT_FOUND",
                    "", "row not found in Total_Log");
        }

        String statusLabel() {
            return found ? "FOUND_SKIP_CONNECT_FAIL" : "MISSING";
        }

        void markRerunRequested(String detail) {
            autoRerunStatus = "REQUESTED";
            autoRerunNote = detail == null ? "" : detail;
        }

        void markRerunSkipped(String detail) {
            autoRerunStatus = "SKIPPED";
            autoRerunNote = detail == null ? "" : detail;
        }

        void markRerunFailed(String detail) {
            autoRerunStatus = "FAILED";
            autoRerunNote = detail == null ? "" : detail;
        }
    }
}
