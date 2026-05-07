package com.java.botgetlog;

import com.java.shared.AppMetadata;
import com.java.updater.AutoUpdateManager;
import java.awt.Toolkit;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.sun.management.OperatingSystemMXBean;
import java.awt.BorderLayout;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.openxml4j.exceptions.*;
import java.awt.Color;
import java.awt.Insets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;



public class BotGetLog_TrueCorp {
    private static final Set<String> rerunOncePerRunKeys = ConcurrentHashMap.newKeySet();

    //  [Thread Configuration Section]
    boolean isFocusMode = false; //  (Work Mode)

    private static final int DEFAULT_THREAD_POOL_SIZE = Telnet_Multi.NORMAL_TELNET_LIMIT;
    private static final int MAX_THREAD_POOL_SIZE = 30;
    private static final long RETRY_DELAY_MS = 3000;
    private static final int MAX_RETRY = 3;
    private static final int MAX_RERUN_IF_LOG_INCOMPLETE = 1;
    private static final long DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MS = 120000L;
    private static final AtomicInteger ACTIVE_TASKS = new AtomicInteger(0);
//  Summary counters
    public static final AtomicInteger successCount = new AtomicInteger(0);
    public static final AtomicInteger failCount = new AtomicInteger(0);
    public static final AtomicInteger stoppedCount = new AtomicInteger(0);
    public static final AtomicInteger authFailCount = new AtomicInteger(0);
    public static final AtomicInteger networkFailCount = new AtomicInteger(0);
    public static final AtomicInteger incompleteFailCount = new AtomicInteger(0);
    public static final AtomicInteger vendorFailCount = new AtomicInteger(0);
    public static final AtomicInteger logMissingFailCount = new AtomicInteger(0);
    public static final AtomicInteger cmdSetFailCount = new AtomicInteger(0);
    private static volatile boolean alarmEnabled = true;
    private static volatile boolean backgroundWorkersActive = true;
    private static java.util.Timer antiSleepTimer;

    public static void resetRunCounters() {
        successCount.set(0);
        failCount.set(0);
        stoppedCount.set(0);
        authFailCount.set(0);
        networkFailCount.set(0);
        incompleteFailCount.set(0);
        vendorFailCount.set(0);
        logMissingFailCount.set(0);
        cmdSetFailCount.set(0);
    }

    public static int getSuccessCount() {
        return successCount.get();
    }

    public static int getFailCount() {
        return failCount.get();
    }

    public static int getStoppedCount() {
        return stoppedCount.get();
    }

    public static int getAuthFailCount() {
        return authFailCount.get();
    }

    public static int getNetworkFailCount() {
        return networkFailCount.get();
    }

    public static int getIncompleteFailCount() {
        return incompleteFailCount.get();
    }

    public static int getVendorFailCount() {
        return vendorFailCount.get();
    }

    public static int getLogMissingFailCount() {
        return logMissingFailCount.get();
    }

    public static int getCmdSetFailCount() {
        return cmdSetFailCount.get();
    }

    public static void recordStoppedTask() {
        stoppedCount.incrementAndGet();
    }

    public static void recordAuthFailure() {
        incrementFailure(authFailCount);
    }

    public static void recordNetworkFailure() {
        incrementFailure(networkFailCount);
    }

    public static void recordIncompleteFailure() {
        incrementFailure(incompleteFailCount);
    }

    public static void recordVendorFailure() {
        incrementFailure(vendorFailCount);
    }

    public static void recordLogMissingFailure() {
        incrementFailure(logMissingFailCount);
    }

    public static void recordCmdSetFailure() {
        incrementFailure(cmdSetFailCount);
    }

    public static void recordTaskFailure(Throwable error) {
        String text = error == null ? "" : String.valueOf(error.getMessage());
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "auth fail", "authentication", "password", "credential", "login failed",
                "permission denied", "too many authentication failures")) {
            recordAuthFailure();
        } else if (containsAny(lower, "cmdset", "cmd set", "command set", "cannot read last command")) {
            recordCmdSetFailure();
        } else if (containsAny(lower, "log missing", "missing log", "file not found")) {
            recordLogMissingFailure();
        } else if (containsAny(lower, "incomplete", "missing last command", "missing session end")) {
            recordIncompleteFailure();
        } else if (containsAny(lower, "wrong vendor", "vendor mismatch")) {
            recordVendorFailure();
        } else {
            recordNetworkFailure();
        }
    }

    private static void incrementFailure(AtomicInteger typeCounter) {
        failCount.incrementAndGet();
        typeCounter.incrementAndGet();
    }

    private static boolean containsAny(String text, String... tokens) {
        if (text == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isEmpty() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAlarmEnabled() {
        return alarmEnabled;
    }

    public static void setAlarmEnabled(boolean enabled) {
        alarmEnabled = enabled;
    }

    public static boolean shouldBackgroundWorkersRun() {
        return backgroundWorkersActive && !shutdownRequested;
    }

    private static synchronized void stopBackgroundWorkers() {
        backgroundWorkersActive = false;
        alarmEnabled = false;
        Telnet_Multi.stopBackgroundMonitors();
        if (antiSleepTimer != null) {
            antiSleepTimer.cancel();
            antiSleepTimer.purge();
            antiSleepTimer = null;
        }
    }

//  For runtime measurement
    private static final long startTime = System.currentTimeMillis();

    private static final java.io.PrintStream realOut
            = AppConsole.createPrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false);
    private static final Object BOT_LOG_LOCK = new Object();
    private static final DateTimeFormatter BOT_LOG_DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int BOT_LOG_BUFFER_SIZE = 16 * 1024;
    private static final long BOT_LOG_FLUSH_INTERVAL_MS = 1500L;
    private static final long PROGRESS_PRINT_INTERVAL_MS = 400L;
    private static final java.util.Map<String, BufferedWriter> BOT_LOG_WRITERS = new java.util.HashMap<>();
    private static final ConcurrentMap<String, Long> BOT_LOG_LAST_FLUSH_MS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, List<String>> CMDSET_COMMAND_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> CMDSET_FIRST_COMMAND_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> CMDSET_LAST_COMMAND_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Integer> CMDSET_COMMAND_COUNT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, CachedDeviceRow> DEVICE_ROW_CACHE = new ConcurrentHashMap<>();
    private static final int FIRST_COMMAND_HEAD_BYTES_TO_SCAN = 64 * 1024;
    private static final int FIRST_COMMAND_HEAD_LINES_TO_SCAN = 160;
    private static final int LAST_COMMAND_TAIL_BYTES_TO_SCAN = 128 * 1024;
    private static final int LAST_COMMAND_TAIL_LINES_TO_SCAN = 240;
    private static final List<String> PREFERRED_CLLS_VALIDATION_IPS = Collections.unmodifiableList(Arrays.asList(
            "10.163.0.113",
            "10.165.0.173",
            "10.165.0.153",
            "10.163.10.121",
            "10.163.10.161",
            "10.163.0.111",
            "10.163.0.131",
            "10.163.10.101",
            "10.163.0.133",
            "10.163.254.151",
            "10.165.0.103",
            "10.165.0.163",
            "10.163.254.171",
            "10.163.0.141",
            "10.163.10.181"
    ));
    private static volatile CachedSettings cachedSettings = CachedSettings.empty();
    private static volatile long lastProgressConsoleUpdateMs = 0L;
    private static final int GATEWAY_SSH_PORT = 22;
    private static final int GATEWAY_TELNET_PORT = 23;
    private static final String DEFAULT_CLLS_VALIDATION_DEVICE = "CLLS validation node";
    private static final String DEFAULT_CLLS_VALIDATION_CMDSET = "HW-VALIDATION";
    private static final String DEFAULT_GATEWAY_USERNAME = "kantap4";
    private static final String DEFAULT_GATEWAY_PASSWORD = "kan84477";
    private static final DataFormatter EXCEL_FORMATTER = createExcelFormatter();
    static final String CMDSET_SHEET = "cmdSet";
    static final String TRUE_SETTING_SHEET = "_setting_TRUE";
    static final String TRUE_DEVICE_SHEET = "deviceList_TRUE";
    private static final String LEGACY_SETTING_SHEET = "_setting";
    private static final String LEGACY_DEVICE_SHEET = "deviceList";

    private static final class CachedSettings {
        final String server;
        final String userServer;
        final String pwServer;
        final String userCLLS;
        final String pwCLLS;
        final String userL2;
        final String pwL2;

        CachedSettings(String server, String userServer, String pwServer,
                String userCLLS, String pwCLLS, String userL2, String pwL2) {
            this.server = safeValue(server);
            this.userServer = safeValue(userServer);
            this.pwServer = safeValue(pwServer);
            this.userCLLS = safeValue(userCLLS);
            this.pwCLLS = safeValue(pwCLLS);
            this.userL2 = safeValue(userL2);
            this.pwL2 = safeValue(pwL2);
        }

        static CachedSettings empty() {
            return new CachedSettings("", "", "", "", "", "", "");
        }

        boolean isConfigured() {
            return !server.isEmpty();
        }
    }

    private static DataFormatter createExcelFormatter() {
        DataFormatter formatter = new DataFormatter();
        formatter.setUseCachedValuesForFormulaCells(true);
        return formatter;
    }


    private static final class CachedDeviceRow {
        final String group;
        final String device;
        final String loopback;

        CachedDeviceRow(String group, String device, String loopback) {
            this.group = safeValue(group);
            this.device = safeValue(device);
            this.loopback = safeValue(loopback);
        }
    }

    private static final class ValidationTarget {
        final int rowNum;
        final String device;
        final String loopback;
        final String cmdSet;

        ValidationTarget(int rowNum, String device, String loopback, String cmdSet) {
            this.rowNum = rowNum;
            this.device = safeValue(device);
            this.loopback = safeValue(loopback);
            this.cmdSet = safeValue(cmdSet);
        }

        String displayLabel() {
            StringBuilder sb = new StringBuilder();
            if (!device.isEmpty()) {
                sb.append(device);
            }
            if (!loopback.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append('[').append(loopback).append(']');
            }
            if (!cmdSet.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append('(').append(cmdSet).append(')');
            }
            return sb.length() == 0 ? "selected node" : sb.toString();
        }
    }

    private static final class CredentialInput {
        final String username;
        final String password;

        CredentialInput(String username, String password) {
            this.username = safeValue(username);
            this.password = safeValue(password);
        }
    }

    private static final class NodeCommandTask {
        final int rowNum;
        final String device;
        final String loopback;
        final String cmdSet;
        final int cmdSetOrder;

        NodeCommandTask(int rowNum, String device, String loopback, String cmdSet, int cmdSetOrder) {
            this.rowNum = rowNum;
            this.device = safeValue(device);
            this.loopback = safeValue(loopback);
            this.cmdSet = safeValue(cmdSet);
            this.cmdSetOrder = cmdSetOrder;
        }
    }

    private static BufferedWriter getBotLogWriter(String filePath) throws IOException {
        BufferedWriter writer = BOT_LOG_WRITERS.get(filePath);
        if (writer == null) {
            writer = new BufferedWriter(new FileWriter(filePath, true), BOT_LOG_BUFFER_SIZE);
            BOT_LOG_WRITERS.put(filePath, writer);
        }
        return writer;
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (BOT_LOG_LOCK) {
                for (BufferedWriter writer : BOT_LOG_WRITERS.values()) {
                    try {
                        writer.flush();
                        writer.close();
                    } catch (IOException ignore) {
                    }
                }
                BOT_LOG_WRITERS.clear();
                BOT_LOG_LAST_FLUSH_MS.clear();
            }
        }));
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cacheKey(String value) {
        return safeValue(value).toLowerCase(Locale.ROOT);
    }

    static Sheet getSheetAny(Workbook workbook, String... sheetNames) {
        if (workbook == null || sheetNames == null) {
            return null;
        }
        for (String sheetName : sheetNames) {
            if (sheetName == null || sheetName.trim().isEmpty()) {
                continue;
            }
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet != null) {
                return sheet;
            }
        }
        return null;
    }

    private static boolean shouldForceLogFlush(String text) {
        String normalized = safeValue(text).toLowerCase(Locale.ROOT);
        return normalized.contains("[error]")
                || normalized.contains("[fail]")
                || normalized.contains("[warn]")
                || normalized.contains("[summary]")
                || normalized.contains("all devices completed")
                || normalized.contains("[auto]");
    }

    private static void flushBotLogIfNeeded(String filePath, BufferedWriter writer, String text) throws IOException {
        long now = System.currentTimeMillis();
        long lastFlush = BOT_LOG_LAST_FLUSH_MS.getOrDefault(filePath, 0L);
        if (shouldForceLogFlush(text) || now - lastFlush >= BOT_LOG_FLUSH_INTERVAL_MS) {
            writer.flush();
            BOT_LOG_LAST_FLUSH_MS.put(filePath, now);
        }
    }

    private static void rebuildExcelCache(Workbook workbook) {
        rebuildExcelCache(workbook, TRUE_DEVICE_SHEET, LEGACY_DEVICE_SHEET);
    }

    private static void rebuildExcelCache(Workbook workbook, String... deviceSheetNames) {
        rebuildExcelCacheForSelection(workbook, "", 0, 0, deviceSheetNames);
    }

    private static void rebuildExcelCacheForSelection(Workbook workbook, String deviceSelectMode,
            int deviceRowStart, int deviceRowEnd, String... deviceSheetNames) {
        CMDSET_COMMAND_CACHE.clear();
        CMDSET_FIRST_COMMAND_CACHE.clear();
        CMDSET_LAST_COMMAND_CACHE.clear();
        CMDSET_COMMAND_COUNT_CACHE.clear();
        DEVICE_ROW_CACHE.clear();

        if (workbook == null) {
            return;
        }

        System.out.println("[INFO] Rebuilding Excel cache...");

        try {
            Sheet cmdSheet = workbook.getSheet(CMDSET_SHEET);
            if (cmdSheet != null && cmdSheet.getRow(0) != null) {
                Row headerRow = cmdSheet.getRow(0);
                for (int col = 0; col < headerRow.getLastCellNum(); col++) {
                    Cell headerCell = headerRow.getCell(col);
                    String cmdSet = safeValue(getCellValue(headerCell));
                    if (cmdSet.isEmpty()) {
                        continue;
                    }

                    List<String> commands = new ArrayList<>();
                    for (int rowIdx = 0; rowIdx <= cmdSheet.getLastRowNum(); rowIdx++) {
                        Row currentRow = cmdSheet.getRow(rowIdx);
                        if (currentRow == null || currentRow.getCell(col) == null) {
                            continue;
                        }
                        String value = safeValue(getCellValue(currentRow.getCell(col)));
                        if (!value.isEmpty()) {
                            commands.add(value);
                        }
                    }

                    String key = cacheKey(cmdSet);
                    CMDSET_COMMAND_CACHE.put(key, Collections.unmodifiableList(commands));
                    CMDSET_COMMAND_COUNT_CACHE.put(key, Math.max(0, commands.size() - 1));
                    CMDSET_FIRST_COMMAND_CACHE.put(key, commands.size() > 1 ? commands.get(1) : "");
                    CMDSET_LAST_COMMAND_CACHE.put(key, commands.size() > 1 ? commands.get(commands.size() - 1) : "");
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Failed to build cmdSet cache: " + e.getMessage());
        }

        try {
            Sheet deviceSheet = getSheetAny(workbook, deviceSheetNames);
            if (deviceSheet != null) {
                for (Row row : deviceSheet) {
                    if (row == null) {
                        continue;
                    }
                    int cacheRowNum = row.getRowNum() + 1;
                    String runFlag = shouldRunDeviceRow(row, deviceSelectMode, deviceRowStart, deviceRowEnd)
                            ? "Y" : getCell(row, 0);
                    DEVICE_ROW_CACHE.put(cacheRowNum,
                            new CachedDeviceRow(runFlag, getCell(row, 2), getCell(row, 3)));
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Failed to build device cache: " + e.getMessage());
        }

        System.out.printf("[INFO] Excel cache ready. cmdSets=%d, deviceRows=%d%n",
                CMDSET_COMMAND_CACHE.size(), DEVICE_ROW_CACHE.size());
    }

    private static void updateCachedSettings(String server, String userServer, String pwServer,
            String userCLLS, String pwCLLS, String userL2, String pwL2) {
        cachedSettings = new CachedSettings(server, userServer, pwServer, userCLLS, pwCLLS, userL2, pwL2);
    }

    private static String normalizeSettingKey(String key) {
        return safeValue(key)
                .toLowerCase(Locale.ROOT)
                .replace(":", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
    }

    private static Map<String, String> readSettingValues(Sheet sheet) {
        Map<String, String> values = new LinkedHashMap<>();
        if (sheet == null) {
            return values;
        }

        FormulaEvaluator evaluator = null;
        try {
            evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();
        } catch (Exception ignored) {
        }

        for (Row row : sheet) {
            if (row == null) {
                continue;
            }
            String key = normalizeSettingKey(getCell(row, 0));
            if (key.isEmpty()) {
                continue;
            }
            values.put(key, safeValue(getSettingCellValue(row.getCell(1), evaluator)));
        }
        return values;
    }

    private static String getSettingCellValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }

        if (cell.getCellType() == CellType.FORMULA && evaluator != null) {
            try {
                return EXCEL_FORMATTER.formatCellValue(cell, evaluator).trim();
            } catch (Exception ex) {
                System.out.println("[WARN] Cannot evaluate setting formula "
                        + cell.getSheet().getSheetName() + "!" + cell.getAddress()
                        + " : " + ex.getMessage());
            }
        }

        return getCellValue(cell);
    }

    private static String firstSettingValue(Map<String, String> settings, String... aliases) {
        if (settings == null || aliases == null) {
            return "";
        }

        for (String alias : aliases) {
            String value = safeValue(settings.get(normalizeSettingKey(alias)));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static int firstSettingInt(Map<String, String> settings, int defaultValue, String... aliases) {
        String value = firstSettingValue(settings, aliases);
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

    private static int clampThreadPoolSize(int configuredThreadPoolSize, int taskCount) {
        if (taskCount <= 0) {
            return 1;
        }
        int configured = configuredThreadPoolSize <= 0 ? DEFAULT_THREAD_POOL_SIZE : configuredThreadPoolSize;
        int capped = Math.min(configured, MAX_THREAD_POOL_SIZE);
        return Math.max(1, Math.min(capped, taskCount));
    }

    private static boolean shouldRunDeviceRow(Row row, String deviceSelectMode, int deviceRowStart, int deviceRowEnd) {
        if (row == null) {
            return false;
        }
        String mode = safeValue(deviceSelectMode);
        if ("All".equalsIgnoreCase(mode)) {
            return true;
        }
        if ("Y".equalsIgnoreCase(getCell(row, 0))) {
            return true;
        }
        int excelRow = row.getRowNum() + 1;
        return deviceRowStart > 0 && deviceRowEnd >= deviceRowStart
                && excelRow >= deviceRowStart && excelRow <= deviceRowEnd;
    }

    private static String normalizeGatewayAddressValue(String value) {
        String normalized = safeValue(value).replace('\\', '/');
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replaceFirst("(?i)^(ssh|telnet):/+", "$1://");
    }

    private static String extractGatewayScheme(String value) {
        String normalized = normalizeGatewayAddressValue(value).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ssh://")) {
            return "ssh";
        }
        if (normalized.startsWith("telnet://")) {
            return "telnet";
        }
        return "";
    }

    private static String stripGatewayScheme(String value) {
        String normalized = normalizeGatewayAddressValue(value);
        String scheme = extractGatewayScheme(normalized);
        if (scheme.isEmpty()) {
            return normalized;
        }
        return normalized.substring((scheme + "://").length()).trim();
    }

    private static String buildGatewayServerValue(String hostValue, String portValue) {
        String normalizedHost = normalizeGatewayAddressValue(hostValue);
        String normalizedPort = safeValue(portValue);
        if (normalizedHost.isEmpty()) {
            return "";
        }

        if (normalizedPort.matches("\\d+\\.0+")) {
            normalizedPort = normalizedPort.substring(0, normalizedPort.indexOf('.'));
        }

        String embeddedScheme = extractGatewayScheme(normalizedHost);
        String hostOnly = stripGatewayScheme(normalizedHost);

        if (!normalizedPort.isEmpty()) {
            if (!normalizedPort.matches("\\d+")) {
                throw new IllegalArgumentException("Invalid 'gatewayPort' in " + TRUE_SETTING_SHEET + " sheet. Use 22 for SSH or 23 for TELNET.");
            }

            int port = Integer.parseInt(normalizedPort);
            if (port == GATEWAY_SSH_PORT) {
                return "ssh://" + hostOnly + ":" + port;
            }
            if (port == GATEWAY_TELNET_PORT) {
                return "telnet://" + hostOnly + ":" + port;
            }

            throw new IllegalArgumentException("Unsupported 'gatewayPort' in " + TRUE_SETTING_SHEET + " sheet. Use 22 for SSH or 23 for TELNET.");
        }

        if (!embeddedScheme.isEmpty()) {
            return embeddedScheme + "://" + hostOnly;
        }

        return normalizedHost;
    }

    private static CachedSettings loadSettingsFromSheet(Sheet sheet) {
        if (sheet == null) {
            throw new IllegalArgumentException("Missing '" + TRUE_SETTING_SHEET + "' sheet.");
        }

        Map<String, String> settings = readSettingValues(sheet);
        String legacyServer = firstSettingValue(settings, "server");
        String legacySshHost = firstSettingValue(settings, "sshHost");
        String gatewayHost = firstSettingValue(settings, "gatewayHost", "sshHost", "server");
        String gatewayPort = firstSettingValue(settings, "gatewayPort");

        String server = buildGatewayServerValue(gatewayHost, gatewayPort);
        if (server.isEmpty()) {
            server = buildGatewayServerValue(legacyServer, "");
        }
        if (server.isEmpty() && !legacySshHost.isEmpty()) {
            server = "ssh://" + stripGatewayScheme(legacySshHost);
        }
        if (server.isEmpty()) {
            throw new IllegalArgumentException("Missing 'gatewayHost' in " + TRUE_SETTING_SHEET + " sheet.");
        }

        String userServer = firstSettingValue(settings, "gatewayUsername", "sshUsername", "userServer");
        String pwServer = firstSettingValue(settings, "gatewayPassword", "sshPassword", "pwServer");
        String userCLLS = firstSettingValue(settings, "cllsUsername", "userCLLS");
        String pwCLLS = firstSettingValue(settings, "cllsPassword", "pwCLLS");
        String userL2 = firstSettingValue(settings, "L2Username", "userL2");
        String pwL2 = firstSettingValue(settings, "L2Password", "pwL2");

        if (userServer.isEmpty()) {
            userServer = DEFAULT_GATEWAY_USERNAME;
            System.out.println("[INFO] Default gatewayUsername applied: " + userServer);
        }
        if (pwServer.isEmpty()) {
            pwServer = DEFAULT_GATEWAY_PASSWORD;
            System.out.println("[INFO] Default gatewayPassword applied: ********");
        }

        return new CachedSettings(server, userServer, pwServer, userCLLS, pwCLLS, userL2, pwL2);
    }

    public static int copyCachedCommands(String cmdSet, String[] targetCommand) {
        if (targetCommand == null) {
            return 0;
        }
        List<String> commands = CMDSET_COMMAND_CACHE.get(cacheKey(cmdSet));
        if (commands == null || commands.isEmpty()) {
            return 0;
        }

        int copyCount = Math.min(commands.size(), targetCommand.length);
        Arrays.fill(targetCommand, null);
        for (int i = 0; i < copyCount; i++) {
            targetCommand[i] = commands.get(i);
        }
        return copyCount;
    }

    public static String getCachedLastCommand(String cmdSet) {
        return CMDSET_LAST_COMMAND_CACHE.getOrDefault(cacheKey(cmdSet), "");
    }

    public static String getCachedFirstCommand(String cmdSet) {
        return CMDSET_FIRST_COMMAND_CACHE.getOrDefault(cacheKey(cmdSet), "");
    }

    public static int getCachedCommandCount(String cmdSet) {
        return CMDSET_COMMAND_COUNT_CACHE.getOrDefault(cacheKey(cmdSet), 0);
    }

    public static boolean isRetryEnabledForRow(int rowNum) {
        CachedDeviceRow cachedRow = DEVICE_ROW_CACHE.get(rowNum);
        return cachedRow != null && "Y".equalsIgnoreCase(cachedRow.group);
    }

    private static CachedDeviceRow getCachedDeviceRow(int rowNum) {
        return DEVICE_ROW_CACHE.get(rowNum);
    }

    private static CachedSettings getCachedSettings() {
        return cachedSettings;
    }

    private static boolean usesCllsCredentials(String cmdSet) {
        String value = safeValue(cmdSet);
        if (value.isEmpty()) {
            return false;
        }
        return Character.toUpperCase(value.charAt(0)) != 'L';
    }

    private static boolean supportsImmediateCllsValidation(String cmdSet) {
        String value = safeValue(cmdSet);
        if (value.isEmpty()) {
            return false;
        }
        char family = Character.toUpperCase(value.charAt(0));
        return family == 'H' || family == 'N' || family == 'Z' || family == 'J';
    }

    private static String findValidationCmdSet(Row row) {
        if (row == null) {
            return "";
        }
        for (int col = 4; col < row.getLastCellNum(); col++) {
            String cmdSet = getCell(row, col).trim();
            if (usesCllsCredentials(cmdSet) && supportsImmediateCllsValidation(cmdSet)) {
                return cmdSet;
            }
        }
        return "";
    }

    private static List<ValidationTarget> collectValidationTargets(Sheet deviceSheet) {
        List<ValidationTarget> targets = new ArrayList<>();
        Map<String, ValidationTarget> targetByIp = new LinkedHashMap<>();
        Set<String> preferredIpSet = new LinkedHashSet<>(PREFERRED_CLLS_VALIDATION_IPS);

        if (deviceSheet != null) {
            for (Row row : deviceSheet) {
                if (row == null) {
                    continue;
                }

                String loopback = getCell(row, 3);
                String device = getCell(row, 2);
                String validationCmdSet = findValidationCmdSet(row);
                if (loopback.isEmpty() || device.isEmpty() || validationCmdSet.isEmpty()) {
                    continue;
                }

                int rowNum = row.getRowNum() + 1;
                ValidationTarget target = new ValidationTarget(rowNum, device, loopback, validationCmdSet);
                if (preferredIpSet.contains(loopback) && !targetByIp.containsKey(loopback)) {
                    targetByIp.put(loopback, target);
                }
            }
        }

        for (String preferredIp : PREFERRED_CLLS_VALIDATION_IPS) {
            ValidationTarget target = targetByIp.get(preferredIp);
            if (target == null) {
                target = new ValidationTarget(0, DEFAULT_CLLS_VALIDATION_DEVICE, preferredIp, DEFAULT_CLLS_VALIDATION_CMDSET);
            }
            targets.add(target);
        }

        return targets;
    }

    private static CredentialInput showCllsCredentialDialog(String initialUsername, String initialPassword) {
        JTextField usernameField = new JTextField(safeValue(initialUsername), 22);
        JPasswordField passwordField = new JPasswordField(safeValue(initialPassword), 22);
        StopProgram owner = StopProgram.getInstance();
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
        panel.add(new JLabel("<html>Please enter your TRUE CLLS login credentials.<br>"
                + "(Make sure your keyboard is set to English before typing.)</html>"), BorderLayout.NORTH);
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

        if (owner != null) {
            owner.toFront();
            owner.requestFocus();
        }
        System.out.println("[INFO] Waiting for CLLS Login dialog input.");
        int choice = JOptionPane.showConfirmDialog(
                owner,
                panel,
                "BotGetLog [TRUE] Login",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }

        return new CredentialInput(usernameField.getText(), new String(passwordField.getPassword()));
    }

    private static int showValidationRecoveryDialog(List<String> messages) {
        StopProgram owner = StopProgram.getInstance();
        StringBuilder detail = new StringBuilder();
        if (messages != null) {
            int shown = 0;
            for (String message : messages) {
                String value = safeValue(message);
                if (value.isEmpty()) {
                    continue;
                }
                if (shown == 0) {
                    detail.append("\n\n");
                } else {
                    detail.append('\n');
                }
                detail.append("- ").append(value);
                shown++;
                if (shown >= 3) {
                    break;
                }
            }
        }

        Object[] options = new Object[]{"Retry validation", "Continue anyway", "Cancel"};
        return JOptionPane.showOptionDialog(
                owner,
                "The validation nodes could not confirm this login yet."
                + "\nThis can happen when the selected nodes are down, unreachable, or show an unexpected prompt."
                + detail.toString()
                + "\n\nChoose Retry to try again, Continue anyway to start the batch without validation, or Cancel to stop.",
                "CLLS Validation",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0]
        );
    }

    private static CredentialInput promptForRequiredCllsCredentials(String initialUsername, String initialPassword) {
        String currentUsername = safeValue(initialUsername);
        String currentPassword = safeValue(initialPassword);
        while (true) {
            CredentialInput entered = showCllsCredentialDialog(currentUsername, currentPassword);
            if (entered == null) {
                return null;
            }

            currentUsername = safeValue(entered.username);
            currentPassword = safeValue(entered.password);
            if (currentUsername.isEmpty() || currentPassword.isEmpty()) {
                JOptionPane.showMessageDialog(
                        StopProgram.getInstance(),
                        "Username and Password are required before continuing.",
                        "CLLS Login",
                        JOptionPane.ERROR_MESSAGE
                );
                continue;
            }

            return new CredentialInput(currentUsername, currentPassword);
        }
    }

    private static CredentialInput promptForValidatedCllsCredentials(
            String server,
            String userServer,
            String pwServer,
            String initialUsername,
            String initialPassword,
            List<ValidationTarget> validationTargets) {

        String currentUsername = safeValue(initialUsername);
        String currentPassword = safeValue(initialPassword);
        boolean promptBeforeValidation = currentUsername.isEmpty() || currentPassword.isEmpty();

        while (true) {
            if (promptBeforeValidation) {
                CredentialInput entered = showCllsCredentialDialog(currentUsername, currentPassword);
                if (entered == null) {
                    return null;
                }

                currentUsername = safeValue(entered.username);
                currentPassword = safeValue(entered.password);
            }

            if (currentUsername.isEmpty()) {
                JOptionPane.showMessageDialog(
                        null,
                        "Username is required before continuing.",
                        "CLLS Login",
                        JOptionPane.ERROR_MESSAGE
                );
                promptBeforeValidation = true;
                continue;
            }
            if (currentPassword.isEmpty()) {
                JOptionPane.showMessageDialog(
                        null,
                        "Password is required before continuing.",
                        "CLLS Login",
                        JOptionPane.ERROR_MESSAGE
                );
                promptBeforeValidation = true;
                continue;
            }

            if (validationTargets == null || validationTargets.isEmpty()) {
                return new CredentialInput(currentUsername, currentPassword);
            }

            List<String> retryableMessages = new ArrayList<>();
            boolean invalidCredentials = false;
            boolean gatewayConfirmationOnlyFailures = true;
            List<ValidationTarget> targetsToTry = new ArrayList<>(validationTargets);

            for (int i = 0; i < targetsToTry.size(); i++) {
                ValidationTarget target = targetsToTry.get(i);
                System.out.println(String.format(
                        "[INFO] Validating CLLS credentials on %s (%d/%d)",
                        safeValue(target.displayLabel()),
                        i + 1,
                        targetsToTry.size()
                ));
                Telnet_Multi.LoginValidationResult result = Telnet_Multi.validateCllsCredentials(
                        server, userServer, pwServer,
                        target.loopback, currentUsername, currentPassword,
                        target.cmdSet, target.device
                );

                if (result.isSuccess()) {
                    return new CredentialInput(currentUsername, currentPassword);
                }

                if (result.isInvalidCredentials()) {
                    invalidCredentials = true;
                    break;
                }

                if (!result.isGatewayConfirmationRetryable()) {
                    gatewayConfirmationOnlyFailures = false;
                }

                String message = safeValue(result.message);
                if (!message.isEmpty()) {
                    retryableMessages.add(message);
                }
            }

            if (invalidCredentials) {
                JOptionPane.showMessageDialog(
                        null,
                        "Username or Password is incorrect.\nPlease try again.",
                        "CLLS Login Failed",
                        JOptionPane.ERROR_MESSAGE
                );
                currentPassword = "";
                promptBeforeValidation = true;
                continue;
            }

            if (gatewayConfirmationOnlyFailures && retryableMessages.size() >= 3) {
                System.out.println("[INFO] CLLS validation could not confirm the gateway prompt; continuing with the supplied credentials.");
                return new CredentialInput(currentUsername, currentPassword);
            }

            int choice = showValidationRecoveryDialog(retryableMessages);
            if (choice == 0) {
                promptBeforeValidation = false;
                continue;
            }
            if (choice == 1) {
                return new CredentialInput(currentUsername, currentPassword);
            }
            return null;
        }
    }

    private static Set<String> loadAllConnectionFailedKeys(PathFile fileInput) {
        Set<String> keys = new HashSet<>();
        try {
            File logDir = new File(fileInput.getLogWork());
            if (!logDir.exists() || !logDir.isDirectory()) {
                return keys;
            }

            File[] failFiles = logDir.listFiles((dir, name)
                    -> name != null
                    && name.startsWith("Node_ConnectionFailed_")
                    && name.endsWith(".txt"));

            if (failFiles == null || failFiles.length == 0) {
                return keys;
            }

            Arrays.sort(failFiles, Comparator.comparing(File::getName));

            Pattern ptn = Pattern.compile("\\[(\\d+)](\\d+\\.\\d+\\.\\d+\\.\\d+)_.*", Pattern.CASE_INSENSITIVE);
            for (File failFile : failFiles) {
                try (BufferedReader br = new BufferedReader(new FileReader(failFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Matcher m = ptn.matcher(line);
                        if (m.find()) {
                            String rowNum = m.group(1).trim();
                            String ip = m.group(2).trim();
                            keys.add(rowNum + "," + ip);
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[SKIP-CONN-FAIL] Unable to read file: "
                            + failFile.getName() + " : " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("[SKIP-CONN-FAIL] Unable to read Node_ConnectionFailed logs: " + e.getMessage());
        }
        return keys;
    }

    private static boolean isConnectionFailed(Set<String> failedKeys, int rowNum, String loopback) {
        if (failedKeys == null || failedKeys.isEmpty()) {
            return false;
        }
        return failedKeys.contains(rowNum + "," + loopback);
    }

    public static void main(String[] args) {
        AppConsole.install();
        if (!AppMetadata.isRunningFromIde() && AutoUpdateManager.checkForUpdatesAtStartup()) {
            return;
        }
        backgroundWorkersActive = true;

        //   Sleep +  beep - 5 -
        antiSleepTimer = new java.util.Timer("anti-sleep", true);
        antiSleepTimer.schedule(new TimerTask() {
            Robot robot;
            int toggle = 1;

            {
                try {
                    robot = new Robot();
                } catch (Exception e) {
                    System.out.println("[WARN] Cannot initialize Robot: " + e.getMessage());
                }
            }

            @Override
            public void run() {
                if (!shouldBackgroundWorkersRun()) {
                    return;
                }
                try {
                    if (robot != null) {
                        //  
                        Point p = MouseInfo.getPointerInfo().getLocation();
                        robot.mouseMove(p.x + toggle, p.y + toggle);
                        robot.mouseMove(p.x, p.y);
                        toggle = -toggle;

                        if (BotGetLog_TrueCorp.isAlarmEnabled()) {
                            Toolkit.getDefaultToolkit().beep();
                        }

                        System.out.println("[ANTI-SLEEP] Mouse moved & beeped at "
                                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                    }
                } catch (Exception ignored) {
                }
            }
        }, 0, 5000); //  - 5 -

        //   GUI  Event Dispatch Thread
        SwingUtilities.invokeLater(StopProgram::new);

        //   Thread   GUI
        new Thread(() -> {
            while (StopProgram.getInstance() == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            System.out.println("[STARTUP] BotGetLog Multithread initialized");

            Dialog.setLAF();
            Dialog D = new Dialog();
            PathFile FileInput = new PathFile();
            //   Log 
            new File(FileInput.getLog()).mkdirs();
            new File(FileInput.getLogWork()).mkdirs();

            CredentialInput startupCllsCredentials = promptForRequiredCllsCredentials("", "");
            if (startupCllsCredentials == null) {
                realOut.println("[INFO] TRUE startup cancelled before Excel check.");
                requestImmediateShutdown("CLLS login dialog cancelled", 0);
                return;
            }

            int Num_row = 2; //  -- 2  ( header)

            Workbook workbook = null;

            try {
                File excelFile = new File(FileInput.getUserInterface_Input());
                if (!excelFile.exists()) {
                    throw new FileNotFoundException(" Excel file not found: " + excelFile.getAbsolutePath());
                }

                File tempCopy = new File(System.getProperty("java.io.tmpdir"),
                        "excelCopy_" + System.currentTimeMillis() + ".xlsx");
                Files.copy(excelFile.toPath(), tempCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);

                int retryCount = 0;
                while (retryCount < MAX_RETRY) {
                    try ( InputStream fis = new BufferedInputStream(new FileInputStream(tempCopy))) {
                        workbook = WorkbookFactory.create(fis);
                        realOut.printf("[OK] Excel opened successfully: %s%n", excelFile.getName());
                        break;
                    } catch (InvalidOperationException | POIXMLException e) {
                        retryCount++;
                        if (retryCount >= MAX_RETRY) {
                            throw e;
                        }
                        realOut.printf("[WARN] Excel not ready (zip busy), retry %d/%d%n", retryCount, MAX_RETRY);
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }
                Sheet trueSettingSheet = getSheetAny(workbook, TRUE_SETTING_SHEET, LEGACY_SETTING_SHEET);
                Sheet trueDeviceSheet = getSheetAny(workbook, TRUE_DEVICE_SHEET, LEGACY_DEVICE_SHEET);
                if (trueSettingSheet == null) {
                    D.Error("Missing '" + TRUE_SETTING_SHEET + "' sheet.");
                    return;
                }

                String server = "", User_server = "", PW_server = "";
                String User_CLLS = "", PW_CLLS = "";
                String User_L2 = "", PW_L2 = "";

                String trueSettingSheetName = trueSettingSheet.getSheetName();
                String trueDeviceSheetName = trueDeviceSheet != null ? trueDeviceSheet.getSheetName() : TRUE_DEVICE_SHEET;
                Map<String, String> trueSettings = readSettingValues(trueSettingSheet);
                String deviceSelectMode = firstSettingValue(trueSettings, "deviceSelectMode");
                int deviceRowStart = firstSettingInt(trueSettings, 0, "deviceRowStart");
                int deviceRowEnd = firstSettingInt(trueSettings, 0, "deviceRowEnd");
                int configuredThreadPoolSize = firstSettingInt(trueSettings, DEFAULT_THREAD_POOL_SIZE,
                        "threadPoolSize", "thread", "threads", "maxThreads");

                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    Sheet sheet = workbook.getSheetAt(i);

                    if (trueSettingSheetName.equals(sheet.getSheetName())) {
                        CachedSettings loadedSettings;
                        try {
                            loadedSettings = loadSettingsFromSheet(sheet);
                        } catch (IllegalArgumentException e) {
                            D.Error(e.getMessage());
                            return;
                        }

                        server = loadedSettings.server;
                        User_server = loadedSettings.userServer;
                        PW_server = loadedSettings.pwServer;
                        User_CLLS = loadedSettings.userCLLS;
                        PW_CLLS = loadedSettings.pwCLLS;
                        User_L2 = loadedSettings.userL2;
                        PW_L2 = loadedSettings.pwL2;
                        User_CLLS = startupCllsCredentials.username;
                        PW_CLLS = startupCllsCredentials.password;

//  CLLS credentials
                        List<ValidationTarget> validationTargets = collectValidationTargets(null);
                        CredentialInput enteredCredentials = promptForValidatedCllsCredentials(
                                server, User_server, PW_server,
                                User_CLLS, PW_CLLS,
                                validationTargets
                        );
                        if (enteredCredentials == null) {
                            realOut.println("[INFO] TRUE startup cancelled before CLLS validation.");
                            requestImmediateShutdown("CLLS login validation cancelled", 0);
                            return;
                        }
                        User_CLLS = enteredCredentials.username;
                        PW_CLLS = enteredCredentials.password;

//   L2:   ()
                        if (User_L2 == null) {
                            User_L2 = "";
                        }
                        if (PW_L2 == null) {
                            PW_L2 = "";
                        }
                        updateCachedSettings(server, User_server, PW_server, User_CLLS, PW_CLLS, User_L2, PW_L2);
                        System.out.println("[INFO] Fast Mode removed: all safety monitors are always active.");

                        if (trueDeviceSheet == null) {
                            D.Error("Missing '" + TRUE_DEVICE_SHEET + "' sheet.");
                            return;
                        }
                        trueDeviceSheetName = trueDeviceSheet.getSheetName();
                        rebuildExcelCacheForSelection(workbook, deviceSelectMode, deviceRowStart, deviceRowEnd,
                                trueDeviceSheetName);

                        try {
                                final String pingTarget = Telnet_Multi.extractGatewayHost(server);
                                final int PING_INTERVAL_MS = 5000;   // ping - 5 
                                final int RETRY_INTERVAL_MS = 5000;  // retry - 5 

                                Thread pingThread = new Thread(() -> {
                                    final javax.swing.JDialog[] networkDialog = {null};

                                    final java.util.concurrent.atomic.AtomicBoolean networkLost = new java.util.concurrent.atomic.AtomicBoolean(false);

                                    while (shouldBackgroundWorkersRun()) {
                                        try {
                                            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                                            Process process = Runtime.getRuntime().exec("ping -n 1 " + pingTarget);
                                            int exitCode = process.waitFor();

                                            if (exitCode == 0) {
                                                StopProgram.updateNetworkStatus("[OK] Network online (" + timestamp + ")", new Color(0, 255, 0));

                                                if (networkLost.get()) {
                                                    System.out.println("[PING OK] Network restored at " + timestamp);
                                                    javax.swing.SwingUtilities.invokeLater(() -> {
                                                        //   dialog 
                                                        if (networkDialog[0] != null && networkDialog[0].isShowing()) {
                                                            networkDialog[0].dispose();
                                                            networkDialog[0] = null;
                                                        }

                                                        //   popup 
                                                        JOptionPane.showMessageDialog(
                                                                null,
                                                                " Network reconnected!\nResuming tasks...",
                                                                "Network Restored",
                                                                JOptionPane.INFORMATION_MESSAGE
                                                        );
                                                    });

                                                    synchronized (BotGetLog_TrueCorp.class) {
                                                        networkLost.set(false);
                                                        BotGetLog_TrueCorp.class.notifyAll();
                                                    }
                                                } else {
                                                    System.out.println("[PING OK] " + pingTarget + " reachable at " + timestamp);
                                                }
                                            } else {
                                                StopProgram.updateNetworkStatus("[X]Network disconnected! (" + timestamp + ")", Color.RED);

                                                if (!networkLost.get()) {
                                                    networkLost.set(true);
                                                    if (BotGetLog_TrueCorp.isAlarmEnabled()) {
                                                        Toolkit.getDefaultToolkit().beep();
                                                    }

                                                    System.out.println("[PING FAIL] " + pingTarget + " unreachable at " + timestamp);

//   ()  Ping 
                                                    new Thread(() -> {
                                                        try {
                                                            while (networkLost.get() && shouldBackgroundWorkersRun()) {
                                                                try {
                                                                    javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
                                                                    javax.sound.sampled.AudioFormat af
                                                                            = new javax.sound.sampled.AudioFormat(44100, 8, 1, true, false);
                                                                    int durationMs = 80;   //   ()
                                                                    int freq = 1200;       //   ( = )
                                                                    byte[] buf = new byte[44100 * durationMs / 1000];

                                                                    for (int n = 0; n < buf.length; n++) {
                                                                        double angle = n / (44100.0 / freq) * 2.0 * Math.PI;
                                                                        buf[n] = (byte) (Math.sin(angle) * 127);
                                                                    }

                                                                    clip.open(af, buf, 0, buf.length);
                                                                    if (BotGetLog_TrueCorp.isAlarmEnabled()) {
                                                                        clip.start();
                                                                    }

                                                                } catch (Exception e) {
                                                                    if (BotGetLog_TrueCorp.isAlarmEnabled()) {
                                                                        Toolkit.getDefaultToolkit().beep();
                                                                    }

                                                                }

                                                                Thread.sleep(100); //  - 0.1  ()
                                                            }
                                                        } catch (InterruptedException ignored) {
                                                        }
                                                    }).start();

                                                    javax.swing.SwingUtilities.invokeLater(() -> {
                                                        if (networkDialog[0] == null || !networkDialog[0].isShowing()) {
                                                            JOptionPane optionPane = new JOptionPane(
                                                                    " Network disconnected!\nPlease connect to the network to continue.",
                                                                    JOptionPane.WARNING_MESSAGE,
                                                                    JOptionPane.DEFAULT_OPTION);
                                                            JDialog dialog = optionPane.createDialog("Network Alert");
                                                            dialog.setModal(false);           //  
                                                            dialog.setAlwaysOnTop(true);      //  
                                                            dialog.setVisible(true);
                                                            networkDialog[0] = dialog;        //   network 
                                                        }
                                                    });
                                                }

                                                synchronized (BotGetLog_TrueCorp.class) {
                                                    while (networkLost.get() && shouldBackgroundWorkersRun()) {
                                                        try {
                                                            Thread.sleep(RETRY_INTERVAL_MS);
                                                            Process retry = Runtime.getRuntime().exec("ping -n 1 " + pingTarget);
                                                            int retryCode = retry.waitFor();
                                                            if (retryCode == 0) {
                                                                StopProgram.updateNetworkStatus(" NETWORK RESTORED (" + timestamp + ")", new Color(0, 255, 0));

                                                                networkLost.set(false);
                                                                BotGetLog_TrueCorp.class.notifyAll();
                                                                System.out.println("[NETWORK RESTORED] Resuming operations...");
                                                                if (BotGetLog_TrueCorp.isAlarmEnabled()) {
                                                                    Toolkit.getDefaultToolkit().beep();
                                                                }

                                                                javax.swing.SwingUtilities.invokeLater(() -> {
                                                                    //   dialog 
                                                                    if (networkDialog[0] != null && networkDialog[0].isShowing()) {
                                                                        networkDialog[0].dispose();
                                                                        networkDialog[0] = null;
                                                                    }
                                                                });

                                                                break;
                                                            }
                                                        } catch (Exception ignored) {
                                                        }
                                                    }
                                                }
                                            }

                                            Thread.sleep(PING_INTERVAL_MS);

                                        } catch (Exception e) {
                                            System.out.println("[PING ERROR] " + e.getMessage());
                                            try {

                                                if (BotGetLog_TrueCorp.isAlarmEnabled()) {
                                                    Toolkit.getDefaultToolkit().beep();
                                                }

                                                Thread.sleep(PING_INTERVAL_MS);
                                            } catch (InterruptedException ignored) {
                                            }
                                        }
                                    }
                                });

                                pingThread.setDaemon(true);
                                pingThread.start();
                            } catch (Exception e) {
                                System.out.println("[WARN] Unable to start ping thread: " + e.getMessage());
                        }

                    } else if (trueDeviceSheetName.equals(sheet.getSheetName())) {
                        List<Integer> indexRowList = new ArrayList<>();
                        for (Row row : sheet) {
                            try {
                                if (shouldRunDeviceRow(row, deviceSelectMode, deviceRowStart, deviceRowEnd)
                                        && !getCell(row, 2).isEmpty()
                                        && !getCell(row, 3).isEmpty()
                                        && Num_row <= row.getRowNum() + 1) {
                                    indexRowList.add(row.getRowNum());
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        // Build tasks by cmdSet order so every node in CmdSet-1
                        // finishes before CmdSet-2 starts, and so on.
                        int totalCmdSets = 0;
                        Map<Integer, List<NodeCommandTask>> cmdSetBatches = new LinkedHashMap<>();
                        for (int rowIdx : indexRowList) {
                            Row row = sheet.getRow(rowIdx);
                            if (!shouldRunDeviceRow(row, deviceSelectMode, deviceRowStart, deviceRowEnd)) {
                                continue;
                            }
                            String device = getCell(row, 2);
                            String loopback = getCell(row, 3);
                            for (int k = 4; k < row.getLastCellNum(); k++) {
                                String cmdSet = getCell(row, k).trim();
                                if (!cmdSet.isEmpty()) {
                                    totalCmdSets++;
                                    int cmdSetOrder = k - 3;
                                    cmdSetBatches
                                            .computeIfAbsent(cmdSetOrder, key -> new ArrayList<>())
                                            .add(new NodeCommandTask(rowIdx + 1, device, loopback, cmdSet, cmdSetOrder));
                                }
                            }
                        }

                        int totalNodes = totalCmdSets; //   cmdSet 
                        resetRunCounters();
                        realOut.printf("[INFO] Total commands to process: %d%n", totalNodes);

                        if (totalNodes == 0) {
                            realOut.println(" No command marked as Y in Excel.");
                            return;
                        }

                        if (totalNodes == 0) {
                            realOut.println(" No device marked as Y in Excel.");
                            return;
                        }
//   background monitor  (- Telnet)
                        Telnet_Multi.startWrongVendorMonitor(new PathFile());
                        Telnet_Multi.startCommandCompletionMonitor(new PathFile());
                        Telnet_Multi.startConnectionFailMonitor(new PathFile());

//   ThreadPool - TELNET_LIMIT - Telnet_Multi
                        int initialThreads = clampThreadPoolSize(configuredThreadPoolSize, totalNodes);
                        Telnet_Multi.configureNormalTelnetLimit(initialThreads);
                        realOut.printf("[INFO] Thread pool size: %d (configured=%d, max=%d)%n",
                                initialThreads, configuredThreadPoolSize, MAX_THREAD_POOL_SIZE);

                        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                                initialThreads, //  Core - permit 
                                initialThreads, //  Max - (fixed pool)
                                5L, TimeUnit.SECONDS, //  idle timeout
                                new LinkedBlockingQueue<>(100), //  
                                new ThreadPoolExecutor.CallerRunsPolicy()
                        );
                        exec.allowCoreThreadTimeOut(true); //   thread  idle

                        StopProgram.attachExecutor(exec);
                        BotGetLog_TrueCorp.attachExecutor(exec);

//  Real-time mode/thread/CPU monitor
                        //  Real-time mode/thread/CPU monitor (REAL-TIME ACTIVE COUNT)
                        Thread statusThread = new Thread(() -> {
                            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                            CpuSmooth cpuSmooth = new CpuSmooth(0.18, 25);
                            long lastMonitorTime = 0; //  

                            while (!exec.isShutdown()) {
                                try {
                                    double cpu = cpuSmooth.updateFromOs(os, true);
                                    int active = exec.getActiveCount();
                                    final int cpuMax = Telnet_Multi.getTurboTelnetLimit();
                                    int total = BotGetLog_TrueCorp.isFocusModeActive()
                                            ? cpuMax
                                            : Telnet_Multi.getNormalTelnetLimit();
                                    int queue = exec.getQueue().size();
                                    String mode = BotGetLog_TrueCorp.isFocusModeActive() ? "TURBO" : "NORMAL";

                                    //   60 -
                                    if (System.currentTimeMillis() - lastMonitorTime >= 60000) {
                                        realOut.printf("\n\rMode: %s | Threads: %d active / %d total | CPU: %.1f%%     ",
                                                mode, active, total, cpu);
                                        System.out.printf("[Monitor] Pool size=%d | TELNET_LIMIT=%d%n",
                                                exec.getPoolSize(), Telnet_Multi.TELNET_LIMIT.availablePermits());
                                        lastMonitorTime = System.currentTimeMillis(); // 
                                    }

                                    //  GUI  ()
                                    final int fActive = active;
                                    final int fTotal = total;
                                    final double fCpu = cpu;
                                    SwingUtilities.invokeLater(()
                                            -> StopProgram.updateThreadStatusFromBot(mode, fActive, fTotal, fCpu)
                                    );

                                    Thread.sleep(500); // loop - 0.5  - 60 -
                                } catch (InterruptedException e) {
                                    break;
                                } catch (Exception ignored) {
                                }
                            }
                        });

                        statusThread.setDaemon(true);
                        statusThread.start();

                        final int[] progress = {0};
                        commandBatchLoop:
                        for (Map.Entry<Integer, List<NodeCommandTask>> batchEntry : cmdSetBatches.entrySet()) {
                            if (isShutdownRequested()) {
                                break;
                            }
                            int cmdSetOrder = batchEntry.getKey();
                            List<NodeCommandTask> batchTasks = batchEntry.getValue();
                            List<Future<?>> batchFutures = new ArrayList<>();

                            realOut.printf("%n[INFO] Starting CmdSet-%d batch (%d nodes)%n",
                                    cmdSetOrder, batchTasks.size());

                            for (NodeCommandTask task : batchTasks) {
                                if (isShutdownRequested() || exec.isShutdown()) {
                                    break;
                                }

                                String Device = task.device;
                                String Loopback = task.loopback;
                                String cmdSet = task.cmdSet;
                                int rowNum = task.rowNum;
                                int rowIdx = rowNum - 1;
                                String firstCommand = getFirstCommandFromCmdSheet(workbook, cmdSet);
                                String lastCommand = getLastCommandFromCmdSheet(workbook, cmdSet);

                                boolean alreadyDone = false;
                                boolean alreadyDoneAsSuccess = false;
                                try {
                                    File folder = new File(FileInput.getLog());
                                    if (folder.exists() && folder.isDirectory()) {
                                        File[] logFiles = folder.listFiles((dir, name) -> name.endsWith(".txt"));
                                        if (logFiles != null) {
                                            List<File> matchedLogList = new ArrayList<File>();

                                            for (File f : logFiles) {
                                                String fileName = f.getName();

                                                //  -  Device/Loopback
                                                //  [row] + cmdSet
                                                if (fileName.startsWith("[" + rowNum + "]")
                                                        && isEquivalentCmdSet(cmdSet, extractCmdSetFromLogName(fileName))) {
                                                    matchedLogList.add(f);
                                                }
                                            }

                                            if (matchedLogList.size() > 0) {
                                                Collections.sort(matchedLogList, new Comparator<File>() {
                                                    @Override
                                                    public int compare(File f1, File f2) {
                                                        return compareLogPriority(f1, f2);
                                                    }
                                                });

                                                File latestLog = matchedLogList.get(0);
                                                int dupIndex = 1;
                                                while (dupIndex < matchedLogList.size()) {
                                                    File oldLog = matchedLogList.get(dupIndex);
                                                    if (Telnet_Multi.isProtectedLogFile(oldLog)) {
                                                        System.out.println("[DELETE-SKIP] Duplicate log is active/recent, keep for safety: " + oldLog.getName());
                                                        dupIndex++;
                                                        continue;
                                                    }
                                                    System.out.println("[DELETE] Duplicate old log deleted: " + oldLog.getName());
                                                    if (Telnet_Multi.moveLogToArchiveIfSafe(oldLog, "duplicate old log before batch")) {
                                                        System.out.println("[OK] Moved duplicate old file to Deleted_Log.");
                                                    } else {
                                                        System.out.println("[WARN] Failed to move duplicate old file: " + oldLog.getAbsolutePath());
                                                    }
                                                    dupIndex++;
                                                }

                                                boolean logComplete = isLogCompleteForLogFileCmdSet(latestLog, cmdSet);

                                                //  -
                                                if (logComplete) {
                                                    System.out.println("[OK] Log complete: " + latestLog.getName());
                                                    alreadyDone = true;
                                                    alreadyDoneAsSuccess = true;
                                                } else {
                                                    boolean incompleteConnectionFailure = hasConnectionFailureSignalInLog(latestLog);
                                                    if (Telnet_Multi.isProtectedLogFile(latestLog)) {
                                                        System.out.println("[DELETE-SKIP] Incomplete log is active/recent, do not delete or start duplicate task: " + latestLog.getName());
                                                        alreadyDone = true;
                                                    } else {
                                                        System.out.println("[DELETE] Incomplete log (missing last command or session end): " + latestLog.getName());
                                                        if (Telnet_Multi.moveLogToArchiveIfSafe(latestLog, "incomplete log before batch")) {
                                                            System.out.println("[OK] Moved incomplete log to Deleted_Log.");
                                                        } else {
                                                            System.out.println("[WARN] Failed to move: " + latestLog.getAbsolutePath());
                                                        }
                                                        alreadyDone = incompleteConnectionFailure;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    System.out.println("[WARN] Error checking Total_Log: " + e.getMessage());
                                }

                                if (alreadyDone) {
                                    System.out.printf("[SKIP] Skipped Row %d (%s) [%s] - already exists%n",
                                            rowNum, Device, cmdSet);
                                    if (alreadyDoneAsSuccess) {
                                        BotGetLog_TrueCorp.successCount.incrementAndGet();
                                    }

                                    //   Progress  Skipped Row
                                    synchronized (progress) {
                                        progress[0]++;
                                        double percent = (progress[0] * 100.0 / totalNodes);
                                        printProgress(percent, progress[0], totalNodes);
                                    }

                                    continue; //  cmdSet
                                }

                                final String fDev = Device;
                                final String fLoop = Loopback;
                                final String fCmd = cmdSet;
                                final int fRowNum = rowNum;
                                final PathFile fFile = FileInput;
                                final String fServer = server, fUsrS = User_server, fPwdS = PW_server;
                                final String fUsrC = User_CLLS, fPwdC = PW_CLLS;
                                final String fUsrL2 = User_L2, fPwdL2 = PW_L2;
                                final String fFirstCommand = firstCommand;
                                final String fLastCommand = lastCommand;

                                try {
                                    batchFutures.add(exec.submit(() -> {
                                    ACTIVE_TASKS.incrementAndGet();
                                    boolean telnetPermitAcquired = false;
                                    boolean countProgress = false;
                                    try {
                                        if (isShutdownRequested()) {
                                            realOut.printf("[STOP] Skip Row %d %s [%s] because shutdown was requested%n",
                                                    fRowNum, fDev, fCmd);
                                            BotGetLog_TrueCorp.recordStoppedTask();
                                            return;
                                        }

                                        //  - Telnet ( concurrent)
                                        Telnet_Multi.TELNET_LIMIT.acquire();
                                        telnetPermitAcquired = true;

                                        if (isShutdownRequested()) {
                                            realOut.printf("[STOP] Skip Row %d %s [%s] after TELNET permit because shutdown was requested%n",
                                                    fRowNum, fDev, fCmd);
                                            BotGetLog_TrueCorp.recordStoppedTask();
                                            return;
                                        }

                                        countProgress = true;
                                        String now = LocalDateTime.now()
                                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                                        realOut.printf("\n[RUN] Row %d  %s (%s) [%s]%n",
                                                fRowNum, fDev, fLoop, fCmd);
                                        logwork("[START] " + fRowNum + " " + fDev + " [" + fLoop + "] " + now + "\n",
                                                fFile.getLogWork());

                                        boolean taskSucceeded = false;
                                        boolean terminalFailureRecorded = false;
                                        int maxAttempts = (fFirstCommand.isEmpty() || fLastCommand.isEmpty())
                                                ? 1
                                                : (1 + MAX_RERUN_IF_LOG_INCOMPLETE);

                                        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                                            if (isShutdownRequested()) {
                                                BotGetLog_TrueCorp.recordStoppedTask();
                                                return;
                                            }

                                            Telnet_Multi telnetObj = new Telnet_Multi(
                                                    fServer, fUsrS, fPwdS,
                                                    fLoop, fUsrC, fPwdC,
                                                    fCmd, fDev, fRowNum,
                                                    fUsrL2, fPwdL2
                                            );
                                            telnetObj.disconnect();

                                            File completedLog = findLatestCompletedLog(fFile, fRowNum, fLoop, fDev, fCmd, fLastCommand);
                                            if (!telnetObj.hasSessionFailureRecorded() && completedLog != null) {
                                                logwork("[OK] " + fDev + " " + fLoop + "\n", fFile.getLogWork());
                                                BotGetLog_TrueCorp.successCount.incrementAndGet();
                                                taskSucceeded = true;
                                                break;
                                            }

                                            if (telnetObj.hasSessionFailureRecorded()) {
                                                terminalFailureRecorded = true;
                                                break;
                                            }

                                            if (fFirstCommand.isEmpty() || fLastCommand.isEmpty()) {
                                                BotGetLog_TrueCorp.recordCmdSetFailure();
                                                terminalFailureRecorded = true;
                                                realOut.printf("[WARN] Row %d %s [%s] cannot validate TRUE cmdSet boundary%n",
                                                        fRowNum, fDev, fCmd);
                                                break;
                                            }

                                            File latestLog = findLatestMatchingLog(fFile, fRowNum, fLoop, fCmd);
                                            if (latestLog != null && hasConnectionFailureSignalInLog(latestLog)) {
                                                BotGetLog_TrueCorp.recordNetworkFailure();
                                                terminalFailureRecorded = true;
                                                break;
                                            }

                                            if (attempt < maxAttempts) {
                                                realOut.printf("[RETRY] Row %d %s (%s) [%s] because TRUE command boundary was not complete (attempt %d/%d)%n",
                                                        fRowNum, fDev, fLoop, fCmd, attempt + 1, maxAttempts);
                                                archiveIncompleteMatchingLogs(fFile, fRowNum, fLoop, fDev, fCmd,
                                                        fFirstCommand, fLastCommand, "true command boundary incomplete before retry");
                                            }
                                        }

                                        if (!taskSucceeded && !terminalFailureRecorded && !isShutdownRequested()) {
                                            BotGetLog_TrueCorp.recordIncompleteFailure();
                                        }

                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        logwork("[CANCEL] " + fDev + " : interrupted while waiting for TELNET permit\n", fFile.getLogWork());
                                        realOut.printf("[CANCEL] %s interrupted while waiting for TELNET permit%n", fDev);
                                        BotGetLog_TrueCorp.recordStoppedTask();
                                    } catch (Exception e) {
                                        logwork("[ERROR] " + fDev + " : " + e + "\n", fFile.getLogWork());
                                        realOut.printf("[ERROR] %s %s%n", fDev, e);
                                        BotGetLog_TrueCorp.recordTaskFailure(e);
                                    } finally {
                                        //  - ()
                                        if (telnetPermitAcquired) {
                                            Telnet_Multi.TELNET_LIMIT.release();
                                        }

                                        ACTIVE_TASKS.decrementAndGet();
                                        if (countProgress) {
                                            synchronized (progress) {
                                                progress[0]++;
                                                double percent = (progress[0] * 100.0 / totalNodes);
                                                printProgress(percent, progress[0], totalNodes);
                                            }
                                        }

                                    }
                                    }));
                                } catch (RejectedExecutionException e) {
                                    if (isShutdownRequested() || exec.isShutdown()) {
                                        break;
                                    }
                                    throw e;
                                }
                            }

                            for (Future<?> f : batchFutures) {
                                try {
                                    f.get();
                                } catch (Exception ignored) {
                                }
                            }

                            if (isShutdownRequested()) {
                                break commandBatchLoop;
                            }

                            realOut.printf("[INFO] Completed CmdSet-%d batch%n", cmdSetOrder);
                        }

                        boolean stoppedByRequest = isShutdownRequested();
                        if (!exec.isShutdown()) {
                            realOut.println(" All tasks done, shutting down ThreadPool safely...");
                            exec.shutdown(); // -
                        }

                        boolean executorDrained = true;
                        try {
                            executorDrained = exec.awaitTermination(2, TimeUnit.HOURS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            executorDrained = false;
                        }

                        if (executorDrained) {
                            cleanDuplicateLogs(new File(FileInput.getLog()));
                        } else {
                            realOut.println("[WARN] Skip duplicate cleanup because some re-run/log tasks are still active.");
                        }
                        realOut.println("===================================================");
                        if (stoppedByRequest) {
                            realOut.printf("[STOP] Graceful stop completed at %s%n",
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                        } else {
                            realOut.printf("[OK] All devices completed at %s%n",
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                        }
                        realOut.println("===================================================");
                        //  --summary Option Handler
                        showSummary(FileInput, progress[0]);

                        if (stoppedByRequest) {
                            logwork("[STOP] Graceful stop completed.\n", FileInput.getLogWork());
                        } else {
                            logwork("[OK] All devices completed.\n", FileInput.getLogWork());
                            StopProgram.updateProgressFromBot(100, totalNodes, totalNodes);
                            stopBackgroundWorkers();
                            realOut.println("[INFO] Background workers stopped before success dialog.");
                            D.ShowSuccess("All Devices Completed");
                        }
                        //   log - Total_Log  rerun 
                    }
                }

            } catch (Exception ex) {
                realOut.println(" Exception: " + ex);
                logwork("[ERROR] " + ex + "\n", FileInput.getLogWork());
                RunBatch(FileInput.getCurrentFolder());
            } finally {
                if (workbook != null) try {
                    workbook.close();
                    realOut.println(" Workbook closed safely.");
                } catch (IOException ex) {
                    realOut.printf("[WARN] Error closing workbook: %s%n", ex.getMessage());
                }
            }
            System.exit(requestedExitCode);
        }).start();
    }

    //  Utility Functions
    private static synchronized void logwork(String logWork, String file) {
        String fileName = BOT_LOG_DAY_FORMAT.format(LocalDateTime.now()) + ".txt";
        String logPath = file + "\\" + fileName;
        synchronized (BOT_LOG_LOCK) {
            try {
                BufferedWriter log = getBotLogWriter(logPath);
                log.write(logWork);
                if (!logWork.endsWith("\n")) {
                    log.newLine();
                }
                flushBotLogIfNeeded(logPath, log, logWork);
            } catch (IOException ex) {
                realOut.println(ex);
            }
        }
    }

    static String getCellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            // Prefer the cached result from Excel to avoid expensive full-workbook
            // formula evaluation during startup/cache rebuild.
            return EXCEL_FORMATTER.formatCellValue(cell).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String getCell(Sheet sheet, int row, int col) {
        try {
            return getCellValue(sheet.getRow(row).getCell(col));
        } catch (Exception e) {
            return "";
        }
    }

    static String getCell(Row row, int col) {
        try {
            return getCellValue(row.getCell(col));
        } catch (Exception e) {
            return "";
        }
    }

    private static void printProgress(double percent, int done, int total) {
        int barLength = 40;
        int filled = (int) (percent / (100.0 / barLength));
        StringBuilder barBuilder = new StringBuilder();
        for (int i = 0; i < filled; i++) {
            barBuilder.append("=");
        }
        for (int i = filled; i < barLength; i++) {
            barBuilder.append("-");
        }
        String bar = barBuilder.toString();
        long now = System.currentTimeMillis();
        boolean shouldPrint = done >= total
                || done <= 1
                || now - lastProgressConsoleUpdateMs >= PROGRESS_PRINT_INTERVAL_MS;
        if (shouldPrint) {
            synchronized (realOut) {
                String msg = String.format("\rProgress: [%s] %.1f%% (%d/%d)", bar, percent, done, total);
                realOut.print(msg);
                if (done >= total) {
                    realOut.println();
                }
                realOut.flush();
            }
            lastProgressConsoleUpdateMs = now;
        }
        StopProgram.updateProgressFromBot(percent, done, total);
        StopProgram.updateTimeEstimate(total, done, getExecutor() != null ? getExecutor().getActiveCount() : 1);

    }

    private static long extractLogDateScore(File file) {
        if (file == null) {
            return Long.MIN_VALUE;
        }
        return extractLogDateScore(file.getName(), file.lastModified());
    }

    private static long extractLogDateScore(String fileName, long fallback) {
        if (fileName == null) {
            return fallback;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("_(\\d{4}-\\d{2}-\\d{2})(?:\\.txt)?$")
                    .matcher(fileName);
            if (m.find()) {
                java.time.LocalDate d = java.time.LocalDate.parse(m.group(1));
                return d.toEpochDay();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static boolean hasValidDuplicateLogHead(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        return isValidLogHead(file, extractDeviceFromLogName(file.getName()), extractCmdSetFromLogName(file.getName()));
    }

    private static int compareLogPriority(File f1, File f2) {
        int validCmp = Boolean.compare(hasValidDuplicateLogHead(f2), hasValidDuplicateLogHead(f1));
        if (validCmp != 0) {
            return validCmp;
        }

        int sizeCmp = Long.compare(f2.length(), f1.length());
        if (sizeCmp != 0) {
            return sizeCmp;
        }

        long s1 = extractLogDateScore(f1);
        long s2 = extractLogDateScore(f2);
        int cmp = Long.compare(s2, s1);
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(f2.lastModified(), f1.lastModified());
    }

    private static void cleanDuplicateLogs(File logDir) {
        if (logDir == null || !logDir.exists() || !logDir.isDirectory()) {
            return;
        }
        File[] files = logDir.listFiles((dir, name) -> name.matches("\\[\\d+\\].*\\.txt"));
        if (files == null || files.length == 0) {
            return;
        }

        Map<String, List<File>> grouped = new HashMap<>();
        for (File f : files) {
            try {
                String name = f.getName();
                int rb = name.indexOf("]");
                int lastUs = name.lastIndexOf("_");
                if (!name.startsWith("[") || rb < 0 || lastUs < 0) {
                    continue;
                }

                String rowPrefix = name.substring(0, rb + 1);

                //  -:  _-  key 
                String bodyWithoutDate = name.substring(rb + 1, lastUs);
                String groupKey = rowPrefix + "|" + bodyWithoutDate;

                grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(f);
            } catch (Exception ignored) {
            }
        }

        int kept = 0;
        for (Map.Entry<String, List<File>> entry : grouped.entrySet()) {
            List<File> sameGroup = entry.getValue();
            if (sameGroup == null || sameGroup.isEmpty()) {
                continue;
            }

            Collections.sort(sameGroup, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return compareLogPriority(f1, f2);
                }
            });

            kept++;

            for (int i = 1; i < sameGroup.size(); i++) {
                File old = sameGroup.get(i);
                if (Telnet_Multi.isProtectedLogFile(old)) {
                    realOut.printf("[DELETE-SKIP] Duplicate log is active/recent, keep for safety: %s%n", old.getName());
                    continue;
                }
                if (Telnet_Multi.moveLogToArchiveIfSafe(old, "final duplicate cleanup")) {
                    realOut.printf("- Moved duplicate old file to Deleted_Log: %s%n", old.getName());
                } else {
                    realOut.printf("[WARN] Failed to move duplicate old file: %s%n", old.getAbsolutePath());
                }
            }
        }
        realOut.printf("[OK] Duplicate cleanup completed (%d latest logs kept)%n", kept);
    }

    private static volatile boolean restarting = false;
    private static volatile boolean shutdownRequested = false;
    private static volatile boolean shutdownWatcherStarted = false;
    private static volatile int requestedExitCode = 0;

    public static synchronized void RunBatch(String currentFolder) {
        try {
            if (restarting) {
                return;
            }
            restarting = true;
            List<String> command = AppMetadata.buildRestartCommand();
            realOut.printf("[INFO] Restarting via: %s%n", command);
            shutdownCurrentExecutorNow("Restarting BotGetLog_TrueCorp");
            new ProcessBuilder(command)
                    .directory(new File(currentFolder))
                    .start();
            realOut.println(" BotGetLog_TrueCorp restarted.");
            System.exit(0);
        } catch (IOException ex) {
            Logger.getLogger(BotGetLog_TrueCorp.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    // ------------------------------------------------------------
    //  Turbo Mode + GUI Integration
    // ------------------------------------------------------------
    private static volatile boolean focusModeSwitch = false;
    private static ThreadPoolExecutor currentExecutorRef;

    public static synchronized void setFocusMode(boolean mode) {
        focusModeSwitch = mode;
        Telnet_Multi.updateTelnetLimit(mode);
        System.out.println(mode ? "[INFO] Turbo Mode Enabled" : "[INFO] Normal Mode Enabled");

        ThreadPoolExecutor execNow = BotGetLog_TrueCorp.getExecutor();
        if (execNow == null) {
            System.err.println(" Executor not initialized yet.");
            return;
        }
        if (execNow.isShutdown() || execNow.isTerminated()) {
            System.err.println(" ThreadPool already shut down, skip resize.");
            return;
        }

        int newLimit = Telnet_Multi.getCurrentTelnetLimit();
        int currentCore = execNow.getCorePoolSize();
        int currentMax = execNow.getMaximumPoolSize();

        try {
            if (newLimit > currentMax) {
                execNow.setMaximumPoolSize(newLimit);
            }
            if (newLimit != currentCore) {
                execNow.setCorePoolSize(newLimit);
            }
            if (newLimit < execNow.getMaximumPoolSize()) {
                execNow.setMaximumPoolSize(newLimit);
            }
            execNow.allowCoreThreadTimeOut(!mode);
            if (!mode) {
                execNow.purge();
            }

            System.out.printf("[INFO] Adjusted ThreadPool: core=%d -> %d | max=%d -> %d%n",
                    currentCore, execNow.getCorePoolSize(), currentMax, execNow.getMaximumPoolSize());
        } catch (IllegalArgumentException e) {
            System.err.printf("[WARN] ThreadPool resize failed: %s%n", e.getMessage());
        } catch (Exception e) {
            System.err.println(" ThreadPool resize error: " + e.getMessage());
        }

    }

    public static synchronized boolean isFocusModeActive() {
        return focusModeSwitch;
    }

    public static synchronized void attachExecutor(ThreadPoolExecutor exec) {
        currentExecutorRef = exec;
    }

    public static synchronized ThreadPoolExecutor getExecutor() {
        return currentExecutorRef;
    }

    private static void shutdownCurrentExecutorNow(String reason) {
        if (reason != null && !reason.trim().isEmpty()) {
            realOut.println("[INFO] Immediate shutdown requested: " + reason);
        }

        ThreadPoolExecutor exec = getExecutor();
        if (exec == null || exec.isTerminated()) {
            return;
        }

        List<Runnable> queuedTasks = exec.shutdownNow();
        if (!queuedTasks.isEmpty()) {
            realOut.printf("[INFO] Canceled %d queued task(s).%n", queuedTasks.size());
        }

        try {
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                realOut.println("[WARN] Some running tasks did not stop before exit.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            realOut.println("[WARN] Interrupted while waiting for shutdown.");
        }
    }

    public static boolean isShutdownRequested() {
        return shutdownRequested;
    }

    private static long getGracefulShutdownTimeoutMs() {
        String configured = System.getProperty("botgetlog.shutdown.timeout.ms", "").trim();
        if (!configured.isEmpty()) {
            try {
                long timeoutMs = Long.parseLong(configured);
                if (timeoutMs > 0L) {
                    return timeoutMs;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MS;
    }

    private static void startGracefulShutdownWatchdog(ThreadPoolExecutor exec) {
        if (shutdownWatcherStarted || exec == null) {
            return;
        }
        shutdownWatcherStarted = true;

        Thread watchdog = new Thread(() -> {
            long timeoutMs = getGracefulShutdownTimeoutMs();
            try {
                if (!exec.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                    realOut.printf("[WARN] Graceful shutdown timed out after %d seconds; forcing remaining tasks.%n",
                            timeoutMs / 1000L);
                    shutdownCurrentExecutorNow("Graceful shutdown timeout");
                    System.exit(requestedExitCode);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "graceful-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public static synchronized void requestShutdown(String reason, int exitCode) {
        shutdownRequested = true;
        requestedExitCode = exitCode;
        stopBackgroundWorkers();

        if (reason != null && !reason.trim().isEmpty()) {
            realOut.println("[INFO] Graceful shutdown requested: " + reason);
        }

        ThreadPoolExecutor exec = getExecutor();
        if (exec == null) {
            System.exit(exitCode);
            return;
        }

        exec.shutdown();
        startGracefulShutdownWatchdog(exec);
    }

    public static synchronized void requestImmediateShutdown(String reason, int exitCode) {
        shutdownRequested = true;
        requestedExitCode = exitCode;
        stopBackgroundWorkers();

        if (reason != null && !reason.trim().isEmpty()) {
            realOut.println("[INFO] Manual stop requested: " + reason);
        }

        shutdownCurrentExecutorNow(reason);
        System.exit(exitCode);
    }

    public static synchronized int getCurrentThreadCount() {
        return (currentExecutorRef != null) ? currentExecutorRef.getPoolSize() : 0;
    }

    public static synchronized int getActiveThreadCount() {
        return ACTIVE_TASKS.get();
    }

    //   Summary  ( % )
//   Summary  ( % )   Unique + --

    private static void showSummary(PathFile FileInput, int totalProcessed) {
        try {
            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            File wrongVendorFile = new File(FileInput.getLogWork(), "Node_WrongVendor_" + today + ".txt");
            File connFailFile = new File(FileInput.getLogWork(), "Node_ConnectionFailed_" + today + ".txt");

            //   Unique -
            int wrongVendorCount = countLinesUnique(wrongVendorFile);
            int connFailCount = countLinesUnique(connFailFile);

            //  Success = ---
            int successCount = BotGetLog_TrueCorp.successCount.get();
            double successPercent = totalProcessed > 0
                    ? (successCount * 100.0 / totalProcessed)
                    : 0.0;

            System.out.println("\n========== SUMMARY ==========");
            System.out.printf("[OK] Success: %d nodes (%.2f%%)%n", successCount, successPercent);
            System.out.printf("[WARN] Wrong vendor: %d nodes%n", wrongVendorCount);
            System.out.printf("[FAIL] Connection failed: %d nodes%n", connFailCount);
            System.out.printf("[INFO] Total processed: %d nodes%n", totalProcessed);
            System.out.println("=============================\n");

            //  log 
            logwork(String.format(
                    "[SUMMARY] Success=%d (%.2f%%), WrongVendor=%d, ConnectionFailed=%d, Total=%d%n",
                    successCount, successPercent, wrongVendorCount, connFailCount, totalProcessed),
                    FileInput.getLogWork());

        } catch (Exception e) {
            System.out.println("[WARN] Failed to generate summary: " + e);
        }
    }

//  Summary by counting files in log directory (actual run count)
    private static void showSummary(PathFile FileInput) {
        try {
            String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            File logDir = new File(FileInput.getLog());
            File wrongVendorFile = new File(FileInput.getLogWork(), "Node_WrongVendor_" + today + ".txt");
            File connFailFile = new File(FileInput.getLogWork(), "Node_ConnectionFailed_" + today + ".txt");

            int totalProcessed = 0;
            if (logDir.exists() && logDir.isDirectory()) {
                File[] logFiles = logDir.listFiles((dir, name) -> name.matches("\\[\\d+\\].*\\.txt"));
                if (logFiles != null) {
                    totalProcessed = logFiles.length;
                }
            }

            int wrongVendorCount = countLinesUnique(wrongVendorFile);
            int connFailCount = countLinesUnique(connFailFile);

            System.out.println("\n========== SUMMARY ==========");
            System.out.printf("[WARN] Wrong vendor: %d nodes%n", wrongVendorCount);
            System.out.printf("[FAIL] Connection failed: %d nodes%n", connFailCount);
            System.out.printf("[INFO] Total processed: %d nodes%n", totalProcessed);
            System.out.println("=============================\n");

            logwork(String.format("[SUMMARY] WrongVendor=%d, ConnectionFailed=%d, Total=%d%n",
                    wrongVendorCount, connFailCount, totalProcessed),
                    FileInput.getLogWork());

        } catch (Exception e) {
            System.out.println("[WARN] Failed to generate summary: " + e);
        }
    }

//   node  unique (Device+IP)  summary
//   node  unique - (Device + IP) 
    private static int countLinesUnique(File file) {
        if (!file.exists()) {
            return 0;
        }

        Set<String> uniqueKeys = new HashSet<>();
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^\\[\\d+\\],(\\d{4}-\\d{2}-\\d{2})\\s[0-9:]+,([^,]+),([^,]+),"
        );

        try ( BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                java.util.regex.Matcher m = pattern.matcher(line);
                if (m.find()) {
                    String date = m.group(1).trim();
                    String device = m.group(2).trim();
                    String ip = m.group(3).trim();
                    if (date.equals(today)) {
                        String key = device + "_" + ip;
                        uniqueKeys.add(key);
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[WARN] countLinesUnique: " + e);
        }

        //  Debug summary
        System.out.printf("[SUMMARY] %s  %d unique nodes found for %s%n",
                file.getName(), uniqueKeys.size(), today);

        return uniqueKeys.size();
    }

//  Helper: -- summary
    private static int countLines(File file) {
        if (!file.exists()) {
            return 0;
        }
        try ( BufferedReader br = new BufferedReader(new FileReader(file))) {
            int count = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("[") && line.contains(",")) {
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

//  log static  static method
    private static synchronized void logworkStatic(String text) {
        try {
            PathFile fileInput = new PathFile();
            String fileName = BOT_LOG_DAY_FORMAT.format(LocalDateTime.now()) + ".txt";
            String logPath = fileInput.getLogWork() + "\\" + fileName;
            synchronized (BOT_LOG_LOCK) {
                BufferedWriter log = getBotLogWriter(logPath);
                log.write(text);
                if (!text.endsWith("\n")) {
                    log.newLine();
                }
                flushBotLogIfNeeded(logPath, log, text);
            }
        } catch (Exception e) {
            System.out.println("[WARN] logworkStatic failed: " + e);
        }
    }

    private static String getLastCommandFromCmdSheet(Workbook workbook, String cmdSet) {
        String cached = getCachedLastCommand(cmdSet);
        if (!cached.isEmpty()) {
            return cached;
        }
        try {
            Sheet cmdSheet = workbook.getSheet(CMDSET_SHEET);
            if (cmdSheet == null || cmdSheet.getRow(0) == null) {
                return "";
            }

            for (int j = 0; j < cmdSheet.getRow(0).getLastCellNum(); j++) {
                if (cmdSheet.getRow(0).getCell(j) == null) {
                    continue;
                }

                String header = getCellValue(cmdSheet.getRow(0).getCell(j));
                if (!cmdSet.equalsIgnoreCase(header)) {
                    continue;
                }

                for (int r = cmdSheet.getLastRowNum(); r >= 0; r--) {
                    Row cmdRow = cmdSheet.getRow(r);
                    if (cmdRow != null && cmdRow.getCell(j) != null) {
                        String value = getCellValue(cmdRow.getCell(j));
                        if (!value.isEmpty()) {
                            return value;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read last command for " + cmdSet + ": " + e.getMessage());
        }
        return "";
    }

    private static String getFirstCommandFromCmdSheet(Workbook workbook, String cmdSet) {
        String cached = getCachedFirstCommand(cmdSet);
        if (!cached.isEmpty()) {
            return cached;
        }
        try {
            Sheet cmdSheet = workbook.getSheet(CMDSET_SHEET);
            if (cmdSheet == null || cmdSheet.getRow(0) == null) {
                return "";
            }

            for (int j = 0; j < cmdSheet.getRow(0).getLastCellNum(); j++) {
                if (cmdSheet.getRow(0).getCell(j) == null) {
                    continue;
                }

                String header = getCellValue(cmdSheet.getRow(0).getCell(j));
                if (!cmdSet.equalsIgnoreCase(header)) {
                    continue;
                }

                for (int r = 1; r <= cmdSheet.getLastRowNum(); r++) {
                    Row cmdRow = cmdSheet.getRow(r);
                    if (cmdRow != null && cmdRow.getCell(j) != null) {
                        String value = getCellValue(cmdRow.getCell(j));
                        if (!value.isEmpty()) {
                            return value;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read first command for " + cmdSet + ": " + e.getMessage());
        }
        return "";
    }

    public static boolean isLogCompleteForCmdSet(File logFile, String cmdSet) {
        String safeCmdSet = cmdSet == null || cmdSet.trim().isEmpty()
                ? extractCmdSetFromLogName(logFile == null ? "" : logFile.getName())
                : cmdSet.trim();
        String firstCommand = getCachedFirstCommand(safeCmdSet);
        String lastCommand = getCachedLastCommand(safeCmdSet);
        if (firstCommand.isEmpty() || lastCommand.isEmpty()) {
            try {
                PathFile fileInput = new PathFile();
                File excelFile = new File(fileInput.getUserInterface_Input());
                if (excelFile.exists()) {
                    try (Workbook workbook = WorkbookFactory.create(excelFile)) {
                        if (firstCommand.isEmpty()) {
                            firstCommand = getFirstCommandFromCmdSheet(workbook, safeCmdSet);
                        }
                        if (lastCommand.isEmpty()) {
                            lastCommand = getLastCommandFromCmdSheet(workbook, safeCmdSet);
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[WARN] Cannot load cmdSet boundary for " + safeCmdSet + ": " + e.getMessage());
            }
        }
        return isLogComplete(logFile, firstCommand, lastCommand, safeCmdSet);
    }

    private static boolean isLogCompleteForLogFileCmdSet(File logFile, String fallbackCmdSet) {
        String fileCmdSet = extractCmdSetFromLogName(logFile == null ? "" : logFile.getName());
        String validationCmdSet = fileCmdSet == null || fileCmdSet.trim().isEmpty()
                ? fallbackCmdSet
                : fileCmdSet;
        return isLogCompleteForCmdSet(logFile, validationCmdSet);
    }

    private static boolean isLogComplete(File logFile, String firstCommand, String lastCommand, String cmdSet) {
        if (logFile == null || !logFile.exists()) {
            return false;
        }
        if (Telnet_Multi.hasWrongVendorSignal(logFile)) {
            return false;
        }

        String safeCmdSet = cmdSet == null || cmdSet.trim().isEmpty()
                ? extractCmdSetFromLogName(logFile.getName())
                : cmdSet.trim();
        String safeFirstCommand = firstCommand == null ? "" : firstCommand.trim();
        String safeLastCommand = lastCommand == null ? "" : lastCommand.trim();

        if (safeFirstCommand.isEmpty() || safeLastCommand.isEmpty()) {
            return false;
        }

        if (!isValidLogHead(logFile, extractDeviceFromLogName(logFile.getName()), safeCmdSet)) {
            return false;
        }

        return containsPromptPlusFirstCommand(logFile, safeFirstCommand)
                && containsPromptPlusLastCommand(logFile, safeLastCommand);
    }

    private static boolean containsPromptPlusFirstCommand(File logFile, String firstCommand) {
        if (logFile == null || firstCommand == null || firstCommand.trim().isEmpty()) {
            return false;
        }

        try {
            String headContent = readUtf8Head(logFile, FIRST_COMMAND_HEAD_BYTES_TO_SCAN);
            if (headContent.isEmpty()) {
                return false;
            }

            String[] rawLines = headContent.split("\r?\n");
            int endIndex = Math.min(rawLines.length, FIRST_COMMAND_HEAD_LINES_TO_SCAN);
            for (int i = 0; i < endIndex; i++) {
                String line = sanitizeLogLine(rawLines[i]);
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase(firstCommand.trim())
                        || matchesPromptPlusCommandLine(line, firstCommand)) {
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read log for first-command check: "
                    + logFile.getName() + " -> " + e.getMessage());
        }
        return false;
    }

    private static boolean containsPromptPlusLastCommand(File logFile, String lastCommand) {
        if (logFile == null || lastCommand == null || lastCommand.trim().isEmpty()) {
            return false;
        }

        try {
            String tailContent = readUtf8Tail(logFile, LAST_COMMAND_TAIL_BYTES_TO_SCAN);
            if (tailContent.isEmpty()) {
                return false;
            }

            String[] rawLines = tailContent.split("\r?\n");
            int startIndex = Math.max(0, rawLines.length - LAST_COMMAND_TAIL_LINES_TO_SCAN);
            List<String> tailLines = new ArrayList<>();
            for (int i = startIndex; i < rawLines.length; i++) {
                tailLines.add(sanitizeLogLine(rawLines[i]));
            }

            for (int i = tailLines.size() - 1; i >= 0; i--) {
                String line = tailLines.get(i);
                if (line.isEmpty()) {
                    continue;
                }
                if (matchesPromptPlusCommandLine(line, lastCommand)
                        || matchesCommandCompletionFallback(tailLines, i, lastCommand)) {
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Cannot read log for last-command check: "
                    + logFile.getName() + " -> " + e.getMessage());
        }
        return false;
    }

    private static String readUtf8Tail(File logFile, int tailBytesToScan) throws IOException {
        if (logFile == null || !logFile.isFile()) {
            return "";
        }

        long fileLength = logFile.length();
        if (fileLength <= 0) {
            return "";
        }

        int bytesToRead = (int) Math.min(fileLength, Math.max(1, tailBytesToScan));
        byte[] buffer = new byte[bytesToRead];
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long startPos = Math.max(0L, fileLength - bytesToRead);
            raf.seek(startPos);
            raf.readFully(buffer);

            String tail = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
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
        if (logFile == null || !logFile.isFile()) {
            return "";
        }

        long fileLength = logFile.length();
        if (fileLength <= 0) {
            return "";
        }

        int bytesToRead = (int) Math.min(fileLength, Math.max(1, headBytesToScan));
        byte[] buffer = new byte[bytesToRead];
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            raf.seek(0);
            raf.readFully(buffer);
            return new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String sanitizeLogLine(String line) {
        if (line == null) {
            return "";
        }
        return line
                .replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "")
                .replace("\r", "")
                .trim();
    }

    private static boolean matchesPromptPlusCommandLine(String line, String command) {
        if (line == null || command == null) {
            return false;
        }

        String cmd = command.trim();
        if (cmd.isEmpty()) {
            return false;
        }

        String quotedCmd = Pattern.quote(cmd);
        return line.matches("^<[^\\r\\n>]+>\\s*" + quotedCmd + "\\s*$")
                || line.matches("^\\[(?:~|\\*)?[^\\r\\n\\]]+(?:-[^\\]]+)?\\]\\s*" + quotedCmd + "\\s*$")
                || line.matches("^[A-Za-z]:[^\\r\\n#>]+#\\s*" + quotedCmd + "\\s*$")
                || line.matches("^[^\\r\\n#>]+[>#]\\s*" + quotedCmd + "\\s*$")
                || line.matches("^[A-Za-z0-9._:-]+[>#]\\s*" + quotedCmd + "\\s*$")
                || line.equalsIgnoreCase(cmd);
    }

    private static boolean matchesCommandCompletionFallback(List<String> tailLines, int commandLineIndex, String lastCommand) {
        if (tailLines == null || lastCommand == null) {
            return false;
        }
        if (commandLineIndex < 0 || commandLineIndex >= tailLines.size()) {
            return false;
        }

        String cmd = lastCommand.trim();
        if (cmd.isEmpty()) {
            return false;
        }

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

        int end = Math.min(tailLines.size(), commandLineIndex + 8);
        for (int i = commandLineIndex + 1; i < end; i++) {
            String next = tailLines.get(i);
            if (next == null || next.isEmpty()) {
                continue;
            }

            String lower = next.toLowerCase(Locale.ROOT);
            if (lower.contains("channel closed while waiting for cmd: " + normalizedCmd)
                    || lower.contains("channel closed")
                    || lower.contains("connection closed")
                    || lower.contains("foreign host")
                    || lower.contains("logout")
                    || lower.contains("script done")
                    || lower.contains("enter ip address")) {
                return true;
            }
        }

        return false;
    }


    private static String readLogHead(File logFile, int maxLines) {
        if (logFile == null || !logFile.exists()) {
            return "";
        }
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < maxLines) {
                String trimmed = line == null ? "" : line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
                count++;
            }
        } catch (Exception e) {
            System.out.println("[WARN] Error reading log head: " + logFile.getName() + " -> " + e.getMessage());
        }
        return "";
    }

    private static boolean isValidLogHead(File logFile, String device, String cmdSet) {
        String head = readLogHead(logFile, 20);
        if (head.isEmpty()) {
            return false;
        }

        String safeHead = head.toLowerCase();
        String safeDevice = device == null ? "" : device.trim().toLowerCase();
        String safeCmdSet = cmdSet == null ? "" : cmdSet.trim().toLowerCase();

        if (safeHead.contains("enter ip address") || safeHead.contains("connection closed") || safeHead.contains("script done")) {
            return false;
        }

        if (safeCmdSet.startsWith("zte-")) {
            String promptDevice = extractHashPromptDevice(head);
            String expectedDevice = normalizeDeviceForLogComparison(device);
            String actualDevice = normalizeDeviceForLogComparison(promptDevice);
            return safeHead.contains("#")
                    && safeHead.contains("terminal length 0")
                    && (expectedDevice.isEmpty() || expectedDevice.equals(actualDevice));
        }
        if (safeCmdSet.startsWith("n-")) {
            String promptDevice = extractNokiaPromptDevice(head);
            String expectedDevice = normalizeDeviceForLogComparison(device);
            String actualDevice = normalizeDeviceForLogComparison(promptDevice);
            return safeHead.startsWith("a:")
                    && safeHead.contains("#")
                    && safeHead.contains("environment no more")
                    && (expectedDevice.isEmpty() || expectedDevice.equals(actualDevice));
        }
        if (safeCmdSet.startsWith("hw-")) {
            String promptDevice = extractAnglePromptDevice(head);
            String expectedDevice = normalizeDeviceForLogComparison(device);
            String actualDevice = normalizeDeviceForLogComparison(promptDevice);
            return safeHead.startsWith("<")
                    && safeHead.contains(">")
                    && safeHead.contains("screen-length 0")
                    && (expectedDevice.isEmpty() || expectedDevice.equals(actualDevice));
        }

        return (safeHead.contains(safeDevice) && (safeHead.contains("#") || safeHead.contains(">")));
    }

    private static String extractNokiaPromptDevice(String line) {
        if (line == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("^[A-Za-z]:([^#\\r\\n]+)#").matcher(line.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String extractAnglePromptDevice(String line) {
        if (line == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("^<([^>\\r\\n]+)>").matcher(line.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String extractHashPromptDevice(String line) {
        if (line == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("^([^#>\\r\\n]+)#").matcher(line.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String normalizeDeviceForLogComparison(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replaceAll("\\s+", "");
    }

    private static boolean isLogComplete(File logFile, String lastCommand) {
        String cmdSet = extractCmdSetFromLogName(logFile == null ? "" : logFile.getName());
        String firstCommand = getCachedFirstCommand(cmdSet);
        return isLogComplete(logFile, firstCommand, lastCommand, cmdSet);
    }

    private static boolean hasConnectionFailureSignalInLog(File logFile) {
        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long fileLength = raf.length();
            long seekPos = Math.max(0, fileLength - 4096);
            raf.seek(seekPos);

            byte[] buf = new byte[(int) (fileLength - seekPos)];
            raf.readFully(buf);
            String tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
            return containsConnectionFailureSignal(tail);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean containsConnectionFailureSignal(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("connection failed")
                || lower.contains("connection timed out")
                || lower.contains("unable to connect")
                || lower.contains("cannot connect")
                || lower.contains("connection refused")
                || lower.contains("sam-bb connection to node timed out")
                || lower.contains("no response after password")
                || lower.contains("telnet read timed out")
                || lower.contains("connectexception")
                || lower.contains("[error] telnet");
    }

    private static String normalizeCmdSetFamily(String cmdSet) {
        if (cmdSet == null) {
            return "";
        }
        String value = cmdSet.trim();
        int dash = value.indexOf('-');
        return (dash >= 0 && dash < value.length() - 1)
                ? value.substring(dash + 1).trim().toLowerCase()
                : value.toLowerCase();
    }

    private static boolean isEquivalentCmdSet(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizeCmdSetFamily(left).equals(normalizeCmdSetFamily(right));
    }


    private static String extractCmdSetFromLogName(String fileName) {
        try {
            Matcher matcher = Pattern.compile("\\[(\\d+)](.*?)_(.*?)_(.*?)_(\\d{4}-\\d{2}-\\d{2})(?:_deleted_.*)?\\.txt").matcher(fileName);
            if (matcher.find()) {
                return matcher.group(4);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String extractDeviceFromLogName(String fileName) {
        try {
            Matcher matcher = Pattern.compile("\\[(\\d+)](.*?)_(.*?)_(.*?)_(\\d{4}-\\d{2}-\\d{2})(?:_deleted_.*)?\\.txt").matcher(fileName);
            if (matcher.find()) {
                return matcher.group(3);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static File findLatestCompletedLog(PathFile fileInput, int rowNum, String loopback, String device, String cmdSet, String lastCommand) {
        try {
            File logDir = new File(fileInput.getLog());
            if (!logDir.exists() || !logDir.isDirectory()) {
                return null;
            }

            File[] matched = logDir.listFiles((dir, name)
                    -> name != null
                    && name.startsWith("[" + rowNum + "]")
                    && name.contains(loopback + "_")
                    && name.endsWith(".txt"));

            if (matched == null || matched.length == 0) {
                return null;
            }

            Arrays.sort(matched, BotGetLog_TrueCorp::compareLogPriority);
            for (File file : matched) {
                String fileCmdSet = extractCmdSetFromLogName(file.getName());

                if (!isEquivalentCmdSet(cmdSet, fileCmdSet)) {
                    continue;
                }

                if (isLogCompleteForLogFileCmdSet(file, cmdSet)) {
                    return file;
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Unable to find completed log: " + e.getMessage());
        }
        return null;
    }

    private static File findLatestMatchingLog(PathFile fileInput, int rowNum, String loopback, String cmdSet) {
        try {
            File logDir = new File(fileInput.getLog());
            if (!logDir.exists() || !logDir.isDirectory()) {
                return null;
            }

            File[] matched = logDir.listFiles((dir, name)
                    -> name != null
                    && name.startsWith("[" + rowNum + "]")
                    && name.contains(loopback + "_")
                    && name.endsWith(".txt"));

            if (matched == null || matched.length == 0) {
                return null;
            }

            Arrays.sort(matched, BotGetLog_TrueCorp::compareLogPriority);
            for (File file : matched) {
                if (isEquivalentCmdSet(cmdSet, extractCmdSetFromLogName(file.getName()))) {
                    return file;
                }
            }
        } catch (Exception e) {
            System.out.println("[WARN] Unable to find latest log: " + e.getMessage());
        }
        return null;
    }

    private static void archiveIncompleteMatchingLogs(PathFile fileInput, int rowNum, String loopback,
            String device, String cmdSet, String firstCommand, String lastCommand, String reason) {
        try {
            File logDir = new File(fileInput.getLog());
            if (!logDir.exists() || !logDir.isDirectory()) {
                return;
            }

            File[] matched = logDir.listFiles((dir, name)
                    -> name != null
                    && name.startsWith("[" + rowNum + "]")
                    && name.contains(loopback + "_")
                    && name.endsWith(".txt"));

            if (matched == null) {
                return;
            }

            for (File file : matched) {
                String fileCmdSet = extractCmdSetFromLogName(file.getName());
                if (!isEquivalentCmdSet(cmdSet, fileCmdSet)) {
                    continue;
                }
                if (isLogCompleteForLogFileCmdSet(file, cmdSet)) {
                    continue;
                }
                if (Telnet_Multi.isProtectedLogFile(file)) {
                    System.out.println("[DELETE-SKIP] Incomplete TRUE log is active/recent, keep for safety: " + file.getName());
                    continue;
                }
                Telnet_Multi.moveLogToArchiveIfInactive(file, reason);
            }
        } catch (Exception e) {
            System.out.println("[WARN] Unable to archive incomplete logs: " + e.getMessage());
        }
    }

//   node  monitor ()
    public static void RerunNode(int rowNum, String loopback, String device, String cmdSet) {
        try {
            if (isShutdownRequested()) {
                System.out.printf("[RE-RUN]  Skip row %d because shutdown is in progress%n", rowNum);
                return;
            }

            System.out.printf("[RE-RUN] [INFO] Row %d | %s | %s | %s%n", rowNum, loopback, device, cmdSet);

            PathFile fileInput = new PathFile();
            CachedDeviceRow cachedRow = getCachedDeviceRow(rowNum);
            CachedSettings settings = getCachedSettings();

            String group = cachedRow != null ? cachedRow.group : "";
            String devNameFromSheet = cachedRow != null ? cachedRow.device : "";
            String ipFromSheet = cachedRow != null ? cachedRow.loopback : "";
            String lastCommand = getCachedLastCommand(cmdSet);

            String server = settings.server;
            String userServer = settings.userServer;
            String pwServer = settings.pwServer;
            String userCLLS = settings.userCLLS;
            String pwCLLS = settings.pwCLLS;
            String userL2 = settings.userL2;
            String pwL2 = settings.pwL2;

            if (cachedRow == null || !settings.isConfigured() || lastCommand.isEmpty()) {
                File excelFile = new File(fileInput.getUserInterface_Input());
                if (!excelFile.exists()) {
                    System.out.println("[RE-RUN] [FAIL] Excel file not found: " + excelFile.getAbsolutePath());
                    return;
                }

                try ( Workbook workbook = WorkbookFactory.create(excelFile)) {
                    Sheet setSheet = getSheetAny(workbook, TRUE_SETTING_SHEET, LEGACY_SETTING_SHEET);
                    Sheet deviceSheet = getSheetAny(workbook, TRUE_DEVICE_SHEET, LEGACY_DEVICE_SHEET);
                    Map<String, String> trueSettings = readSettingValues(setSheet);
                    String deviceSelectMode = firstSettingValue(trueSettings, "deviceSelectMode");
                    int deviceRowStart = firstSettingInt(trueSettings, 0, "deviceRowStart");
                    int deviceRowEnd = firstSettingInt(trueSettings, 0, "deviceRowEnd");
                    rebuildExcelCacheForSelection(workbook, deviceSelectMode, deviceRowStart, deviceRowEnd,
                            deviceSheet != null ? deviceSheet.getSheetName() : TRUE_DEVICE_SHEET);

                    CachedDeviceRow refreshedRow = getCachedDeviceRow(rowNum);
                    CachedSettings refreshedSettings = getCachedSettings();
                    if (refreshedRow != null) {
                        group = refreshedRow.group;
                        devNameFromSheet = refreshedRow.device;
                        ipFromSheet = refreshedRow.loopback;
                    }

                    if (setSheet != null) {
                        CachedSettings loadedSettings = loadSettingsFromSheet(setSheet);
                        server = loadedSettings.server;
                        userServer = loadedSettings.userServer;
                        pwServer = loadedSettings.pwServer;
                        userCLLS = loadedSettings.userCLLS;
                        pwCLLS = loadedSettings.pwCLLS;
                        userL2 = loadedSettings.userL2;
                        pwL2 = loadedSettings.pwL2;
                        updateCachedSettings(server, userServer, pwServer, userCLLS, pwCLLS, userL2, pwL2);
                    } else if (refreshedSettings.isConfigured()) {
                        server = refreshedSettings.server;
                        userServer = refreshedSettings.userServer;
                        pwServer = refreshedSettings.pwServer;
                        userCLLS = refreshedSettings.userCLLS;
                        pwCLLS = refreshedSettings.pwCLLS;
                        userL2 = refreshedSettings.userL2;
                        pwL2 = refreshedSettings.pwL2;
                    }

                    lastCommand = getCachedLastCommand(cmdSet);
                    if (lastCommand.isEmpty()) {
                        lastCommand = getLastCommandFromCmdSheet(workbook, cmdSet);
                    }
                }
            }

            String devName = (device != null && !device.trim().isEmpty()) ? device.trim() : devNameFromSheet;
            String ip = (loopback != null && !loopback.trim().isEmpty()) ? loopback.trim() : ipFromSheet;
            String cmd = cmdSet != null && !cmdSet.trim().isEmpty() ? cmdSet.trim() : "";

            if (!"Y".equalsIgnoreCase(group)) {
                System.out.printf("[RE-RUN]  Skipped %s (Group != Y)%n", devName);
                return;
            }

            if (devName == null || devName.trim().isEmpty() || ip == null || ip.trim().isEmpty()) {
                System.out.printf("[RE-RUN]  Skip row %d because device/ip is empty%n", rowNum);
                return;
            }

            if (cmd.isEmpty()) {
                System.out.printf("[RE-RUN]  Skip row %d because cmdSet is empty%n", rowNum);
                return;
            }

            if (Telnet_Multi.hasActiveLogSessionFor(rowNum, ip, cmd)) {
                System.out.printf("[RE-RUN]  Skip rerun Row %d | %s | %s because original log is still active%n",
                        rowNum, ip, cmd);
                return;
            }

            String rerunKey = rowNum + "|" + ip + "|" + devName + "|" + normalizeCmdSetFamily(cmd);
            if (!rerunOncePerRunKeys.add(rerunKey)) {
                System.out.printf("[RE-RUN]  Skip rerun Row %d | %s | %s because rerun already happened once in this program run%n",
                        rowNum, ip, cmd);
                return;
            }

            File completedLog = findLatestCompletedLog(fileInput, rowNum, ip, devName, cmd, lastCommand);
            if (completedLog != null) {
                rerunOncePerRunKeys.remove(rerunKey);
                System.out.printf("[RE-RUN]  Skip rerun Row %d | %s | %s because completed equivalent log already exists: %s%n",
                        rowNum, ip, cmd, completedLog.getName());
                return;
            }

            final String finalDevName = devName;
            final String finalIp = ip;
            final String finalCmd = cmd;
            final String finalServer = server;
            final String finalUserServer = userServer;
            final String finalPwServer = pwServer;
            final String finalUserCLLS = userCLLS;
            final String finalPwCLLS = pwCLLS;
            final String finalUserL2 = userL2;
            final String finalPwL2 = pwL2;
            final String finalLastCommand = lastCommand;

            ThreadPoolExecutor exec = getExecutor();
            Runnable reRunTask = () -> {
                boolean telnetPermitAcquired = false;
                try {
                    if (isShutdownRequested()) {
                        System.out.printf("[RE-RUN]  Skip Row %d | %s | %s because shutdown is in progress%n",
                                rowNum, finalIp, finalCmd);
                        return;
                    }

                    File latestCompletedLog = findLatestCompletedLog(fileInput, rowNum, finalIp, finalDevName, finalCmd, finalLastCommand);
                    if (latestCompletedLog != null) {
                        rerunOncePerRunKeys.remove(rerunKey);
                        System.out.printf("[RE-RUN]  Skip rerun Row %d | %s | %s because completed equivalent log already exists before submit: %s%n",
                                rowNum, finalIp, finalCmd, latestCompletedLog.getName());
                        return;
                    }

                    if (Telnet_Multi.hasActiveLogSessionFor(rowNum, finalIp, finalCmd)) {
                        rerunOncePerRunKeys.remove(rerunKey);
                        System.out.printf("[RE-RUN]  Skip rerun Row %d | %s | %s because original log became active before submit%n",
                                rowNum, finalIp, finalCmd);
                        return;
                    }

                    Telnet_Multi.TELNET_LIMIT.acquire();
                    telnetPermitAcquired = true;

                    if (isShutdownRequested()) {
                        System.out.printf("[RE-RUN]  Skip Row %d | %s | %s after TELNET permit because shutdown is in progress%n",
                                rowNum, finalIp, finalCmd);
                        return;
                    }

                    System.out.printf("[RE-RUN]  Starting re-run Telnet for %s (%s) [%s]%n", finalDevName, finalIp, finalCmd);
                    new Telnet_Multi(
                            finalServer, finalUserServer, finalPwServer,
                            finalIp, finalUserCLLS, finalPwCLLS,
                            finalCmd, finalDevName, rowNum,
                            finalUserL2, finalPwL2
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.printf("[RE-RUN] [CANCEL] Interrupted while waiting for TELNET permit: %s (%s) [%s]%n",
                            finalDevName, finalIp, finalCmd);
                } catch (Exception e) {
                    System.out.println("[RE-RUN] [WARN] Error: " + e.getMessage());
                } finally {
                    if (telnetPermitAcquired) {
                        Telnet_Multi.TELNET_LIMIT.release();
                    }
                }
            };

            if (exec != null && !exec.isShutdown()) {
                exec.submit(reRunTask);
            } else if (!isShutdownRequested()) {
                new Thread(reRunTask, "DirectReRun-" + devName).start();
            }

        } catch (Exception e) {
            System.out.println("[RE-RUN] [WARN] Error rerunning node: " + e.getMessage());
        }
    }

}

