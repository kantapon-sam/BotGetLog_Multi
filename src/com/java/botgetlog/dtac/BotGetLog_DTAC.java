package com.java.botgetlog.dtac;

import org.apache.poi.ss.usermodel.*;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Robot;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Point;
import java.awt.Toolkit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * โปรแกรมหลัก:
 *  - อ่าน UserInterface_Input.xlsx
 *      - _setting  → SshUsername / sshPassword
 *      - deviceList → row ที่เป็น Y + cmdSet แต่ละคอลัมน์
 *      - cmdSet    → ใช้ใน SSH_Multi
 *  - ยิงงานแบบ multi-thread ด้วย ExecutorService
 *  - StopProgram GUI ใช้ดู progress และกดหยุดได้
 */
public class BotGetLog_DTAC {

    private static final PathFile fileInput = new PathFile();
    private static final Dialog dialog = new Dialog();
    private static final String SETTING_SHEET = "_setting_DTAC";
    private static final String DEVICE_SHEET = "deviceList_DTAC";
    private static final String CMDSET_SHEET = "cmdSet";

    private static final int MAX_RERUN_IF_LOG_INCOMPLETE = 1;
    private static final int FIRST_COMMAND_HEAD_BYTES_TO_SCAN = 128 * 1024;
    private static final int FIRST_COMMAND_HEAD_LINES_TO_SCAN = 120;
    private static final int LAST_COMMAND_TAIL_BYTES_TO_SCAN = 256 * 1024;
    private static final int LAST_COMMAND_TAIL_LINES_TO_SCAN = 200;
    private static final int EXCEL_OPEN_MAX_RETRY = 3;
    private static final long EXCEL_OPEN_RETRY_DELAY_MS = 3000L;
    private static final int DEFAULT_THREAD_POOL_SIZE = 6;
    private static final int MAX_THREAD_POOL_SIZE = 30;
    private static final int TURBO_THREAD_MULTIPLIER = 2;
    private static final int MAX_VALIDATION_NODES = 6;
    private static final List<String> DEFAULT_VALIDATION_IPS = Collections.unmodifiableList(Arrays.asList(
            "10.242.0.66",
            "10.242.0.191",
            "10.242.0.206",
            "10.240.80.6",
            "10.240.0.1",
            "10.241.128.1"
    ));
    private static final DateTimeFormatter SUMMARY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter SUMMARY_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static ThreadPoolExecutor executor;
    private static final AtomicInteger completedTasks = new AtomicInteger(0);
    private static final AtomicInteger successTaskCount = new AtomicInteger(0);
    private static final AtomicInteger failedTaskCount = new AtomicInteger(0);
    private static final AtomicInteger authFailedTaskCount = new AtomicInteger(0);
    private static final AtomicInteger networkFailedTaskCount = new AtomicInteger(0);
    private static final AtomicInteger commandIncompleteTaskCount = new AtomicInteger(0);
    private static final AtomicInteger vendorMismatchTaskCount = new AtomicInteger(0);
    private static final AtomicInteger logMissingTaskCount = new AtomicInteger(0);
    private static final AtomicInteger validationMissingTaskCount = new AtomicInteger(0);
    private static final AtomicInteger stoppedTaskCount = new AtomicInteger(0);
    private static final AtomicInteger unknownFailedTaskCount = new AtomicInteger(0);
    private static volatile int totalTasks = 0;
    private static volatile boolean stopRequested = false;
    private static volatile boolean alarmEnabled = true;
    private static volatile int normalThreadPoolSize = DEFAULT_THREAD_POOL_SIZE;
    private static volatile int currentThreadPoolSize = DEFAULT_THREAD_POOL_SIZE;
    private static volatile boolean turboMode = false;

    private static final class CredentialInput {
        final String username;
        final String password;

        CredentialInput(String username, String password) {
            this.username = username == null ? "" : username.trim();
            this.password = password == null ? "" : password.trim();
        }
    }

    private enum FailureType {
        SUCCESS,
        AUTH_FAILED,
        NETWORK_FAILED,
        COMMAND_INCOMPLETE,
        VENDOR_MISMATCH,
        LOG_MISSING,
        VALIDATION_MISSING,
        STOPPED,
        UNKNOWN
    }

    private static final class ValidationProbe {
        final String label;
        final String ip;

        ValidationProbe(String label, String ip) {
            this.label = label == null ? "" : label.trim();
            this.ip = ip == null ? "" : ip.trim();
        }
    }

    private static final class CredentialValidationReport {
        final int total;
        final int success;
        final int authFailure;
        final List<String> messages;

        CredentialValidationReport(int total, int success, int authFailure, List<String> messages) {
            this.total = total;
            this.success = success;
            this.authFailure = authFailure;
            this.messages = (messages != null)
                    ? Collections.unmodifiableList(new ArrayList<>(messages))
                    : Collections.emptyList();
        }

        boolean hasSuccess() {
            return success > 0;
        }

        boolean hasAuthFailure() {
            return authFailure > 0;
        }

        String shortDetail() {
            if (messages.isEmpty()) return "-";
            StringBuilder sb = new StringBuilder();
            int max = Math.min(messages.size(), 6);
            for (int i = 0; i < max; i++) {
                if (sb.length() > 0) sb.append("<br>");
                sb.append(messages.get(i));
            }
            return sb.toString();
        }
    }

    private static final class TaskRunResult {
        final DeviceTask task;
        final boolean success;
        final FailureType failureType;
        final String message;
        final int attempts;
        final String latestLogName;

        private TaskRunResult(DeviceTask task, boolean success, FailureType failureType,
                String message, int attempts, File latestLog) {
            this.task = task;
            this.success = success;
            this.failureType = failureType == null ? FailureType.UNKNOWN : failureType;
            this.message = (message == null || message.trim().isEmpty()) ? "-" : message.trim();
            this.attempts = Math.max(0, attempts);
            this.latestLogName = latestLog == null ? "" : latestLog.getName();
        }

        static TaskRunResult success(DeviceTask task, int attempts, File latestLog) {
            return new TaskRunResult(task, true, FailureType.SUCCESS,
                    "Completed", attempts, latestLog);
        }

        static TaskRunResult failure(DeviceTask task, FailureType failureType,
                String message, int attempts, File latestLog) {
            return new TaskRunResult(task, false, failureType, message, attempts, latestLog);
        }

        static TaskRunResult stopped(DeviceTask task, int attempts) {
            return new TaskRunResult(task, false, FailureType.STOPPED,
                    "Stopped by user", attempts, null);
        }

        String statusLabel() {
            return success ? "SUCCESS" : failureType.name();
        }
    }

    // ======================================================
    // Anti-sleep helper: match TRUE behavior with mouse nudge and optional beep.
    // ======================================================
    private static void startMouseJiggler() {
        Thread t = new Thread(() -> {
            try {
                Robot robot = new Robot();
                int toggle = 1;
                while (!isStopRequested()) {
                    PointerInfo pi = MouseInfo.getPointerInfo();
                    if (pi != null) {
                        Point p = pi.getLocation();
                        robot.mouseMove(p.x + toggle, p.y + toggle);
                        robot.mouseMove(p.x, p.y);
                        toggle = -toggle;

                        boolean beeped = isAlarmEnabled();
                        if (beeped) {
                            Toolkit.getDefaultToolkit().beep();
                        }

                        System.out.println("[ANTI-SLEEP] DTAC mouse moved"
                                + (beeped ? " & beeped" : "")
                                + " at " + LocalDateTime.now().format(SUMMARY_TIMESTAMP_FORMAT));
                    }

                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException ie) {
                        break;
                    }

                }
            } catch (Exception e) {
                System.out.println("[WARN] Mouse jiggler error: " + e.getMessage());
            }
        }, "MouseJiggler");
        t.setDaemon(true);
        t.start();
    }

    // ======================================================
    private static Set<String> loadExistingLogNames() {
        Set<String> names = new HashSet<>();
        File dir = new File(fileInput.getLog());
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) names.add(f.getName());
            }
        }
        return names;
    }

    private static TaskValidationInfo buildTaskValidationInfo(String cmdSet, Workbook workbook) {
        if (cmdSet == null) {
            return new TaskValidationInfo(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        String baseCmdSet = cmdSet.trim();
        if (baseCmdSet.isEmpty()) {
            return new TaskValidationInfo(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        List<String> cmdSetCandidates = buildCmdSetCandidates(baseCmdSet);
        CommandBoundary boundary = loadTaskBoundaryCommands(workbook, cmdSetCandidates);
        return new TaskValidationInfo(cmdSetCandidates, boundary.firstCommands, boundary.lastCommands);
    }

    private static boolean isTaskAlreadyDone(int excelRow, TaskValidationInfo validationInfo, Set<String> existingLogs) {
        if (validationInfo == null
                || validationInfo.cmdSetCandidates.isEmpty()
                || validationInfo.firstCommands.isEmpty()
                || validationInfo.lastCommands.isEmpty()) {
            return false;
        }

        String prefix = "[" + excelRow + "]";
        for (String name : existingLogs) {
            if (!name.startsWith(prefix)) continue;
            if (!matchesAnyCmdSetName(name, validationInfo.cmdSetCandidates)) continue;

            File logFile = new File(fileInput.getLog(), name);
            if (!logFile.isFile()) continue;

            if (isConnectFailureLog(logFile)) {
                deleteLogFileQuietly(logFile, "stale connect failure");
                continue;
            }

            if (containsPromptPlusFirstAndLastCommand(logFile, validationInfo.firstCommands, validationInfo.lastCommands)) {
                return true;
            }

            deleteLogFileQuietly(logFile, "missing command boundary");
        }
        return false;
    }

    private static List<String> buildCmdSetCandidates(String baseCmdSet) {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        String trimmed = (baseCmdSet != null) ? baseCmdSet.trim() : "";
        if (trimmed.isEmpty()) return new ArrayList<>();

        names.add(trimmed);

        int idx = trimmed.indexOf("-");
        String suffix = (idx >= 0) ? trimmed.substring(idx) : ("-" + trimmed);
        names.add("HW" + suffix);
        names.add("ZTE" + suffix);

        List<String> sanitized = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            sanitized.add(name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        }
        return sanitized;
    }

    private static boolean matchesAnyCmdSetName(String fileName, List<String> cmdSetCandidates) {
        if (fileName == null || cmdSetCandidates == null || cmdSetCandidates.isEmpty()) return false;

        for (String cmdSetName : cmdSetCandidates) {
            String mid = "_" + cmdSetName + "_";
            if (fileName.contains(mid)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> loadLastCommands(Workbook workbook, List<String> cmdSetCandidates) {
        return loadTaskBoundaryCommands(workbook, cmdSetCandidates).lastCommands;
    }

    private static CommandBoundary loadTaskBoundaryCommands(Workbook workbook, List<String> cmdSetCandidates) {
        LinkedHashSet<String> firsts = new LinkedHashSet<>();
        LinkedHashSet<String> lasts = new LinkedHashSet<>();

        if (workbook == null || cmdSetCandidates == null || cmdSetCandidates.isEmpty()) {
            return new CommandBoundary(new ArrayList<>(), new ArrayList<>());
        }

        Sheet sheet = workbook.getSheet(CMDSET_SHEET);
        if (sheet == null) return new CommandBoundary(new ArrayList<>(), new ArrayList<>());

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return new CommandBoundary(new ArrayList<>(), new ArrayList<>());

        DataFormatter formatter = new DataFormatter();
        int lastRow = sheet.getLastRowNum();

        for (String cmdSetName : cmdSetCandidates) {
            int targetCol = -1;
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String header = formatter.formatCellValue(headerRow.getCell(c)).trim();
                if (cmdSetName.equalsIgnoreCase(header)) {
                    targetCol = c;
                    break;
                }
            }
            if (targetCol == -1) continue;

            String firstCommand = null;
            String lastCommand = null;

            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String cmd = formatter.formatCellValue(row.getCell(targetCol)).trim();
                if (!cmd.isEmpty()) {
                    if (firstCommand == null) {
                        firstCommand = cmd;
                    }
                    lastCommand = cmd;
                }
            }

            if (firstCommand != null && !firstCommand.isEmpty()) {
                firsts.add(firstCommand);
            }
            if (lastCommand != null && !lastCommand.isEmpty()) {
                lasts.add(lastCommand);
            }
        }

        return new CommandBoundary(new ArrayList<>(firsts), new ArrayList<>(lasts));
    }

    private static class CommandBoundary {
        final List<String> firstCommands;
        final List<String> lastCommands;

        CommandBoundary(List<String> firstCommands, List<String> lastCommands) {
            this.firstCommands = (firstCommands != null)
                    ? Collections.unmodifiableList(firstCommands)
                    : Collections.emptyList();
            this.lastCommands = (lastCommands != null)
                    ? Collections.unmodifiableList(lastCommands)
                    : Collections.emptyList();
        }
    }

    private static boolean containsPromptPlusFirstAndLastCommand(File logFile,
                                                                List<String> firstCommands,
                                                                List<String> lastCommands) {
        if (firstCommands == null || firstCommands.isEmpty() || lastCommands == null || lastCommands.isEmpty()) {
            return false;
        }

        return containsPromptPlusFirstCommand(logFile, firstCommands)
                && containsPromptPlusLastCommand(logFile, lastCommands);
    }

    private static boolean isConnectFailureLog(File logFile) {
        if (logFile == null || !logFile.isFile()) {
            return false;
        }

        try {
            String head = readUtf8Head(logFile, FIRST_COMMAND_HEAD_BYTES_TO_SCAN);
            String tail = readUtf8Tail(logFile, LAST_COMMAND_TAIL_BYTES_TO_SCAN);
            String combined = ((head == null) ? "" : head) + "\n" + ((tail == null) ? "" : tail);
            return containsConnectFailureText(combined);
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read log for connect-failure check: "
                    + logFile.getName() + " -> " + e.getMessage());
            return false;
        }
    }

    private static boolean containsConnectFailureText(String text) {
        if (text == null) return false;

        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("ssh_multi connect failed")
                || lower.contains("tcp port 22 unreachable")
                || lower.contains("connect failed")
                || lower.contains("connection refused")
                || lower.contains("no route to host")
                || lower.contains("connection timed out")
                || lower.contains("jschexception")
                || lower.contains("authentication failed")
                || lower.contains("auth fail");
    }

    private static boolean isAuthFailureLog(File logFile) {
        if (logFile == null || !logFile.isFile()) {
            return false;
        }

        try {
            String head = readUtf8Head(logFile, FIRST_COMMAND_HEAD_BYTES_TO_SCAN);
            String tail = readUtf8Tail(logFile, LAST_COMMAND_TAIL_BYTES_TO_SCAN);
            String lower = (((head == null) ? "" : head) + "\n" + ((tail == null) ? "" : tail))
                    .toLowerCase(Locale.ROOT);
            return lower.contains("auth fail")
                    || lower.contains("authentication failed")
                    || lower.contains("userauth fail")
                    || lower.contains("auth cancel")
                    || lower.contains("username or password is empty");
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read log for auth-failure check: "
                    + logFile.getName() + " -> " + e.getMessage());
            return false;
        }
    }

    private static String detectPromptVendorFromLog(File logFile) {
        if (logFile == null || !logFile.isFile()) return null;

        try {
            String headContent = readUtf8Head(logFile, FIRST_COMMAND_HEAD_BYTES_TO_SCAN);
            if (headContent.isEmpty()) return null;

            String[] rawLines = headContent.split("\r?\n");
            int endIndex = Math.min(rawLines.length, FIRST_COMMAND_HEAD_LINES_TO_SCAN);
            String lastVendor = null;

            for (int i = 0; i < endIndex; i++) {
                String vendor = detectPromptVendorFromLine(rawLines[i]);
                if (vendor != null) {
                    lastVendor = vendor;
                }
            }

            return lastVendor;
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read log for prompt-vendor check: "
                    + logFile.getName() + " -> " + e.getMessage());
            return null;
        }
    }

    private static String detectPromptVendorFromLine(String line) {
        String sanitized = sanitizeLogLine(line);
        if (sanitized.isEmpty()) return null;

        String upper = sanitized.toUpperCase(Locale.ROOT);
        if (upper.startsWith("[INFO]")
                || upper.startsWith("[WARN]")
                || upper.startsWith("[ERROR]")
                || upper.startsWith("[DELETE]")
                || upper.startsWith("[RETRY]")
                || upper.startsWith("[OK]")
                || upper.startsWith("[START]")
                || upper.startsWith("[DONE]")
                || upper.startsWith("[AUTO-VENDOR]")
                || upper.startsWith("[SEND CMD]")) {
            return null;
        }

        if (sanitized.matches("^[A-Za-z0-9._:-]+#(?:\\s*.*)?$")) {
            return "ZTE";
        }

        if (sanitized.matches("^[A-Za-z0-9._:-]+>(?:\\s*.*)?$")
                || sanitized.matches("^<[A-Za-z0-9._:-]+>(?:\\s*.*)?$")
                || sanitized.matches("^\\[(?:~|\\*)?[A-Za-z0-9._:-]+(?:-[^\\]]+)?\\](?:\\s*.*)?$")) {
            return "HW";
        }

        return null;
    }

    private static boolean doesLogFileNameMatchPromptVendor(File logFile, String promptVendor) {
        if (logFile == null || promptVendor == null || promptVendor.trim().isEmpty()) {
            return true;
        }

        String upperFileName = logFile.getName().toUpperCase(Locale.ROOT);
        return upperFileName.contains("_" + promptVendor.toUpperCase(Locale.ROOT) + "-");
    }

    private static boolean containsPromptPlusFirstCommand(File logFile, List<String> firstCommands) {
        if (logFile == null || firstCommands == null || firstCommands.isEmpty()) return false;

        try {
            String headContent = readUtf8Head(logFile, FIRST_COMMAND_HEAD_BYTES_TO_SCAN);
            if (headContent.isEmpty()) return false;

            String[] rawLines = headContent.split("\r?\n");
            int startIndex = 0;
            int endIndex = Math.min(rawLines.length, FIRST_COMMAND_HEAD_LINES_TO_SCAN);

            for (int i = startIndex; i < endIndex; i++) {
                String line = sanitizeLogLine(rawLines[i]);
                if (line.isEmpty()) continue;

                for (String cmd : firstCommands) {
                    if (cmd == null || cmd.trim().isEmpty()) continue;

                    String trimmedCmd = cmd.trim();
                    if (line.equalsIgnoreCase(trimmedCmd)
                            || matchesPromptPlusCommandLine(line, trimmedCmd)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read log for first-command check: "
                    + logFile.getName() + " -> " + e.getMessage());
        }

        return false;
    }

    private static boolean containsPromptPlusLastCommand(File logFile, List<String> lastCommands) {
        try {
            String tailContent = readUtf8Tail(logFile, LAST_COMMAND_TAIL_BYTES_TO_SCAN);
            if (tailContent.isEmpty()) return false;

            String[] rawLines = tailContent.split("\r?\n");
            int startIndex = Math.max(0, rawLines.length - LAST_COMMAND_TAIL_LINES_TO_SCAN);
            List<String> tailLines = new ArrayList<>();

            for (int i = startIndex; i < rawLines.length; i++) {
                tailLines.add(sanitizeLogLine(rawLines[i]));
            }

            for (int i = tailLines.size() - 1; i >= 0; i--) {
                String line = tailLines.get(i);
                if (line.isEmpty()) continue;

                for (String cmd : lastCommands) {
                    if (matchesPromptPlusCommandLine(line, cmd)
                            || matchesCommandCompletionFallback(tailLines, i, cmd)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read log for completion check: " + logFile.getName() + " -> " + e.getMessage());
        }
        return false;
    }

    private static String readUtf8Tail(File logFile, int tailBytesToScan) throws IOException {
        if (logFile == null || !logFile.isFile()) return "";

        long fileLength = logFile.length();
        if (fileLength <= 0) return "";

        int bytesToRead = (int) Math.min(fileLength, Math.max(1, tailBytesToScan));
        byte[] buffer = new byte[bytesToRead];

        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long startPos = Math.max(0L, fileLength - bytesToRead);
            raf.seek(startPos);
            raf.readFully(buffer);

            String tail = new String(buffer, StandardCharsets.UTF_8);
            if (startPos > 0) {
                int firstNewLine = tail.indexOf('\n');
                if (firstNewLine >= 0 && firstNewLine + 1 < tail.length()) {
                    tail = tail.substring(firstNewLine + 1);
                }
            }
            return tail;
        }
    }

    private static String readUtf8Head(File logFile, int headBytesToScan) throws IOException {
        if (logFile == null || !logFile.isFile()) return "";

        long fileLength = logFile.length();
        if (fileLength <= 0) return "";

        int bytesToRead = (int) Math.min(fileLength, Math.max(1, headBytesToScan));
        byte[] buffer = new byte[bytesToRead];

        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            raf.seek(0);
            raf.readFully(buffer);

            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    private static String sanitizeLogLine(String line) {
        if (line == null) return "";
        return line
                .replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "")
                .replace("\r", "")
                .trim();
    }

    private static boolean matchesPromptPlusCommandLine(String line, String lastCommand) {
        if (line == null || lastCommand == null) return false;

        String cmd = lastCommand.trim();
        if (cmd.isEmpty()) return false;

        String quotedCmd = Pattern.quote(cmd);

        return line.matches("^<[^\r\n>]+>\\s*" + quotedCmd + "\\s*$")
                || line.matches("^\\[(?:~|\\*)?[^\r\n\\]]+(?:-[^\\]]+)?\\]\\s*" + quotedCmd + "\\s*$")
                || line.matches("^[A-Za-z0-9._:-]+[>#]\\s*" + quotedCmd + "\\s*$");
    }

    private static void deleteLogFileQuietly(File logFile, String reason) {
        if (logFile == null || !logFile.exists()) return;

        try {
            Files.deleteIfExists(logFile.toPath());
            System.out.printf("[DELETE] %s (%s)%n", logFile.getName(), reason);
        } catch (IOException e) {
            System.out.printf("[WARN] Cannot delete log file %s : %s%n", logFile.getName(), e.getMessage());
        }
    }

    private static List<File> findMatchingLogFiles(int excelRow, List<String> cmdSetCandidates) {
        List<File> matches = new ArrayList<>();
        if (cmdSetCandidates == null || cmdSetCandidates.isEmpty()) return matches;

        File dir = new File(fileInput.getLog());
        File[] files = dir.listFiles();
        if (files == null) return matches;

        String prefix = "[" + excelRow + "]";
        for (File file : files) {
            if (file == null || !file.isFile()) continue;

            String name = file.getName();
            if (!name.startsWith(prefix)) continue;
            if (!matchesAnyCmdSetName(name, cmdSetCandidates)) continue;

            matches.add(file);
        }

        matches.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return matches;
    }

    private static File findLatestMatchingLogFile(int excelRow, List<String> cmdSetCandidates) {
        List<File> matches = findMatchingLogFiles(excelRow, cmdSetCandidates);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static void deleteMatchingLogFiles(int excelRow, List<String> cmdSetCandidates, String reason) {
        for (File file : findMatchingLogFiles(excelRow, cmdSetCandidates)) {
            deleteLogFileQuietly(file, reason);
        }
    }

    private static TaskRunResult runTaskWithCompletionCheck(DeviceTask task, String sshUser, String sshPass) {
        int maxAttempts = (task.firstCommands.isEmpty() || task.lastCommands.isEmpty())
                ? 1
                : (1 + MAX_RERUN_IF_LOG_INCOMPLETE);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (stopRequested) return TaskRunResult.stopped(task, attempt - 1);

            new SSH_Multi("", sshUser, sshPass,
                    task.ip, "", "",
                    task.cmdSet, task.deviceName, task.rowNum,
                    "", "");

            File latestLog = findLatestMatchingLogFile(task.rowNum, task.cmdSetCandidates);
            if (latestLog != null && isConnectFailureLog(latestLog)) {
                FailureType failureType = isAuthFailureLog(latestLog)
                        ? FailureType.AUTH_FAILED
                        : FailureType.NETWORK_FAILED;
                deleteMatchingLogFiles(task.rowNum, task.cmdSetCandidates, "connect failure");
                System.out.printf("[DELETE-SKIP] [%d]%s (%s) cmdSet=%s because CONNECT_FAIL detected%n",
                        task.rowNum, task.deviceName, task.ip, task.cmdSet);
                return TaskRunResult.failure(task, failureType,
                        "CONNECT_FAIL detected", attempt, latestLog);
            }

            if (task.firstCommands.isEmpty() || task.lastCommands.isEmpty()) {
                System.out.printf("[WARN] Cannot validate command boundary for [%d]%s (%s) cmdSet=%s%n",
                        task.rowNum, task.deviceName, task.ip, task.cmdSet);
                return TaskRunResult.failure(task, FailureType.VALIDATION_MISSING,
                        "cmdSet first/last command is missing", attempt, latestLog);
            }

            String promptVendor = detectPromptVendorFromLog(latestLog);
            if (latestLog != null && promptVendor != null
                    && !doesLogFileNameMatchPromptVendor(latestLog, promptVendor)) {
                deleteMatchingLogFiles(task.rowNum, task.cmdSetCandidates,
                        "prompt vendor mismatch (" + promptVendor + ")");

                if (attempt < maxAttempts) {
                    System.out.printf("[RETRY] [%d]%s (%s) cmdSet=%s because prompt vendor=%s did not match log file name (attempt %d/%d)%n",
                            task.rowNum, task.deviceName, task.ip, task.cmdSet, promptVendor, attempt + 1, maxAttempts);
                    continue;
                }

                System.out.printf("[WARN] [%d]%s (%s) cmdSet=%s prompt vendor=%s did not match log file name after %d attempt(s)%n",
                        task.rowNum, task.deviceName, task.ip, task.cmdSet, promptVendor, maxAttempts);
                return TaskRunResult.failure(task, FailureType.VENDOR_MISMATCH,
                        "Prompt vendor " + promptVendor + " did not match log file name",
                        attempt, latestLog);
            }

            if (latestLog != null
                    && containsPromptPlusFirstAndLastCommand(latestLog, task.firstCommands, task.lastCommands)) {
                if (attempt > 1) {
                    System.out.printf("[OK] Retry success for [%d]%s (%s) cmdSet=%s on attempt %d/%d%n",
                            task.rowNum, task.deviceName, task.ip, task.cmdSet, attempt, maxAttempts);
                }
                return TaskRunResult.success(task, attempt, latestLog);
            }

            if (attempt < maxAttempts) {
                if (latestLog == null) {
                    System.out.printf("[RETRY] [%d]%s (%s) cmdSet=%s because log not found (attempt %d/%d)%n",
                            task.rowNum, task.deviceName, task.ip, task.cmdSet, attempt + 1, maxAttempts);
                } else {
                    deleteMatchingLogFiles(task.rowNum, task.cmdSetCandidates, "missing command boundary");
                    System.out.printf("[RETRY] [%d]%s (%s) cmdSet=%s because command boundary was not complete (attempt %d/%d)%n",
                            task.rowNum, task.deviceName, task.ip, task.cmdSet, attempt + 1, maxAttempts);
                }
            } else {
                System.out.printf("[WARN] [%d]%s (%s) cmdSet=%s finished but not complete after %d attempt(s)%n",
                        task.rowNum, task.deviceName, task.ip, task.cmdSet, maxAttempts);
                if (latestLog != null) {
                    deleteMatchingLogFiles(task.rowNum, task.cmdSetCandidates, "final incomplete command boundary");
                }
                FailureType failureType = latestLog == null
                        ? FailureType.LOG_MISSING
                        : FailureType.COMMAND_INCOMPLETE;
                String message = latestLog == null
                        ? "Log file was not created"
                        : "Command boundary was not complete";
                return TaskRunResult.failure(task, failureType, message, attempt, latestLog);
            }
        }
        return TaskRunResult.failure(task, FailureType.UNKNOWN,
                "Task finished without a known result", maxAttempts, null);
    }

    private static void resetRunState() {
        completedTasks.set(0);
        successTaskCount.set(0);
        failedTaskCount.set(0);
        authFailedTaskCount.set(0);
        networkFailedTaskCount.set(0);
        commandIncompleteTaskCount.set(0);
        vendorMismatchTaskCount.set(0);
        logMissingTaskCount.set(0);
        validationMissingTaskCount.set(0);
        stoppedTaskCount.set(0);
        unknownFailedTaskCount.set(0);
        stopRequested = false;
        alarmEnabled = true;
        turboMode = false;
        currentThreadPoolSize = DEFAULT_THREAD_POOL_SIZE;
        normalThreadPoolSize = DEFAULT_THREAD_POOL_SIZE;
    }

    private static void registerTaskResult(TaskRunResult result) {
        if (result == null) {
            failedTaskCount.incrementAndGet();
            unknownFailedTaskCount.incrementAndGet();
            return;
        }

        if (result.success) {
            successTaskCount.incrementAndGet();
            return;
        }

        if (result.failureType == FailureType.STOPPED) {
            stoppedTaskCount.incrementAndGet();
            return;
        }

        failedTaskCount.incrementAndGet();
        switch (result.failureType) {
            case AUTH_FAILED:
                authFailedTaskCount.incrementAndGet();
                break;
            case NETWORK_FAILED:
                networkFailedTaskCount.incrementAndGet();
                break;
            case COMMAND_INCOMPLETE:
                commandIncompleteTaskCount.incrementAndGet();
                break;
            case VENDOR_MISMATCH:
                vendorMismatchTaskCount.incrementAndGet();
                break;
            case LOG_MISSING:
                logMissingTaskCount.incrementAndGet();
                break;
            case VALIDATION_MISSING:
                validationMissingTaskCount.incrementAndGet();
                break;
            default:
                unknownFailedTaskCount.incrementAndGet();
                break;
        }
    }


    public static void main(String[] args) {
        Dialog.setLAF();
        AppConsole.install();
        resetRunState();
        startMouseJiggler();

        System.out.println("=== BotGetLog [DTAC] (SSH) ===");
        System.out.println("Excel: " + fileInput.getUserInterface_Input());

        new File(fileInput.getLog()).mkdirs();
        new File(fileInput.getLogWork()).mkdirs();

        showProgressWindowBeforePrompt();
        Set<String> existingLogs = loadExistingLogNames();

        String sshUser;
        String sshPass;
        int configuredThreadPoolSize = DEFAULT_THREAD_POOL_SIZE;

        List<DeviceTask> jobList = new ArrayList<>();

        try (Workbook workbook = openInputWorkbookWithRetry()) {
            Map<String, String> settings = readSettings(workbook);
            sshUser = firstSetting(settings, "user", "username", "sshUsername").trim();
            sshPass = firstSetting(settings, "pass", "password", "sshPassword").trim();
            String deviceSelectMode = firstSetting(settings, "deviceSelectMode").trim();
            int deviceRowStart = firstSettingInt(settings, 0, "deviceRowStart");
            int deviceRowEnd = firstSettingInt(settings, 0, "deviceRowEnd");
            configuredThreadPoolSize = firstSettingInt(settings, DEFAULT_THREAD_POOL_SIZE,
                    "threadPoolSize", "thread", "threads", "maxThreads");

            jobList = buildDeviceTasks(workbook, existingLogs, deviceSelectMode, deviceRowStart, deviceRowEnd);
            System.out.printf("[INFO] Loaded %d DTAC task(s), mode=%s, rows=%d-%d%n",
                    jobList.size(),
                    deviceSelectMode == null || deviceSelectMode.trim().isEmpty() ? "Y column" : deviceSelectMode,
                    deviceRowStart,
                    deviceRowEnd);
        } catch (Exception e) {
            dialog.showError("Cannot read Excel file: " + e.getMessage());
            closeStartupWindows();
            return;
        }

        if (jobList.isEmpty()) {
            dialog.showInfo("No DTAC jobs found to run.");
            closeStartupWindows();
            return;
        }

        totalTasks = jobList.size();
        CredentialInput validatedCredentials = promptForValidatedCredentials(sshUser, sshPass, jobList);
        if (validatedCredentials == null) {
            System.out.println("[INFO] DTAC startup cancelled before SSH jobs.");
            closeStartupWindows();
            return;
        }
        sshUser = validatedCredentials.username;
        sshPass = validatedCredentials.password;

        int poolSize = clampThreadPoolSize(configuredThreadPoolSize, totalTasks);
        normalThreadPoolSize = poolSize;
        currentThreadPoolSize = poolSize;
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize);
        System.out.printf("[INFO] Thread pool size: %d (configured=%d, max=%d)%n",
                poolSize, configuredThreadPoolSize, MAX_THREAD_POOL_SIZE);

        List<Future<?>> futures = new ArrayList<>();
        final List<TaskRunResult> failedResults = Collections.synchronizedList(new ArrayList<>());
        final String finalSshUser = sshUser;
        final String finalSshPass = sshPass;

        for (DeviceTask task : jobList) {
            if (stopRequested) break;
            Future<?> f = executor.submit(() -> {
                TaskRunResult result = TaskRunResult.stopped(task, 0);
                try {
                    if (!stopRequested) {
                        result = runTaskWithCompletionCheck(task, finalSshUser, finalSshPass);
                    }
                } finally {
                    registerTaskResult(result);
                    if (result != null && !result.success && result.failureType != FailureType.STOPPED) {
                        failedResults.add(result);
                    }
                    int done = completedTasks.incrementAndGet();
                    System.out.printf("Finished %d / %d : [%d]%s (%s) cmdSet=%s result=%s attempts=%d reason=%s%n",
                            done, totalTasks, task.rowNum, task.deviceName, task.ip, task.cmdSet,
                            result == null ? "UNKNOWN" : result.statusLabel(),
                            result == null ? 0 : result.attempts,
                            result == null ? "-" : result.message);
                }
            });
            futures.add(f);
        }

        for (Future<?> f : futures) {
            try {
                if (stopRequested) break;
                f.get();
            } catch (Exception ignored) {}
        }

        boolean stoppedManually = stopRequested;
        stopRequested = true;
        shutdownExecutor();
        writeSummary(totalTasks, stoppedManually, failedResults);
        System.out.println("=== END ===");
    }

    private static Workbook openInputWorkbookWithRetry() throws Exception {
        Path excelPath = Paths.get(fileInput.getUserInterface_Input());
        if (!Files.exists(excelPath)) {
            throw new FileNotFoundException("Excel file not found: " + excelPath.toAbsolutePath());
        }

        Exception last = null;
        for (int attempt = 1; attempt <= EXCEL_OPEN_MAX_RETRY; attempt++) {
            Path tempCopy = Paths.get(System.getProperty("java.io.tmpdir"),
                    "BotGetLog_DTAC_Input_" + System.currentTimeMillis() + "_" + attempt + ".xlsx");
            try {
                Files.copy(excelPath, tempCopy, StandardCopyOption.REPLACE_EXISTING);
                try (InputStream in = new BufferedInputStream(Files.newInputStream(tempCopy))) {
                    Workbook workbook = WorkbookFactory.create(in);
                    System.out.printf("[OK] Excel opened from safe temp copy: %s%n", excelPath.getFileName());
                    return workbook;
                }
            } catch (Exception ex) {
                last = ex;
                System.out.printf("[WARN] Excel open failed, retry %d/%d: %s%n",
                        attempt, EXCEL_OPEN_MAX_RETRY, ex.getMessage());
                if (attempt < EXCEL_OPEN_MAX_RETRY) {
                    try {
                        Thread.sleep(EXCEL_OPEN_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            } finally {
                try {
                    Files.deleteIfExists(tempCopy);
                } catch (IOException ignore) { }
            }
        }

        if (last != null) throw last;
        throw new IOException("Cannot open Excel workbook");
    }

    private static int clampThreadPoolSize(int configuredThreadPoolSize, int taskCount) {
        if (taskCount <= 0) return 1;
        int configured = configuredThreadPoolSize <= 0 ? DEFAULT_THREAD_POOL_SIZE : configuredThreadPoolSize;
        int capped = Math.min(configured, MAX_THREAD_POOL_SIZE);
        return Math.max(1, Math.min(capped, taskCount));
    }

    private static CredentialInput promptForValidatedCredentials(String initialUser,
                                                                 String initialPass,
                                                                 List<DeviceTask> jobList) {
        String currentUser = initialUser == null ? "" : initialUser.trim();
        String currentPass = initialPass == null ? "" : initialPass.trim();

        while (!stopRequested) {
            while (currentUser.isEmpty() || currentPass.isEmpty()) {
                CredentialInput entered = showCredentialDialog(currentUser, currentPass);
                if (entered == null) return null;
                currentUser = entered.username;
                currentPass = entered.password;
                if (currentUser.isEmpty() || currentPass.isEmpty()) {
                    JOptionPane.showMessageDialog(
                            getDialogParent(),
                            "Please enter both Username and Password.",
                            "Missing Credentials",
                            JOptionPane.ERROR_MESSAGE);
                }
            }

            List<ValidationProbe> probes = buildValidationProbes(jobList);
            CredentialValidationReport report = validateCredentialOnProbes(probes, currentUser, currentPass);

            if (report.hasSuccess()) {
                System.out.printf("[OK] DTAC SSH credential validated on %d/%d node(s).%n",
                        report.success, report.total);
                return new CredentialInput(currentUser, currentPass);
            }

            if (report.hasAuthFailure()) {
                JOptionPane.showMessageDialog(
                        getDialogParent(),
                        "<html>DTAC SSH username/password looks incorrect.<br><br>"
                                + report.shortDetail()
                                + "<br><br>Please try again.</html>",
                        "DTAC Login Failed",
                        JOptionPane.ERROR_MESSAGE
                );
                currentPass = "";
                continue;
            }

            Object[] options = {"Continue Anyway", "Retry Login", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    getDialogParent(),
                    "<html>Cannot confirm DTAC SSH credential on validation nodes.<br>"
                            + "Checked: " + report.total + " node(s)<br><br>"
                            + report.shortDetail()
                            + "<br><br>If these nodes are unreachable but the credential is correct, continue anyway.</html>",
                    "DTAC Credential Check",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    options,
                    options[0]
            );

            if (choice == 0) {
                System.out.println("[WARN] Continue without successful DTAC credential validation.");
                return new CredentialInput(currentUser, currentPass);
            }
            if (choice == 1) {
                currentPass = "";
                continue;
            }
            return null;
        }

        return null;
    }

    private static void showProgressWindowBeforePrompt() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        Runnable task = () -> {
            StopProgram current = StopProgram.getInstance();
            if (current == null) {
                current = new StopProgram();
            } else {
                current.setVisible(true);
            }
            current.toFront();
            current.requestFocus();
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
        } catch (Exception e) {
            System.out.println("[WARN] Cannot open DTAC progress window before login: " + e.getMessage());
        }
    }

    private static void closeProgressWindow() {
        StopProgram.closeWindow();
    }

    private static void closeStartupWindows() {
        stopRequested = true;
        alarmEnabled = false;
        shutdownExecutor();
        closeProgressWindow();
        AppConsole.closeNow();
    }

    private static java.awt.Component getDialogParent() {
        StopProgram current = StopProgram.getInstance();
        if (current != null && current.isDisplayable()) {
            return current;
        }
        return null;
    }

    private static List<ValidationProbe> buildValidationProbes(List<DeviceTask> jobList) {
        LinkedHashMap<String, ValidationProbe> probes = new LinkedHashMap<>();

        for (String ip : DEFAULT_VALIDATION_IPS) {
            if (ip == null || ip.trim().isEmpty()) continue;
            String trimmedIp = ip.trim();
            probes.put(trimmedIp, new ValidationProbe("fixed", trimmedIp));
            if (probes.size() >= MAX_VALIDATION_NODES) {
                return new ArrayList<>(probes.values());
            }
        }

        if (jobList != null) {
            for (DeviceTask task : jobList) {
                if (task == null || task.ip == null || task.ip.trim().isEmpty()) continue;
                String ip = task.ip.trim();
                if (!probes.containsKey(ip)) {
                    probes.put(ip, new ValidationProbe("row " + task.rowNum + " " + task.deviceName, ip));
                }
                if (probes.size() >= MAX_VALIDATION_NODES) break;
            }
        }

        return new ArrayList<>(probes.values());
    }

    private static CredentialValidationReport validateCredentialOnProbes(
            List<ValidationProbe> probes, String user, String pass) {
        if (probes == null || probes.isEmpty()) {
            return new CredentialValidationReport(0, 1, 0,
                    Collections.singletonList("No validation node configured; skipped validation"));
        }

        int success = 0;
        int authFailure = 0;
        int checked = 0;
        List<String> messages = new ArrayList<>();
        System.out.printf("[INFO] Validating DTAC SSH credential on up to %d node(s)%n", probes.size());

        for (ValidationProbe probe : probes) {
            if (stopRequested) break;
            checked++;
            System.out.printf("[VALIDATE] DTAC %s (%s)%n", probe.ip, probe.label);
            SSH_Multi.CredentialValidationResult result =
                    SSH_Multi.validateCredentials(probe.ip, user, pass);

            String status;
            if (result.success) {
                success++;
                status = "OK";
            } else if (result.authFailure) {
                authFailure++;
                status = "AUTH_FAILED";
            } else {
                status = "UNREACHABLE";
            }

            String message = String.format("%s : %s - %s", probe.ip, status, result.message);
            messages.add(message);
            System.out.println("[VALIDATE] " + message);
            if (result.success || result.authFailure) {
                break;
            }
        }

        return new CredentialValidationReport(checked, success, authFailure, messages);
    }

    private static void writeSummary(int total, boolean stoppedManually, List<TaskRunResult> failedResults) {
        int success = successTaskCount.get();
        int failed = failedTaskCount.get();
        int stopped = Math.max(stoppedTaskCount.get(), Math.max(0, total - success - failed));
        double successRate = total <= 0 ? 0.0 : (success * 100.0 / total);
        String timestamp = LocalDateTime.now().format(SUMMARY_TIMESTAMP_FORMAT);
        String summary = String.format(Locale.ROOT,
                "[SUMMARY] DTAC total=%d success=%d failed=%d stopped=%d authFailed=%d networkFailed=%d commandIncomplete=%d vendorMismatch=%d logMissing=%d validationMissing=%d unknown=%d successRate=%.2f%% manualStop=%s%n",
                total, success, failed, stopped,
                authFailedTaskCount.get(),
                networkFailedTaskCount.get(),
                commandIncompleteTaskCount.get(),
                vendorMismatchTaskCount.get(),
                logMissingTaskCount.get(),
                validationMissingTaskCount.get(),
                unknownFailedTaskCount.get(),
                successRate, stoppedManually);

        System.out.print(summary);
        try {
            Files.createDirectories(Paths.get(fileInput.getLogWork()));
            Path summaryPath = Paths.get(fileInput.getLogWork(),
                    "Summary_DTAC_" + LocalDate.now().format(SUMMARY_DATE_FORMAT) + ".txt");
            Files.write(summaryPath, summary.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            Path summaryCsv = Paths.get(fileInput.getLogWork(),
                    "Summary_DTAC_" + LocalDate.now().format(SUMMARY_DATE_FORMAT) + ".csv");
            boolean newSummaryCsv = !Files.exists(summaryCsv);
            String summaryHeader = "timestamp,total,success,failed,stopped,authFailed,networkFailed,commandIncomplete,vendorMismatch,logMissing,validationMissing,unknown,successRate,manualStop\n";
            String summaryRow = String.format(Locale.ROOT,
                    "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%s%n",
                    csv(timestamp), total, success, failed, stopped,
                    authFailedTaskCount.get(),
                    networkFailedTaskCount.get(),
                    commandIncompleteTaskCount.get(),
                    vendorMismatchTaskCount.get(),
                    logMissingTaskCount.get(),
                    validationMissingTaskCount.get(),
                    unknownFailedTaskCount.get(),
                    successRate, stoppedManually);
            Files.write(summaryCsv,
                    ((newSummaryCsv ? summaryHeader : "") + summaryRow).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);

            if (failedResults != null && !failedResults.isEmpty()) {
                Path failedCsv = Paths.get(fileInput.getLogWork(),
                        "Failed_DTAC_" + LocalDate.now().format(SUMMARY_DATE_FORMAT) + ".csv");
                boolean newFailedCsv = !Files.exists(failedCsv);
                StringBuilder failedRows = new StringBuilder();
                if (newFailedCsv) {
                    failedRows.append("timestamp,row,device,ip,cmdSet,failureType,attempts,latestLog,message\n");
                }
                synchronized (failedResults) {
                    for (TaskRunResult result : failedResults) {
                        if (result == null || result.task == null) continue;
                        failedRows.append(csv(timestamp)).append(',')
                                .append(result.task.rowNum).append(',')
                                .append(csv(result.task.deviceName)).append(',')
                                .append(csv(result.task.ip)).append(',')
                                .append(csv(result.task.cmdSet)).append(',')
                                .append(csv(result.failureType.name())).append(',')
                                .append(result.attempts).append(',')
                                .append(csv(result.latestLogName)).append(',')
                                .append(csv(result.message)).append('\n');
                    }
                }
                Files.write(failedCsv, failedRows.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException ex) {
            System.out.println("[WARN] Cannot write DTAC summary: " + ex.getMessage());
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static CredentialInput showCredentialDialog(String initialUsername, String initialPassword) {
        JTextField usernameField = new JTextField(initialUsername == null ? "" : initialUsername, 22);
        JPasswordField passwordField = new JPasswordField(initialPassword == null ? "" : initialPassword, 22);

        usernameField.addActionListener(event -> {
            passwordField.requestFocusInWindow();
            passwordField.selectAll();
        });

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        fields.add(new JLabel("Username"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        fields.add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        fields.add(new JLabel("Password"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        fields.add(passwordField, gbc);

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.add(new JLabel("<html>Please enter your DTAC SSH login credentials.<br>"
                + "(Make sure your keyboard is set to English before typing.)</html>"),
                BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> {
            if (usernameField.getText().trim().isEmpty()) {
                usernameField.requestFocusInWindow();
                usernameField.selectAll();
            } else {
                passwordField.requestFocusInWindow();
                passwordField.selectAll();
            }
        });

        int choice = JOptionPane.showConfirmDialog(
                getDialogParent(),
                panel,
                "BotGetLog [DTAC] Login",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }

        return new CredentialInput(usernameField.getText(), new String(passwordField.getPassword()));
    }

    private static Map<String, String> readSettings(Workbook workbook) {
        Map<String, String> map = new HashMap<>();
        Sheet sheet = workbook.getSheet(SETTING_SHEET);
        if (sheet == null) return map;

        for (Row row : sheet) {
            Cell varCell = row.getCell(0);
            Cell valCell = row.getCell(1);
            if (varCell == null) continue;
            String var = getCellString(varCell);
            if (var == null) continue;

            var = normalizeSettingKey(var);
            String val = (valCell != null) ? getCellString(valCell) : "";
            if (val == null) val = "";
            map.put(var, val);
        }
        return map;
    }

    private static String normalizeSettingKey(String key) {
        if (key == null) return "";
        return key.replace(":", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String firstSetting(Map<String, String> settings, String... aliases) {
        if (settings == null || aliases == null) return "";
        for (String alias : aliases) {
            String value = settings.get(normalizeSettingKey(alias));
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static int firstSettingInt(Map<String, String> settings, int defaultValue, String... aliases) {
        String value = firstSetting(settings, aliases);
        if (value.matches("\\d+\\.0+")) {
            value = value.substring(0, value.indexOf('.'));
        }
        if (!value.matches("\\d+")) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean shouldRunDeviceRow(Row row, String deviceSelectMode, int deviceRowStart, int deviceRowEnd) {
        if (row == null) return false;

        String mode = deviceSelectMode == null ? "" : deviceSelectMode.trim();
        if ("All".equalsIgnoreCase(mode)) {
            return true;
        }

        String enabled = getCellString(row.getCell(0));
        if ("Y".equalsIgnoreCase(enabled)) {
            return true;
        }

        int excelRow = row.getRowNum() + 1;
        if ("Manual".equalsIgnoreCase(mode)
                && deviceRowStart > 0
                && deviceRowEnd >= deviceRowStart) {
            return excelRow >= deviceRowStart && excelRow <= deviceRowEnd;
        }

        return "Y".equalsIgnoreCase(enabled);
    }

    private static List<DeviceTask> buildDeviceTasks(Workbook workbook, Set<String> existingLogs,
            String deviceSelectMode, int deviceRowStart, int deviceRowEnd) {
        List<DeviceTask> list = new ArrayList<>();
        Sheet sheet = workbook.getSheet(DEVICE_SHEET);
        if (sheet == null) return list;

        int lastRow = sheet.getLastRowNum();
        Row header = sheet.getRow(0);
        if (header == null) return list;

        int lastCol = header.getLastCellNum();

        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            if (!shouldRunDeviceRow(row, deviceSelectMode, deviceRowStart, deviceRowEnd)) continue;

            String group = getCellString(row.getCell(1));
            String deviceName = getCellString(row.getCell(2));
            String ip = getCellString(row.getCell(3));
            if (deviceName == null || deviceName.trim().isEmpty()
                    || ip == null || ip.trim().isEmpty()) continue;

            for (int c = 4; c < lastCol; c++) {
                String cmdSet = getCellString(row.getCell(c));
                if (cmdSet == null || cmdSet.trim().isEmpty()) continue;

                TaskValidationInfo validationInfo = buildTaskValidationInfo(cmdSet.trim(), workbook);

                if (isTaskAlreadyDone(r + 1, validationInfo, existingLogs)) {
                    System.out.printf("[SKIP] Row %d (%s, %s) cmdSet=%s%n",
                            (r + 1), deviceName, ip, cmdSet.trim());
                    continue;
                }

                DeviceTask task = new DeviceTask(
                        r + 1,
                        group,
                        deviceName,
                        ip,
                        cmdSet.trim(),
                        validationInfo.cmdSetCandidates,
                        validationInfo.firstCommands,
                        validationInfo.lastCommands
                );
                list.add(task);
            }
        }
        return list;
    }

    private static String getCellString(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell))
                    return cell.getDateCellValue().toString();
                double d = cell.getNumericCellValue();
                long l = (long) d;
                return (d == (double) l) ? String.valueOf(l) : String.valueOf(d);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return null;
        }
    }

    static class TaskValidationInfo {
        final List<String> cmdSetCandidates;
        final List<String> firstCommands;
        final List<String> lastCommands;

        TaskValidationInfo(List<String> cmdSetCandidates, List<String> firstCommands, List<String> lastCommands) {
            this.cmdSetCandidates = (cmdSetCandidates != null)
                    ? Collections.unmodifiableList(new ArrayList<>(cmdSetCandidates))
                    : Collections.emptyList();
            this.firstCommands = (firstCommands != null)
                    ? Collections.unmodifiableList(new ArrayList<>(firstCommands))
                    : Collections.emptyList();
            this.lastCommands = (lastCommands != null)
                    ? Collections.unmodifiableList(new ArrayList<>(lastCommands))
                    : Collections.emptyList();
        }
    }

    static class DeviceTask {
        final int rowNum;
        final String group;
        final String deviceName;
        final String ip;
        final String cmdSet;
        final List<String> cmdSetCandidates;
        final List<String> firstCommands;
        final List<String> lastCommands;

        DeviceTask(int rowNum, String group, String deviceName, String ip, String cmdSet,
                   List<String> cmdSetCandidates, List<String> firstCommands, List<String> lastCommands) {
            this.rowNum = rowNum;
            this.group = group;
            this.deviceName = deviceName;
            this.ip = ip;
            this.cmdSet = cmdSet;
            this.cmdSetCandidates = (cmdSetCandidates != null)
                    ? Collections.unmodifiableList(new ArrayList<>(cmdSetCandidates))
                    : Collections.emptyList();
            this.firstCommands = (firstCommands != null)
                    ? Collections.unmodifiableList(new ArrayList<>(firstCommands))
                    : Collections.emptyList();
            this.lastCommands = (lastCommands != null)
                    ? Collections.unmodifiableList(new ArrayList<>(lastCommands))
                    : Collections.emptyList();
        }
    }

    public static int getTotalTasks() { return totalTasks; }
    public static int getCompletedTasks() { return completedTasks.get(); }
    public static int getSuccessTaskCount() { return successTaskCount.get(); }
    public static int getFailedTaskCount() { return failedTaskCount.get(); }
    public static int getAuthFailedTaskCount() { return authFailedTaskCount.get(); }
    public static int getNetworkFailedTaskCount() { return networkFailedTaskCount.get(); }
    public static int getCommandIncompleteTaskCount() { return commandIncompleteTaskCount.get(); }
    public static int getVendorMismatchTaskCount() { return vendorMismatchTaskCount.get(); }
    public static int getLogMissingTaskCount() { return logMissingTaskCount.get(); }
    public static int getValidationMissingTaskCount() { return validationMissingTaskCount.get(); }
    public static int getStoppedTaskCount() { return stoppedTaskCount.get(); }
    public static int getUnknownFailedTaskCount() { return unknownFailedTaskCount.get(); }
    public static void requestStop() {
        stopRequested = true;
        alarmEnabled = false;
        shutdownExecutor();
    }
    public static boolean isStopRequested() { return stopRequested; }
    public static boolean isAlarmEnabled() { return alarmEnabled; }
    public static void setAlarmEnabled(boolean enabled) { alarmEnabled = enabled; }
    public static ExecutorService getExecutor() { return executor; }
    public static int getCurrentThreadPoolSize() { return currentThreadPoolSize; }
    public static int getNormalThreadPoolSize() { return normalThreadPoolSize; }
    public static boolean isTurboModeActive() { return turboMode; }

    public static synchronized void setTurboMode(boolean enabled) {
        turboMode = enabled;
        ThreadPoolExecutor exec = executor;
        if (exec == null || exec.isShutdown()) {
            return;
        }

        int base = normalThreadPoolSize <= 0 ? DEFAULT_THREAD_POOL_SIZE : normalThreadPoolSize;
        int target = enabled
                ? Math.min(MAX_THREAD_POOL_SIZE, Math.max(base, base * TURBO_THREAD_MULTIPLIER))
                : base;
        if (totalTasks > 0) {
            target = Math.min(target, totalTasks);
        }
        target = Math.max(1, target);

        try {
            int currentMax = exec.getMaximumPoolSize();
            if (target > currentMax) {
                exec.setMaximumPoolSize(target);
                exec.setCorePoolSize(target);
            } else {
                exec.setCorePoolSize(target);
                exec.setMaximumPoolSize(target);
            }
            exec.prestartAllCoreThreads();
            currentThreadPoolSize = target;
            System.out.printf("[INFO] DTAC %s mode: threadPool=%d%n",
                    enabled ? "Turbo" : "Normal", target);
        } catch (IllegalArgumentException ex) {
            System.out.println("[WARN] Cannot resize DTAC thread pool: " + ex.getMessage());
        }
    }

    public static synchronized void requestImmediateShutdown(String reason, int exitCode) {
        stopRequested = true;
        alarmEnabled = false;
        if (reason != null && !reason.trim().isEmpty()) {
            System.out.println("[INFO] Manual stop requested: " + reason);
        }
        shutdownExecutor();
        System.exit(exitCode);
    }

    private static void shutdownExecutor() {
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
    }
    private static boolean matchesCommandCompletionFallback(List<String> tailLines, int commandLineIndex, String lastCommand) {
    if (tailLines == null || lastCommand == null) return false;
    if (commandLineIndex < 0 || commandLineIndex >= tailLines.size()) return false;

    String cmd = lastCommand.trim();
    if (cmd.isEmpty()) return false;

    String currentLine = tailLines.get(commandLineIndex);
    if (currentLine == null || !currentLine.equalsIgnoreCase(cmd)) {
        return false;
    }

    String normalizedCmd = cmd.toLowerCase(Locale.ROOT);
    boolean isSessionClosingCommand = normalizedCmd.equals("quit")
            || normalizedCmd.equals("exit")
            || normalizedCmd.equals("logout");

    if (!isSessionClosingCommand) {
        return false;
    }

    int end = Math.min(tailLines.size(), commandLineIndex + 6);
    for (int i = commandLineIndex + 1; i < end; i++) {
        String next = tailLines.get(i);
        if (next == null || next.isEmpty()) continue;

        String lower = next.toLowerCase(Locale.ROOT);
        if (lower.contains("channel closed while waiting for cmd: " + normalizedCmd)
                || lower.contains("channel closed")
                || lower.contains("connection closed")
                || lower.contains("current vty users on line is 0")
                || lower.contains("logout")) {
            return true;
        }
    }

    return false;
}
}
