package com.java.launcher;

import com.java.shared.AppMetadata;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;

public final class ChromeAutoLoginService {

    private static final int FALLBACK_REMOTE_DEBUG_PORT = 39223;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int STARTUP_TIMEOUT_MS = 20000;
    private static final int TARGET_WAIT_TIMEOUT_MS = 12000;
    private static final int TARGET_WAIT_INTERVAL_MS = 350;
    private static final int SCRIPT_INTERVAL_MS = 1600;
    private static final int SCRIPT_ATTEMPTS = 18;
    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final String siteLabel;
    private final String targetUrl;
    private final SiteCredential credential;
    private final LoginRecipe recipe;
    private final int remoteDebugPort;

    public ChromeAutoLoginService(String siteLabel, String targetUrl, SiteCredential credential, LoginRecipe recipe) {
        this.siteLabel = siteLabel == null ? "" : siteLabel;
        this.targetUrl = targetUrl == null ? "" : targetUrl;
        this.credential = credential;
        this.recipe = recipe;
        this.remoteDebugPort = findAvailablePort();
    }

    public void run() {
        if (credential == null || !credential.hasUsableCredential()) {
            LauncherActivityLog.warn(siteLabel, "Auto Login skipped because saved credential was not found.");
            showWarning("Saved credential not found for " + siteLabel);
            return;
        }

        BrowserExecutable browser = findBrowserExecutable();
        if (browser == null) {
            LauncherActivityLog.error(siteLabel, "Auto Login failed because Chrome executable was not found.");
            showError("Chrome was not found on this PC.\nPlease install Chrome or set CHROME_PATH.");
            return;
        }

        boolean browserStarted = false;
        try {
            LauncherActivityLog.info(siteLabel, "Starting Auto Login using " + browser.name + " on debug port " + remoteDebugPort + ".");
            Set<String> knownTargetIds = new HashSet<String>(getCurrentTargetIds());
            startAutomationBrowser(browser);
            browserStarted = true;
            waitForDebugEndpoint();
            DevToolsTarget target = waitForTarget(knownTargetIds);
            if (target == null) {
                throw new IOException("Cannot find a Chrome tab for Auto Login.");
            }

            String automationScript = recipe.buildAutomationScript(
                    credential.getUsername(),
                    credential.getPassword()
            );

            try (ChromeDevToolsSocket socket = ChromeDevToolsSocket.open(target.webSocketDebuggerUrl)) {
                socket.sendCommand("Page.enable", "{}");
                socket.sendCommand("Runtime.enable", "{}");
                socket.sendCommand("Page.bringToFront", "{}");
                socket.sendCommand("Page.navigate", "{\"url\":" + toJsonString(targetUrl) + "}");
                socket.drainSilently(1000);

                for (int attempt = 0; attempt < SCRIPT_ATTEMPTS; attempt++) {
                    sleepQuietly(SCRIPT_INTERVAL_MS);
                    socket.sendCommand(
                            "Runtime.evaluate",
                            "{"
                            + "\"expression\":" + toJsonString(automationScript) + ","
                            + "\"returnByValue\":true,"
                            + "\"awaitPromise\":false"
                            + "}"
                    );
                    socket.drainSilently(300);
                }
            }
            LauncherActivityLog.info(siteLabel, "Auto Login commands finished without popup.");
        } catch (Exception ex) {
            if (browserStarted) {
                LauncherActivityLog.warn(siteLabel, "Auto Login continued without popup after browser started: " + safeMessage(ex));
                System.err.println("[Auto Login] " + siteLabel + " skipped popup: " + safeMessage(ex));
                return;
            }
            LauncherActivityLog.error(siteLabel, "Auto Login stopped before browser was ready.", ex);
            showError("Auto Login failed for " + siteLabel + ".\n" + safeMessage(ex));
        }
    }

    private void startAutomationBrowser(BrowserExecutable browser) throws IOException {
        File profileDir = resolveChromeProfileDirectory();
        profileDir.mkdirs();

        List<String> command = new ArrayList<String>();
        command.add(browser.executable.getAbsolutePath());
        command.add("--remote-debugging-port=" + remoteDebugPort);
        command.add("--user-data-dir=" + profileDir.getAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--disable-search-engine-choice-screen");
        command.add("--disable-session-crashed-bubble");
        command.add("--new-window");
        command.add("about:blank");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(AppMetadata.getAppDirectory());
        builder.start();
    }

    private File resolveChromeProfileDirectory() {
        File profileDir = AppMetadata.getLauncherChromeProfileDirectory();
        if (profileDir.exists()) {
            return profileDir;
        }

        File legacyProfileDir = new File(AppMetadata.getOutputDirectory(), "launcher-chrome-profile");
        if (legacyProfileDir.isDirectory()) {
            if (legacyProfileDir.renameTo(profileDir)) {
                return profileDir;
            }
        }
        return profileDir;
    }

    private void waitForDebugEndpoint() throws IOException {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                readUrl(buildDebugUrl("/json/version"));
                return;
            } catch (IOException ignored) {
                sleepQuietly(350);
            }
        }
        throw new IOException("Chrome remote debugging endpoint did not start.");
    }

    private Set<String> getCurrentTargetIds() {
        List<DevToolsTarget> targets = listPageTargets();
        if (targets.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> ids = new HashSet<String>();
        for (DevToolsTarget target : targets) {
            ids.add(target.id);
        }
        return ids;
    }

    private DevToolsTarget waitForTarget(Set<String> knownTargetIds) throws IOException {
        long deadline = System.currentTimeMillis() + TARGET_WAIT_TIMEOUT_MS;
        DevToolsTarget fallback = null;
        while (System.currentTimeMillis() < deadline) {
            List<DevToolsTarget> targets = listPageTargets();
            for (DevToolsTarget target : targets) {
                if (!knownTargetIds.contains(target.id)) {
                    return target;
                }
            }
            if (fallback == null && !targets.isEmpty()) {
                fallback = targets.get(targets.size() - 1);
            }
            sleepQuietly(TARGET_WAIT_INTERVAL_MS);
        }
        return fallback;
    }

    private List<DevToolsTarget> listPageTargets() {
        try {
            String response = readUrl(buildDebugUrl("/json/list"));
            return parseTargets(response);
        } catch (IOException ex) {
            return Collections.emptyList();
        }
    }

    private String buildDebugUrl(String path) {
        return "http://127.0.0.1:" + remoteDebugPort + path;
    }

    private List<DevToolsTarget> parseTargets(String jsonArray) {
        List<DevToolsTarget> targets = new ArrayList<DevToolsTarget>();
        for (String objectJson : splitTopLevelObjects(jsonArray)) {
            String id = getJsonStringField(objectJson, "id");
            String type = getJsonStringField(objectJson, "type");
            String url = getJsonStringField(objectJson, "url");
            String webSocketDebuggerUrl = getJsonStringField(objectJson, "webSocketDebuggerUrl");
            if (id.isEmpty() || webSocketDebuggerUrl.isEmpty()) {
                continue;
            }
            if (!"page".equalsIgnoreCase(type)) {
                continue;
            }
            targets.add(new DevToolsTarget(id, url, webSocketDebuggerUrl));
        }
        return targets;
    }

    private static String getJsonStringField(String objectJson, String fieldName) {
        Matcher matcher = JSON_STRING_FIELD.matcher(objectJson);
        while (matcher.find()) {
            if (fieldName.equals(matcher.group(1))) {
                return unescapeJson(matcher.group(2));
            }
        }
        return "";
    }

    private static List<String> splitTopLevelObjects(String jsonArray) {
        if (jsonArray == null || jsonArray.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> objects = new ArrayList<String>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int objectStart = -1;
        for (int i = 0; i < jsonArray.length(); i++) {
            char current = jsonArray.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                continue;
            }
            if (current == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
                continue;
            }
            if (current == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(jsonArray.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }
        return objects;
    }

    private static BrowserExecutable findBrowserExecutable() {
        List<BrowserExecutable> browsers = new ArrayList<BrowserExecutable>();
        addBrowserCandidate(browsers, "Chrome", System.getenv("CHROME_PATH"));
        addBrowserCandidate(browsers, "Chrome", expandPath("%ProgramFiles%\\Google\\Chrome\\Application\\chrome.exe"));
        addBrowserCandidate(browsers, "Chrome", expandPath("%ProgramFiles(x86)%\\Google\\Chrome\\Application\\chrome.exe"));
        addBrowserCandidate(browsers, "Chrome", expandPath("%LocalAppData%\\Google\\Chrome\\Application\\chrome.exe"));
        addBrowserCandidate(browsers, "Edge", System.getenv("EDGE_PATH"));
        addBrowserCandidate(browsers, "Edge", expandPath("%ProgramFiles%\\Microsoft\\Edge\\Application\\msedge.exe"));
        addBrowserCandidate(browsers, "Edge", expandPath("%ProgramFiles(x86)%\\Microsoft\\Edge\\Application\\msedge.exe"));

        for (BrowserExecutable browser : browsers) {
            if (browser.executable.isFile()) {
                return browser;
            }
        }
        return null;
    }

    private static void addBrowserCandidate(List<BrowserExecutable> browsers, String name, String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        browsers.add(new BrowserExecutable(name, new File(path)));
    }

    private static String expandPath(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }

        String expanded = value;
        expanded = expanded.replace("%ProgramFiles%", System.getenv("ProgramFiles") == null ? "" : System.getenv("ProgramFiles"));
        expanded = expanded.replace("%ProgramFiles(x86)%", System.getenv("ProgramFiles(x86)") == null ? "" : System.getenv("ProgramFiles(x86)"));
        expanded = expanded.replace("%LocalAppData%", System.getenv("LocalAppData") == null ? "" : System.getenv("LocalAppData"));
        return expanded;
    }

    private static String readUrl(String urlText) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(CONNECT_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        try (InputStream input = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            return content.toString();
        }
    }

    private static String toJsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (current < 32) {
                        String hex = Integer.toHexString(current);
                        builder.append("\\u");
                        for (int pad = hex.length(); pad < 4; pad++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(current);
                    }
                    break;
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private static String unescapeJson(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    builder.append(current);
                }
                continue;
            }

            switch (current) {
                case '"':
                    builder.append('"');
                    break;
                case '\\':
                    builder.append('\\');
                    break;
                case '/':
                    builder.append('/');
                    break;
                case 'b':
                    builder.append('\b');
                    break;
                case 'f':
                    builder.append('\f');
                    break;
                case 'n':
                    builder.append('\n');
                    break;
                case 'r':
                    builder.append('\r');
                    break;
                case 't':
                    builder.append('\t');
                    break;
                case 'u':
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            builder.append('u').append(hex);
                            i += 4;
                        }
                    } else {
                        builder.append('u');
                    }
                    break;
                default:
                    builder.append(current);
                    break;
            }
            escaped = false;
        }
        return builder.toString();
    }

    private static void sleepQuietly(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            serverSocket.setReuseAddress(true);
            return serverSocket.getLocalPort();
        } catch (IOException ex) {
            return FALLBACK_REMOTE_DEBUG_PORT;
        }
    }

    private static String safeMessage(Exception ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().trim().isEmpty()) {
            return ex == null ? "Unknown error" : ex.getClass().getSimpleName();
        }
        return ex.getMessage().trim();
    }

    private static void showError(String message) {
        JOptionPane.showMessageDialog(
                null,
                message,
                "Auto Login",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private static void showWarning(String message) {
        JOptionPane.showMessageDialog(
                null,
                message,
                "Auto Login",
                JOptionPane.WARNING_MESSAGE
        );
    }

    private static final class BrowserExecutable {

        private final String name;
        private final File executable;

        private BrowserExecutable(String name, File executable) {
            this.name = name == null ? "" : name;
            this.executable = executable;
        }
    }

    private static final class DevToolsTarget {

        private final String id;
        private final String url;
        private final String webSocketDebuggerUrl;

        private DevToolsTarget(String id, String url, String webSocketDebuggerUrl) {
            this.id = id == null ? "" : id;
            this.url = url == null ? "" : url;
            this.webSocketDebuggerUrl = webSocketDebuggerUrl == null ? "" : webSocketDebuggerUrl;
        }
    }

    private static final class ChromeDevToolsSocket implements Closeable {

        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final SecureRandom random = new SecureRandom();
        private int messageId;

        private ChromeDevToolsSocket(Socket socket, InputStream input, OutputStream output) {
            this.socket = socket;
            this.input = input;
            this.output = output;
        }

        public static ChromeDevToolsSocket open(String wsUrl) throws IOException {
            URI uri = URI.create(wsUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            String path = uri.getRawPath();
            if (path == null || path.trim().isEmpty()) {
                path = "/";
            }
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                path = path + "?" + uri.getRawQuery();
            }

            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String key = java.util.Base64.getEncoder().encodeToString(nonce);

            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String headers = readHttpHeaders(input);
            if (!headers.startsWith("HTTP/1.1 101") && !headers.startsWith("HTTP/1.0 101")) {
                throw new IOException("Chrome DevTools handshake failed.");
            }
            return new ChromeDevToolsSocket(socket, input, output);
        }

        public void sendCommand(String method, String paramsJson) throws IOException {
            messageId++;
            StringBuilder payload = new StringBuilder();
            payload.append("{\"id\":").append(messageId)
                    .append(",\"method\":").append(toJsonString(method));
            if (paramsJson != null && !paramsJson.trim().isEmpty()) {
                payload.append(",\"params\":").append(paramsJson);
            }
            payload.append('}');
            writeTextFrame(payload.toString());
        }

        public void drainSilently(long durationMillis) {
            long deadline = System.currentTimeMillis() + durationMillis;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (input.available() <= 0) {
                        sleepQuietly(40);
                        continue;
                    }
                    readFrame();
                } catch (IOException ignored) {
                    return;
                }
            }
        }

        private void writeTextFrame(String message) throws IOException {
            byte[] payload = message.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);

            int payloadLength = payload.length;
            if (payloadLength <= 125) {
                frame.write(0x80 | payloadLength);
            } else if (payloadLength <= 0xFFFF) {
                frame.write(0x80 | 126);
                frame.write((payloadLength >>> 8) & 0xFF);
                frame.write(payloadLength & 0xFF);
            } else {
                frame.write(0x80 | 127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame.write((payloadLength >>> shift) & 0xFF);
                }
            }

            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }
            output.write(frame.toByteArray());
            output.flush();
        }

        private void readFrame() throws IOException {
            int first = input.read();
            if (first < 0) {
                throw new IOException("Chrome DevTools socket closed.");
            }
            int second = input.read();
            if (second < 0) {
                throw new IOException("Chrome DevTools socket closed.");
            }

            int opcode = first & 0x0F;
            long payloadLength = second & 0x7F;
            boolean masked = (second & 0x80) != 0;
            if (payloadLength == 126) {
                payloadLength = ((long) readByte() << 8) | readByte();
            } else if (payloadLength == 127) {
                payloadLength = 0L;
                for (int i = 0; i < 8; i++) {
                    payloadLength = (payloadLength << 8) | readByte();
                }
            }

            byte[] mask = null;
            if (masked) {
                mask = readBytes(4);
            }
            byte[] payload = readBytes((int) payloadLength);
            if (masked && mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }

            if (opcode == 0x9) {
                writeControlFrame(0xA, payload);
            } else if (opcode == 0x8) {
                throw new IOException("Chrome DevTools socket closed.");
            }
        }

        private void writeControlFrame(int opcode, byte[] payload) throws IOException {
            if (payload == null) {
                payload = new byte[0];
            }
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | (opcode & 0x0F));
            frame.write(0x80 | payload.length);
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }
            output.write(frame.toByteArray());
            output.flush();
        }

        private int readByte() throws IOException {
            int value = input.read();
            if (value < 0) {
                throw new IOException("Unexpected end of WebSocket stream.");
            }
            return value & 0xFF;
        }

        private byte[] readBytes(int length) throws IOException {
            byte[] data = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = input.read(data, offset, length - offset);
                if (read < 0) {
                    throw new IOException("Unexpected end of WebSocket stream.");
                }
                offset += read;
            }
            return data;
        }

        @Override
        public void close() throws IOException {
            try {
                writeControlFrame(0x8, new byte[0]);
            } catch (IOException ignored) {
            }
            socket.close();
        }

        private static String readHttpHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int matched = 0;
            byte[] terminator = new byte[]{'\r', '\n', '\r', '\n'};
            while (matched < terminator.length) {
                int value = input.read();
                if (value < 0) {
                    throw new IOException("Unexpected end of HTTP handshake.");
                }
                buffer.write(value);
                if (value == terminator[matched]) {
                    matched++;
                } else {
                    matched = value == terminator[0] ? 1 : 0;
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
