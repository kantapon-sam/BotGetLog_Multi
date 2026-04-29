package com.java.analytics;

import com.java.shared.AppMetadata;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class UsageAnalytics {

    private static final String ENABLED_PROPERTY = "botgetlog.analytics.enabled";
    private static final String ENABLED_ENV = "BOTGETLOG_ANALYTICS_ENABLED";
    private static final String ENDPOINT_URL_PROPERTY = "botgetlog.analytics.endpointUrl";
    private static final String ENDPOINT_URL_ENV = "BOTGETLOG_ANALYTICS_ENDPOINT_URL";
    private static final String API_KEY_PROPERTY = "botgetlog.analytics.apiKey";
    private static final String API_KEY_ENV = "BOTGETLOG_ANALYTICS_API_KEY";
    private static final String MAX_BATCH_SIZE_PROPERTY = "botgetlog.analytics.maxBatchSize";
    private static final String MAX_BATCH_SIZE_ENV = "BOTGETLOG_ANALYTICS_MAX_BATCH_SIZE";
    private static final String DEBUG_PROPERTY = "botgetlog.analytics.debug";
    private static final String DEBUG_ENV = "BOTGETLOG_ANALYTICS_DEBUG";
    private static final String CONFIG_FILE_NAME = "analytics.properties";
    private static final String QUEUE_FILE_NAME = "analytics-queue.tsv";
    private static final String MACHINE_ID_FILE_NAME = "machine-id.txt";
    private static final String FLUSH_LOCK_FILE_NAME = "analytics-flush.lock";
    private static final String ANALYTICS_LOG_FILE_NAME = "analytics.log";
    private static final String DEFAULT_ENDPOINT_URL = "";
    private static final String DEFAULT_API_KEY = "";
    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new AnalyticsThreadFactory());
    private static final Object LOG_LOCK = new Object();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                flushNow();
            } catch (Exception ignored) {
            }
            EXECUTOR.shutdown();
        }, "usage-analytics-shutdown"));
    }

    private UsageAnalytics() {
    }

    public static void trackLaunchAsync(String toolName) {
        if (!isEnabled()) {
            return;
        }
        final String safeToolName = safe(toolName);
        EXECUTOR.submit(() -> {
            try {
                appendLaunchEvent(safeToolName);
                flushNow();
            } catch (Exception e) {
                log("Analytics enqueue failed for " + safeToolName + ": " + e.getMessage());
            }
        });
    }

    public static boolean isEnabled() {
        return parseBoolean(resolveValue(ENABLED_PROPERTY, ENABLED_ENV, "enabled"));
    }

    public static void flushNow() {
        if (!isEnabled()) {
            return;
        }
        AnalyticsConfig config = AnalyticsConfig.load();
        if (!config.isValid()) {
            log("Analytics is enabled but endpointUrl is missing.");
            return;
        }

        File flushLockFile = new File(getAnalyticsDirectory(), FLUSH_LOCK_FILE_NAME);
        try (RandomAccessFile raf = new RandomAccessFile(flushLockFile, "rw");
                FileChannel channel = raf.getChannel();
                FileLock ignored = channel.lock()) {
            while (true) {
                List<LaunchEvent> batch = readBatch(config.maxBatchSize);
                if (batch.isEmpty()) {
                    return;
                }
                dispatchBatch(config, batch);
                dropBatch(batch.size());
            }
        } catch (IOException e) {
            log("Analytics flush skipped: " + e.getMessage());
        }
    }

    private static void appendLaunchEvent(String toolName) throws IOException {
        LaunchEvent event = new LaunchEvent(
                UUID.randomUUID().toString(),
                "app_launch",
                toolName,
                AppMetadata.getCurrentVersion(),
                getOrCreateMachineId(),
                OffsetDateTime.now().format(ISO_TIME),
                OffsetDateTime.now(ZoneOffset.UTC).format(ISO_TIME)
        );
        appendLine(event.toQueueLine());
    }

    private static String getOrCreateMachineId() throws IOException {
        File machineIdFile = new File(getAnalyticsDirectory(), MACHINE_ID_FILE_NAME);
        ensureParentDirectory(machineIdFile);
        if (machineIdFile.isFile()) {
            String existing = new String(Files.readAllBytes(machineIdFile.toPath()), StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        String created = UUID.randomUUID().toString().replace("-", "");
        Files.write(machineIdFile.toPath(), Collections.singletonList(created), StandardCharsets.UTF_8);
        return created;
    }

    private static void appendLine(String line) throws IOException {
        File queueFile = getQueueFile();
        ensureParentDirectory(queueFile);
        try (RandomAccessFile raf = new RandomAccessFile(queueFile, "rw");
                FileChannel channel = raf.getChannel();
                FileLock ignored = channel.lock()) {
            raf.seek(raf.length());
            raf.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<LaunchEvent> readBatch(int maxBatchSize) throws IOException {
        File queueFile = getQueueFile();
        if (!queueFile.isFile()) {
            return Collections.emptyList();
        }

        List<LaunchEvent> events = new ArrayList<LaunchEvent>();
        try (RandomAccessFile raf = new RandomAccessFile(queueFile, "rw");
                FileChannel channel = raf.getChannel();
                FileLock ignored = channel.lock();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Channels.newInputStream(channel.position(0)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                LaunchEvent event = LaunchEvent.fromQueueLine(line);
                if (event != null) {
                    events.add(event);
                }
                if (events.size() >= maxBatchSize) {
                    break;
                }
            }
        }
        return events;
    }

    private static void dropBatch(int count) throws IOException {
        if (count <= 0) {
            return;
        }
        File queueFile = getQueueFile();
        if (!queueFile.isFile()) {
            return;
        }

        List<String> remaining = new ArrayList<String>();
        try (RandomAccessFile raf = new RandomAccessFile(queueFile, "rw");
                FileChannel channel = raf.getChannel();
                FileLock ignored = channel.lock();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Channels.newInputStream(channel.position(0)), StandardCharsets.UTF_8))) {
            String line;
            int skipped = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (skipped < count) {
                    skipped++;
                    continue;
                }
                remaining.add(line);
            }
            channel.truncate(0);
        }

        if (remaining.isEmpty()) {
            Files.deleteIfExists(queueFile.toPath());
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(queueFile.toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < remaining.size(); i++) {
                if (i > 0) {
                    writer.newLine();
                }
                writer.write(remaining.get(i));
            }
        }
    }

    private static void dispatchBatch(AnalyticsConfig config, List<LaunchEvent> batch) throws IOException {
        HttpURLConnection connection = openConnection(config.endpointUrl);
        byte[] payload = buildRequestPayload(config, batch).getBytes(StandardCharsets.UTF_8);
        String response;
        try {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            response = readResponseBody(connection);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Apps Script returned HTTP " + status + ": " + response);
            }
        } finally {
            connection.disconnect();
        }

        if (!containsOkTrue(response)) {
            throw new IOException("Apps Script response did not confirm success: " + response);
        }
    }

    private static HttpURLConnection openConnection(String endpointUrl) throws IOException {
        URL url = new URL(endpointUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("User-Agent", AppMetadata.getAppName() + "-analytics");
        return connection;
    }

    private static String buildRequestPayload(AnalyticsConfig config, List<LaunchEvent> batch) {
        StringBuilder json = new StringBuilder(1024);
        json.append("{");
        json.append("\"source\":\"").append(escapeJson(AppMetadata.getAppName())).append("\",");
        json.append("\"batch_created_at\":\"").append(escapeJson(OffsetDateTime.now(ZoneOffset.UTC).format(ISO_TIME))).append("\"");
        if (!config.apiKey.isEmpty()) {
            json.append(",\"api_key\":\"").append(escapeJson(config.apiKey)).append("\"");
        }
        json.append(",\"events\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(batch.get(i).toJson());
        }
        json.append("]}");
        return json.toString();
    }

    private static String readResponseBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            stream = connection.getInputStream();
        }
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    private static boolean containsOkTrue(String response) {
        String normalized = safe(response).replace(" ", "").replace("\r", "").replace("\n", "").toLowerCase();
        return normalized.contains("\"ok\":true");
    }

    private static File getAnalyticsDirectory() {
        return new File(AppMetadata.getOutputDirectory(), "Analytics");
    }

    private static File getQueueFile() {
        return new File(getAnalyticsDirectory(), QUEUE_FILE_NAME);
    }

    private static void ensureParentDirectory(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Files.createDirectories(parent.toPath());
        }
    }

    private static void log(String message) {
        if (!isDebugEnabled()) {
            return;
        }
        synchronized (LOG_LOCK) {
            try {
                File logFile = new File(getAnalyticsDirectory(), ANALYTICS_LOG_FILE_NAME);
                ensureParentDirectory(logFile);
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        Files.newOutputStream(logFile.toPath(),
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND),
                        StandardCharsets.UTF_8))) {
                    writer.write("[" + OffsetDateTime.now().format(ISO_TIME) + "] " + message);
                    writer.newLine();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isDebugEnabled() {
        return parseBoolean(resolveValue(DEBUG_PROPERTY, DEBUG_ENV, "debug"));
    }

    private static String resolveValue(String propertyName, String envName, String fileKey) {
        String propertyValue = safe(System.getProperty(propertyName));
        if (!propertyValue.isEmpty()) {
            return propertyValue;
        }
        String envValue = safe(System.getenv(envName));
        if (!envValue.isEmpty()) {
            return envValue;
        }
        return safe(loadConfigFileProperties().getProperty(fileKey));
    }

    private static Properties loadConfigFileProperties() {
        Properties properties = new Properties();
        File configFile = getConfigFile();
        if (!configFile.isFile()) {
            return properties;
        }
        try (InputStream input = new FileInputStream(configFile)) {
            properties.load(input);
        } catch (IOException e) {
            log("Failed to read analytics config file: " + e.getMessage());
        }
        return properties;
    }

    private static File getConfigFile() {
        File appConfig = new File(AppMetadata.getAppDirectory(), CONFIG_FILE_NAME);
        if (appConfig.isFile()) {
            return appConfig;
        }
        return new File(CONFIG_FILE_NAME);
    }

    private static boolean parseBoolean(String value) {
        String normalized = safe(value).toLowerCase();
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(safe(value));
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escapeJson(String value) {
        String safeValue = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(safeValue.length() + 16);
        for (int i = 0; i < safeValue.length(); i++) {
            char c = safeValue.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private static final class AnalyticsConfig {

        final String endpointUrl;
        final String apiKey;
        final int maxBatchSize;

        private AnalyticsConfig(String endpointUrl, String apiKey, int maxBatchSize) {
            this.endpointUrl = endpointUrl;
            this.apiKey = apiKey;
            this.maxBatchSize = maxBatchSize;
        }

        static AnalyticsConfig load() {
            String endpointUrl = resolveValue(ENDPOINT_URL_PROPERTY, ENDPOINT_URL_ENV, "endpointUrl");
            if (endpointUrl.isEmpty()) {
                endpointUrl = DEFAULT_ENDPOINT_URL;
            }
            String apiKey = resolveValue(API_KEY_PROPERTY, API_KEY_ENV, "apiKey");
            if (apiKey.isEmpty()) {
                apiKey = DEFAULT_API_KEY;
            }
            int maxBatchSize = parseInt(resolveValue(MAX_BATCH_SIZE_PROPERTY, MAX_BATCH_SIZE_ENV, "maxBatchSize"), DEFAULT_BATCH_SIZE);
            return new AnalyticsConfig(endpointUrl, apiKey, maxBatchSize);
        }

        boolean isValid() {
            return !endpointUrl.isEmpty()
                    && endpointUrl.indexOf("DEPLOYMENT_ID") < 0
                    && endpointUrl.indexOf("PASTE_") < 0;
        }
    }

    private static final class LaunchEvent {

        final String eventId;
        final String eventType;
        final String toolName;
        final String appVersion;
        final String machineId;
        final String startedAt;
        final String queuedAt;

        private LaunchEvent(String eventId, String eventType, String toolName,
                String appVersion, String machineId, String startedAt, String queuedAt) {
            this.eventId = safe(eventId);
            this.eventType = safe(eventType);
            this.toolName = safe(toolName);
            this.appVersion = safe(appVersion);
            this.machineId = safe(machineId);
            this.startedAt = safe(startedAt);
            this.queuedAt = safe(queuedAt);
        }

        String toQueueLine() {
            StringBuilder line = new StringBuilder(256);
            line.append(escapeField(eventId)).append('\t')
                    .append(escapeField(eventType)).append('\t')
                    .append(escapeField(toolName)).append('\t')
                    .append(escapeField(appVersion)).append('\t')
                    .append(escapeField(machineId)).append('\t')
                    .append(escapeField(startedAt)).append('\t')
                    .append(escapeField(queuedAt));
            return line.toString();
        }

        String toJson() {
            return new StringBuilder(256)
                    .append("{\"event_id\":\"").append(escapeJson(eventId)).append('"')
                    .append(",\"event_type\":\"").append(escapeJson(eventType)).append('"')
                    .append(",\"tool_name\":\"").append(escapeJson(toolName)).append('"')
                    .append(",\"app_version\":\"").append(escapeJson(appVersion)).append('"')
                    .append(",\"machine_id\":\"").append(escapeJson(machineId)).append('"')
                    .append(",\"started_at\":\"").append(escapeJson(startedAt)).append('"')
                    .append(",\"queued_at\":\"").append(escapeJson(queuedAt)).append("\"}")
                    .toString();
        }

        static LaunchEvent fromQueueLine(String line) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 7) {
                return null;
            }
            return new LaunchEvent(
                    unescapeField(parts[0]),
                    unescapeField(parts[1]),
                    unescapeField(parts[2]),
                    unescapeField(parts[3]),
                    unescapeField(parts[4]),
                    unescapeField(parts[5]),
                    unescapeField(parts[6])
            );
        }

        private static String escapeField(String value) {
            String safeValue = value == null ? "" : value;
            return safeValue
                    .replace("\\", "\\\\")
                    .replace("\t", "\\t")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
        }

        private static String unescapeField(String value) {
            StringBuilder decoded = new StringBuilder(value.length());
            boolean escaped = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (escaped) {
                    switch (c) {
                        case 't':
                            decoded.append('\t');
                            break;
                        case 'r':
                            decoded.append('\r');
                            break;
                        case 'n':
                            decoded.append('\n');
                            break;
                        case '\\':
                            decoded.append('\\');
                            break;
                        default:
                            decoded.append(c);
                            break;
                    }
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else {
                    decoded.append(c);
                }
            }
            if (escaped) {
                decoded.append('\\');
            }
            return decoded.toString();
        }
    }

    private static final class AnalyticsThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "usage-analytics");
            thread.setDaemon(true);
            return thread;
        }
    }
}
