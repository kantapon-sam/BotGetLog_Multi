package com.java.botgetlog;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.net.telnet.TelnetClient;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public class Telnet_Multi {

    private enum GatewayProtocol {
        AUTO,
        TELNET,
        SSH
    }

    private static final class GatewayEndpoint {

        final GatewayProtocol protocol;
        final String host;
        final int port;
        final String rawValue;

        GatewayEndpoint(GatewayProtocol protocol, String host, int port, String rawValue) {
            this.protocol = protocol;
            this.host = safeTrim(host);
            this.port = port;
            this.rawValue = safeTrim(rawValue);
        }

        String displayTarget() {
            if (protocol == GatewayProtocol.AUTO) {
                if (port > 0) {
                    return "AUTO " + host + ":" + port + " (SSH -> TELNET)";
                }
                return "AUTO " + host + " (SSH:" + DEFAULT_SSH_PORT + " -> TELNET:" + DEFAULT_TELNET_PORT + ")";
            }
            return protocol.name() + " " + host + ":" + port;
        }
    }

    private static final class SshAuthMode {

        final String label;
        final String preferredAuthentications;

        SshAuthMode(String label, String preferredAuthentications) {
            this.label = safeTrim(label);
            this.preferredAuthentications = safeTrim(preferredAuthentications);
        }
    }

    private static final class SshConnectionHandles {

        final Session session;
        final ChannelShell channel;
        final InputStream inputStream;
        final PrintStream outputStream;

        SshConnectionHandles(Session session, ChannelShell channel, InputStream inputStream, PrintStream outputStream) {
            this.session = session;
            this.channel = channel;
            this.inputStream = inputStream;
            this.outputStream = outputStream;
        }
    }

    private static final class SshPasswordUserInfo implements UserInfo, UIKeyboardInteractive {

        private final String password;

        SshPasswordUserInfo(String password) {
            this.password = password == null ? "" : password;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptYesNo(String str) {
            return true;
        }

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name,
                String instruction, String[] prompt, boolean[] echo) {
            String[] responses = new String[prompt == null ? 0 : prompt.length];
            for (int i = 0; i < responses.length; i++) {
                responses[i] = password;
            }
            return responses;
        }
    }

    private static final class SshTraceCollector {

        private final List<String> lines = new ArrayList<>();

        void add(String message) {
            String normalized = safeTrim(message);
            if (normalized.isEmpty()) {
                return;
            }

            String lower = normalized.toLowerCase(Locale.ROOT);
            if (!(lower.contains("connecting to")
                    || lower.contains("connection established")
                    || lower.contains("remote version string")
                    || lower.contains("local version string")
                    || lower.contains("authentications that can continue")
                    || lower.contains("next authentication method")
                    || lower.contains("disconnecting from")
                    || lower.contains("service_accept")
                    || lower.contains("userauth")
                    || lower.contains("ssh_msg_disconnect"))) {
                return;
            }

            if (lines.size() >= 8) {
                return;
            }
            lines.add(normalized);
        }

        String summarize() {
            return lines.isEmpty() ? "" : String.join(" || ", lines);
        }
    }

    private static final class ThreadAwareJschLogger implements Logger {

        @Override
        public boolean isEnabled(int level) {
            return true;
        }

        @Override
        public void log(int level, String message) {
            SshTraceCollector collector = SSH_TRACE_COLLECTOR.get();
            if (collector != null) {
                collector.add(message);
            }
        }
    }

    private static final class CommandReadResult {

        final boolean promptDetected;
        final boolean remoteClosed;
        final boolean recovered;
        final boolean timedOut;
        final boolean ioError;
        final String detail;

        private CommandReadResult(boolean promptDetected, boolean remoteClosed, boolean recovered,
                boolean timedOut, boolean ioError, String detail) {
            this.promptDetected = promptDetected;
            this.remoteClosed = remoteClosed;
            this.recovered = recovered;
            this.timedOut = timedOut;
            this.ioError = ioError;
            this.detail = safeTrim(detail);
        }

        static CommandReadResult prompt() {
            return new CommandReadResult(true, false, false, false, false, "");
        }

        static CommandReadResult remoteClosed(String detail) {
            return new CommandReadResult(false, true, false, false, false, detail);
        }

        static CommandReadResult remoteClosedRecovered(String detail) {
            return new CommandReadResult(false, true, true, false, false, detail);
        }

        static CommandReadResult timeout(String detail) {
            return new CommandReadResult(false, false, false, true, false, detail);
        }

        static CommandReadResult ioError(String detail) {
            return new CommandReadResult(false, false, false, false, true, detail);
        }
    }

    // Get the current date and time
    LocalDateTime now = LocalDateTime.now();
    boolean failedAfterPassword = false;

    //  Semaphore  Telnet 
    public static final int NORMAL_TELNET_LIMIT = 6;
    private static final int DEFAULT_TELNET_PORT = 23;
    private static final int DEFAULT_SSH_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final long DEFAULT_COMMAND_MAX_WAIT_MS = 60L * 60L * 1000L;
    private static final long DEFAULT_COMMAND_IDLE_TIMEOUT_MS = 3L * 60L * 1000L;
    private static final long COMMAND_WAIT_LOG_INTERVAL_MS = 30L * 1000L;
    private static final SshAuthMode[] SSH_AUTH_MODES = new SshAuthMode[]{
        new SshAuthMode("password-only", "password"),
        new SshAuthMode("keyboard-interactive", "keyboard-interactive"),
        new SshAuthMode("password-then-keyboard-interactive", "password,keyboard-interactive")
    };
    private static final ThreadLocal<SshTraceCollector> SSH_TRACE_COLLECTOR = new ThreadLocal<>();
    private static volatile int currentTelnetLimit = NORMAL_TELNET_LIMIT;
    public static final AdjustableSemaphore TELNET_LIMIT
            = new AdjustableSemaphore(NORMAL_TELNET_LIMIT);

    static {
        JSch.setLogger(new ThreadAwareJschLogger());
        appendJschConfigValue("server_host_key", "ssh-rsa");
        appendJschConfigValue("PubkeyAcceptedAlgorithms", "ssh-rsa");
    }

    public static int getTurboTelnetLimit() {
        return Math.max(NORMAL_TELNET_LIMIT, Runtime.getRuntime().availableProcessors() / 2);
    }

    public static int getCurrentTelnetLimit() {
        return currentTelnetLimit;
    }

    private static void appendJschConfigValue(String key, String value) {
        try {
            String current = JSch.getConfig(key);
            if (current == null || current.trim().isEmpty()) {
                JSch.setConfig(key, value);
                return;
            }

            List<String> parts = Arrays.asList(current.split("\\s*,\\s*"));
            if (parts.contains(value)) {
                return;
            }
            JSch.setConfig(key, current + "," + value);
        } catch (Exception ignore) {
        }
    }

    private static String mergeJschConfigValue(String current, String value) {
        String normalizedValue = safeTrim(value);
        if (normalizedValue.isEmpty()) {
            return safeTrim(current);
        }

        String normalizedCurrent = safeTrim(current);
        if (normalizedCurrent.isEmpty()) {
            return normalizedValue;
        }

        List<String> parts = Arrays.asList(normalizedCurrent.split("\\s*,\\s*"));
        if (parts.contains(normalizedValue)) {
            return normalizedCurrent;
        }
        return normalizedCurrent + "," + normalizedValue;
    }

    public static final class AdjustableSemaphore extends java.util.concurrent.Semaphore {

        private AdjustableSemaphore(int permits) {
            super(permits);
        }

        private void shrinkPermits(int reduction) {
            super.reducePermits(reduction);
        }
    }

    // Format the date and time
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private String formattedDateTime = now.format(formatter);
    DateTimeFormatter formatterLOG = DateTimeFormatter.ofPattern("yyyyMMdd");
    private String formattedDateTimeLOG = now.format(formatterLOG);
    //private String LOG = "";
    private String s = "";
    private TelnetClient telnet = new TelnetClient();
    private Session sshSession;
    private ChannelShell sshChannel;
    private InputStream in;
    private PrintStream out;
    private String prompt = "";
    private String Card = "";
    private String Port_Nokia = "";
    private String Bas_Interface = "";
    private String Loopback = "";
    private String runtimeDeviceName = "";
    private String gatewayServerAddress = "";
    private String gatewayUsername = "";
    private String gatewayPassword = "";
    private String nodeUsername = "";
    private String nodePassword = "";
    private String l2Username = "";
    private String l2Password = "";
    private GatewayEndpoint gatewayEndpoint;
    PathFile FileInput = new PathFile();

    StringBuilder LOG = new StringBuilder();
    private boolean vendorMismatch = false;
    private boolean sessionFailureRecorded = false;

    public boolean hasSessionFailureRecorded() {
        return sessionFailureRecorded;
    }

    //   log 
    private String preparedLogSessionKey = null;
    private String activeLogSessionPath = null;
    private String activeLogSessionIdentity = null;
    private String activeLogDateTag = null;
    private static final int READ_MATCH_WINDOW_CHARS = 4096;
    private static final int READ_CHECK_INTERVAL_CHARS = 8;
    private static final String[] READ_PAGINATION_MARKERS = new String[]{
        "--more--", "more?", "---- more ----", "--- more ---", "-- more --"
    };
    private static final String[] READ_AUTH_PROMPTS = new String[]{"username:", "login:", "password:"};
    private static final String[] GATEWAY_READY_PATTERNS = new String[]{
        "enter ip address [press q/q to quit]:",
        "enter ip address [press q to quit]:",
        "enter ip address:"
    };
    private static final int LOGIN_PROMPT_DELAY_BASE_MS = 200;
    private static final int LOGIN_PROMPT_DELAY_JITTER_MS = 400;
    private static final int CHECKLOGIN_DELAY_BASE_MS = 200;
    private static final int CHECKLOGIN_DELAY_JITTER_MS = 300;
    private static final int PROMPT_RETRY_DELAY_BASE_MS = 1500;
    private static final int PROMPT_RETRY_DELAY_JITTER_MS = 500;
    private static final int NOKIA_POST_LOGIN_DELAY_BASE_MS = 500;
    private static final int NOKIA_POST_LOGIN_DELAY_JITTER_MS = 500;
    private static final int NOKIA_BETWEEN_COMMAND_DELAY_BASE_MS = 200;
    private static final int NOKIA_BETWEEN_COMMAND_DELAY_JITTER_MS = 100;
    private static final int PROMPT_STABILIZE_DELAY_MS = 150;
    private static final int POST_LOGIN_FINAL_DELAY_MS = 500;
    private static final String[] PRELOGIN_STOP_TOKENS = new String[]{"username:", "user name:", "login:", "password:", "ogin:"};
    private static final String[] CHECKLOGIN_BANNER_NOISE_TOKENS = new String[]{
        "authorised users only", "prohibited", "monitored", "stelnet",
        "protocol", "escape character", "press any key", "banner", "device is prohibited"
    };
    private static final String[] CHECKLOGIN_WARNING_BANNER_TOKENS = new String[]{
        "unauthorised", "unauthorized", "warning", "access to this system",
        "access to this", "confidential", "telnet is not a secure"
    };
    private static final String[] CHECKLOGIN_PROMPT_TOKENS = new String[]{"username", "user name", "login", "ogin"};
    private static final String[] CHECKLOGIN_FAIL_TOKENS = new String[]{"unreachable", "connection timed out"};
    private static final long WRONG_VENDOR_INACTIVE_THRESHOLD_MS = 60 * 1000L;
    private static final Pattern DAILY_LOG_FILE_PATTERN
            = Pattern.compile("\\[(\\d+)](.*?)_(.*?)_(.*?)_(\\d{4}-\\d{2}-\\d{2})\\.txt");
    private static final Pattern WRONG_VENDOR_SIGNAL_PATTERN
            = Pattern.compile(".*(%error\\s*[0-9:]*|invalid input detected|bad command|unrecognized command|unknown command|syntax error|command not found|invalid command).*");
    private static final Pattern ANSI_CURSOR_LEFT_PATTERN = Pattern.compile("([0-9]+)?D");
    private static final Pattern READ_PAGINATION_PATTERN
            = Pattern.compile("-+\\s*more(?:\\s*\\([^\\r\\n]{0,80}\\))?\\s*-+|more\\?");
    private static final String[] REMOTE_SESSION_CLOSE_MARKERS = new String[]{
        "script done",
        "enter ip address [press q",
        "logout"
    };

    private static GatewayEndpoint parseGatewayEndpoint(String server) {
        String raw = safeTrim(server);
        String hostPort = raw;
        GatewayProtocol protocol = GatewayProtocol.AUTO;
        int port = 0;

        int schemeIndex = raw.indexOf("://");
        if (schemeIndex > 0) {
            String scheme = raw.substring(0, schemeIndex).trim().toLowerCase(Locale.ROOT);
            hostPort = raw.substring(schemeIndex + 3).trim();
            if ("ssh".equals(scheme)) {
                protocol = GatewayProtocol.SSH;
                port = DEFAULT_SSH_PORT;
            } else if ("telnet".equals(scheme)) {
                protocol = GatewayProtocol.TELNET;
                port = DEFAULT_TELNET_PORT;
            }
        }

        String host = hostPort;
        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex > 0 && hostPort.indexOf(':') == colonIndex) {
            String maybePort = hostPort.substring(colonIndex + 1).trim();
            if (maybePort.matches("\\d+")) {
                try {
                    port = Integer.parseInt(maybePort);
                    host = hostPort.substring(0, colonIndex).trim();
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return new GatewayEndpoint(protocol, host, port, raw);
    }

    private static List<GatewayEndpoint> buildGatewayCandidates(GatewayEndpoint requestedEndpoint) {
        List<GatewayEndpoint> candidates = new ArrayList<>();
        if (requestedEndpoint == null || requestedEndpoint.host.isEmpty()) {
            return candidates;
        }

        if (requestedEndpoint.protocol != GatewayProtocol.AUTO) {
            candidates.add(requestedEndpoint);
            return candidates;
        }

        if (requestedEndpoint.port > 0) {
            candidates.add(new GatewayEndpoint(GatewayProtocol.SSH, requestedEndpoint.host, requestedEndpoint.port, requestedEndpoint.rawValue));
            candidates.add(new GatewayEndpoint(GatewayProtocol.TELNET, requestedEndpoint.host, requestedEndpoint.port, requestedEndpoint.rawValue));
            return candidates;
        }

        candidates.add(new GatewayEndpoint(GatewayProtocol.SSH, requestedEndpoint.host, DEFAULT_SSH_PORT, requestedEndpoint.rawValue));
        candidates.add(new GatewayEndpoint(GatewayProtocol.TELNET, requestedEndpoint.host, DEFAULT_TELNET_PORT, requestedEndpoint.rawValue));
        return candidates;
    }

    private static String summarizeGatewayAttemptError(Exception e) {
        if (e == null) {
            return "Unknown error";
        }

        List<String> parts = new ArrayList<>();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 3) {
            String message = safeTrim(current.getMessage());
            if (message.isEmpty()) {
                message = current.getClass().getSimpleName();
            }
            if (!parts.contains(message)) {
                parts.add(message);
            }
            current = current.getCause();
            depth++;
        }

        if (!parts.isEmpty()) {
            return String.join(" <- ", parts);
        }
        return e.getClass().getSimpleName();
    }

    private static String buildGatewayFailureMessage(GatewayEndpoint requestedEndpoint, List<String> errors) {
        String host = requestedEndpoint == null ? "" : safeTrim(requestedEndpoint.host);
        String details = errors == null || errors.isEmpty() ? "No connection attempts were made." : String.join(" | ", errors);
        if (requestedEndpoint != null && requestedEndpoint.protocol == GatewayProtocol.AUTO) {
            return "Gateway auto-detect failed for " + host + ": " + details;
        }
        String target = requestedEndpoint == null ? host : requestedEndpoint.displayTarget();
        return "Gateway connection failed for " + target + ": " + details;
    }

    private static void beginSshTrace() {
        SSH_TRACE_COLLECTOR.set(new SshTraceCollector());
    }

    private static String endSshTrace() {
        SshTraceCollector collector = SSH_TRACE_COLLECTOR.get();
        SSH_TRACE_COLLECTOR.remove();
        return collector == null ? "" : collector.summarize();
    }

    private static String formatSshAttemptError(SshAuthMode authMode, Exception e, String traceSummary) {
        StringBuilder sb = new StringBuilder();
        String summary = summarizeGatewayAttemptError(e);
        sb.append(authMode.label).append(" -> ").append(summary);
        String lower = summary.toLowerCase(Locale.ROOT);
        if (lower.contains("too many authentication failures")) {
            sb.append(" | hint: server may be cutting the session before password auth completes");
        } else if (lower.contains("auth fail")) {
            sb.append(" | hint: server rejected this auth mode");
        }
        if (traceSummary != null && !traceSummary.isEmpty()) {
            sb.append(" | trace: ").append(traceSummary);
        }
        return sb.toString();
    }

    private static void appendResponseChunk(StringBuilder transcript, String response) {
        if (transcript == null || response == null || response.isEmpty()) {
            return;
        }
        transcript.append(response);
    }

    private static boolean containsGatewayMenuPrompt(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase(Locale.ROOT);
        for (String pattern : GATEWAY_READY_PATTERNS) {
            if (low.contains(pattern)) {
                return true;
            }
        }
        return low.contains("ip address [press q");
    }

    private static boolean containsGatewayUsernamePrompt(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase(Locale.ROOT);
        if (low.contains("username:") || low.contains("user name:")) {
            return true;
        }
        if (low.contains("last login:")) {
            return false;
        }
        return low.contains("login:") || low.contains("ogin:");
    }

    private static boolean containsGatewayPasswordPrompt(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase(Locale.ROOT);
        return low.contains("password:") || low.contains("ssword:");
    }

    private static boolean containsGatewayAuthPromptText(String text) {
        return containsGatewayUsernamePrompt(text) || containsGatewayPasswordPrompt(text);
    }

    private static boolean isGatewayConfirmationRetryableMessage(String message) {
        if (message == null) {
            return false;
        }
        String low = message.trim().toLowerCase(Locale.ROOT);
        if (low.isEmpty()) {
            return false;
        }
        if (low.contains("gateway ssh login failed")
                || low.contains("could not connect to the gateway")
                || low.contains("but user_server is empty")
                || low.contains("but pw_server is empty")) {
            return false;
        }
        return low.contains("gateway login could not be confirmed")
                || low.contains("gateway menu did not appear")
                || low.contains("gateway menu or login prompt did not appear")
                || low.contains("gateway menu state could not be determined")
                || low.contains("gateway menu state could not be confirmed")
                || (low.contains("shell prompt") && low.contains("before gateway menu"))
                || low.contains("unexpected gateway login state after ssh login");
    }

    private static String readSshBootstrapResponse(InputStream input, PrintStream output,
            int timeoutMs, String... patterns) {
        if (input == null) {
            return "[TIMEOUT-READ]";
        }

        StringBuilder sb = new StringBuilder(4096);
        StringBuilder lowerTail = new StringBuilder(256);
        String[] safePatterns = patterns == null ? new String[0] : patterns;
        String[] normalizedPatterns = normalizePatterns(safePatterns);
        long lastData = System.currentTimeMillis();
        final int pollDelayMs = 10;

        try {
            while (true) {
                if (input.available() <= 0) {
                    if (System.currentTimeMillis() - lastData > timeoutMs) {
                        return "[TIMEOUT-READ]";
                    }
                    sleepQuietly(pollDelayMs);
                    continue;
                }

                int c = input.read();
                if (c == -1) {
                    break;
                }

                char ch = (char) c;
                sb.append(ch);
                appendLowerTail(lowerTail, ch, READ_MATCH_WINDOW_CHARS);
                lastData = System.currentTimeMillis();

                if (shouldInspectReadBuffer(ch, sb.length())) {
                    if (containsPaginationMarker(lowerTail)) {
                        sendPaginationSpace(output, lowerTail);
                    }

                    if (containsGatewayAuthPromptText(sb.toString())) {
                        return sb.toString();
                    }

                    for (String normalizedPattern : normalizedPatterns) {
                        if (normalizedPattern != null
                                && !normalizedPattern.isEmpty()
                                && lowerTail.indexOf(normalizedPattern) >= 0) {
                            return sb.toString();
                        }
                    }
                }

                if (sb.length() > 12000) {
                    sb.delete(0, sb.length() - 8000);
                }
            }
        } catch (Exception e) {
            return "[TIMEOUT-READ]";
        }

        return sb.toString();
    }

    private static boolean containsPreLoginReadyPrompt(CharSequence text) {
        if (text == null) {
            return false;
        }
        String promptToken = extractPromptToken(text);
        if (promptToken == null || promptToken.isEmpty()) {
            return false;
        }
        if (promptToken.matches("[#>\\]]+")) {
            return false;
        }
        return promptToken != null
                && promptToken.length() > 1
                && hasInteractivePromptToken(text.toString());
    }

    private static SshConnectionHandles openSshGatewayAttempt(GatewayEndpoint endpoint, String userServer,
            String pwServer, SshAuthMode authMode) throws Exception {
        JSch jsch = new JSch();
        try {
            jsch.removeAllIdentity();
        } catch (JSchException ignore) {
        }

        Session session = jsch.getSession(userServer, endpoint.host, endpoint.port);
        session.setPassword(pwServer);
        session.setUserInfo(new SshPasswordUserInfo(pwServer));

        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", authMode.preferredAuthentications);
        config.put("NumberOfPasswordPrompts", "1");
        config.put("server_host_key", mergeJschConfigValue(JSch.getConfig("server_host_key"), "ssh-rsa"));
        config.put("PubkeyAcceptedAlgorithms", mergeJschConfigValue(JSch.getConfig("PubkeyAcceptedAlgorithms"), "ssh-rsa"));
        session.setConfig(config);
        session.connect(CONNECT_TIMEOUT_MS);

        ChannelShell channel = (ChannelShell) session.openChannel("shell");
        channel.setPty(true);
        channel.setPtyType("vt100");
        InputStream inputStream = channel.getInputStream();
        OutputStream outputStream = channel.getOutputStream();
        channel.connect(CONNECT_TIMEOUT_MS);

        PrintStream printStream = new PrintStream(outputStream, true);
        return new SshConnectionHandles(session, channel, inputStream, printStream);
    }

    public static String extractGatewayHost(String server) {
        GatewayEndpoint endpoint = parseGatewayEndpoint(server);
        return endpoint.host.isEmpty() ? safeTrim(server) : endpoint.host;
    }

    public static final class LoginValidationResult {

        public enum Status {
            SUCCESS,
            INVALID_CREDENTIALS,
            RETRYABLE_FAILURE
        }

        public final Status status;
        public final String message;

        private LoginValidationResult(Status status, String message) {
            this.status = status;
            this.message = message == null ? "" : message.trim();
        }

        public static LoginValidationResult success(String message) {
            return new LoginValidationResult(Status.SUCCESS, message);
        }

        public static LoginValidationResult invalidCredentials(String message) {
            return new LoginValidationResult(Status.INVALID_CREDENTIALS, message);
        }

        public static LoginValidationResult retryableFailure(String message) {
            return new LoginValidationResult(Status.RETRYABLE_FAILURE, message);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean isInvalidCredentials() {
            return status == Status.INVALID_CREDENTIALS;
        }

        public boolean isRetryableFailure() {
            return status == Status.RETRYABLE_FAILURE;
        }

        public boolean isGatewayConfirmationRetryable() {
            return status == Status.RETRYABLE_FAILURE
                    && isGatewayConfirmationRetryableMessage(message);
        }
    }

    public static LoginValidationResult validateCllsCredentials(String server, String userServer, String pwServer,
            String loopback, String userCLLS, String pwCLLS, String cmdSet, String device) {
        try ( CredentialProbe probe = new CredentialProbe()) {
            return probe.validate(server, userServer, pwServer, loopback, userCLLS, pwCLLS, cmdSet, device);
        } catch (Exception e) {
            String target = describeValidationTarget(loopback, device, cmdSet);
            return LoginValidationResult.retryableFailure("Could not validate " + target + ": " + e.getMessage());
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static long getPositiveLongProperty(String propertyName, long defaultValue, long minValue) {
        String configured = safeTrim(System.getProperty(propertyName));
        if (!configured.isEmpty()) {
            try {
                long value = Long.parseLong(configured);
                if (value >= minValue) {
                    return value;
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return defaultValue;
    }

    private static long getCommandMaxWaitMs() {
        long minutes = getPositiveLongProperty("botgetlog.command.maxWait.minutes",
                DEFAULT_COMMAND_MAX_WAIT_MS / (60L * 1000L), 1L);
        return minutes * 60L * 1000L;
    }

    private static long getCommandIdleTimeoutMs() {
        long seconds = getPositiveLongProperty("botgetlog.command.idleTimeout.seconds",
                DEFAULT_COMMAND_IDLE_TIMEOUT_MS / 1000L, 30L);
        return seconds * 1000L;
    }

    private static String formatDurationSeconds(long milliseconds) {
        long seconds = Math.max(1L, milliseconds / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (remainingSeconds == 0L) {
            return minutes + "m";
        }
        return minutes + "m " + remainingSeconds + "s";
    }

    private static String describeValidationTarget(String loopback, String device, String cmdSet) {
        String safeLoopback = safeTrim(loopback);
        String safeDevice = safeTrim(device);
        String safeCmdSet = safeTrim(cmdSet);

        StringBuilder sb = new StringBuilder();
        if (!safeDevice.isEmpty()) {
            sb.append(safeDevice);
        }
        if (!safeLoopback.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('[').append(safeLoopback).append(']');
        }
        if (!safeCmdSet.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('(').append(safeCmdSet).append(')');
        }
        return sb.length() == 0 ? "selected node" : sb.toString();
    }

    private static boolean containsTransportFailureText(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase(Locale.ROOT);
        return low.contains("connection timed out")
                || low.contains("timed out")
                || low.contains("unreachable")
                || low.contains("no route to host")
                || low.contains("host is down")
                || low.contains("connection reset")
                || low.contains("closed by foreign host")
                || low.contains("refused")
                || low.contains("network is unreachable");
    }

    private static boolean containsRemoteSessionClosedText(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase(Locale.ROOT);
        if (containsTransportFailureText(low) || containsGatewayMenuPrompt(low)) {
            return true;
        }
        for (String marker : REMOTE_SESSION_CLOSE_MARKERS) {
            if (low.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String summarizeRemoteSessionCloseReason(String text) {
        if (text == null) {
            return "remote session closed";
        }
        String low = text.toLowerCase(Locale.ROOT);
        if (low.contains("closed by foreign host")) {
            return "closed by foreign host";
        }
        if (low.contains("connection reset")) {
            return "connection reset";
        }
        if (low.contains("connection timed out") || low.contains("timed out")) {
            return "connection timed out";
        }
        if (low.contains("script done")) {
            return "script done";
        }
        if (low.contains("enter ip address [press q")) {
            return "gateway menu returned";
        }
        if (low.contains("logout")) {
            return "logout";
        }
        if (low.contains("unreachable")) {
            return "unreachable";
        }
        return "remote session closed";
    }

    private static boolean isTimeoutResponse(String text) {
        if (text == null) {
            return true;
        }
        return text.contains("[TIMEOUT-READ]")
                || text.contains("[TIMEOUT]")
                || text.contains("[TIMEOUT-READ-LIMIT]")
                || text.contains("[TIMEOUT-Checklogin]");
    }

    private static final class CredentialProbe implements AutoCloseable {

        private final TelnetClient telnet = new TelnetClient();
        private Session sshSession;
        private ChannelShell sshChannel;
        private InputStream in;
        private PrintStream out;
        private String lastPromptToken = "";
        private GatewayEndpoint gatewayEndpoint;

        private LoginValidationResult validate(String server, String userServer, String pwServer,
                String loopback, String userCLLS, String pwCLLS, String cmdSet, String device) {

            String safeUserServer = safeTrim(userServer);
            String safePwServer = safeTrim(pwServer);
            String safeLoopback = safeTrim(loopback);
            String safeUserCLLS = safeTrim(userCLLS);
            String safePwCLLS = safeTrim(pwCLLS);
            String safeCmdSet = safeTrim(cmdSet);
            String target = describeValidationTarget(loopback, device, cmdSet);

            try {
                String gatewayReady = connectGateway(server, safeUserServer, safePwServer, target);
                if (isTimeoutResponse(gatewayReady)
                        || (containsAuthPromptText(gatewayReady) && gatewayReady.toLowerCase(Locale.ROOT).indexOf("enter ip address") < 0)) {
                    return LoginValidationResult.retryableFailure("Gateway login could not be confirmed while validating " + target + ".");
                }

                write(safeLoopback);
                String preLoginOut = readPreLoginBanner(12000);
                if (containsTransportFailureText(preLoginOut)) {
                    return LoginValidationResult.retryableFailure("Node did not answer login on " + target + ".");
                }

                boolean preLoginNokia = preLoginOut != null && preLoginOut.toLowerCase(Locale.ROOT).contains("ogin:");
                String fallbackVendor = extractVendorPrefix(safeCmdSet);
                String detectedVendor = preLoginNokia ? fallbackVendor : detectVendorFromText(preLoginOut, fallbackVendor);
                String effectiveCmdSet = safeCmdSet;
                int dashIndex = safeCmdSet.indexOf('-');
                if (dashIndex > 0 && detectedVendor != null && !detectedVendor.isEmpty()) {
                    effectiveCmdSet = detectedVendor + safeCmdSet.substring(dashIndex);
                }

                if (effectiveCmdSet.isEmpty()) {
                    effectiveCmdSet = "HW";
                }

                char family = Character.toUpperCase(effectiveCmdSet.charAt(0));
                if (!(family == 'H' || family == 'N' || family == 'Z' || family == 'J')) {
                    return LoginValidationResult.retryableFailure("Immediate login validation is not supported for " + target + ".");
                }

                char loginVendorFamily = preLoginNokia ? 'N' : (family == 'N' ? 'H' : family);
                sleepQuietly(randomDelayMs(
                        LOGIN_PROMPT_DELAY_BASE_MS,
                        LOGIN_PROMPT_DELAY_JITTER_MS));

                String usernamePrompt = containsAuthPromptText(preLoginOut)
                        ? preLoginOut
                        : ((loginVendorFamily == 'H' || loginVendorFamily == 'Z')
                        ? readUntil("Username:")
                        : readUntil("ogin:"));

                if (isTimeoutResponse(usernamePrompt) || !containsAuthPromptText(usernamePrompt)) {
                    return LoginValidationResult.retryableFailure("Login prompt did not appear on " + target + ".");
                }
                if (containsLoginFailureText(usernamePrompt)) {
                    return LoginValidationResult.invalidCredentials("Username or password was rejected on " + target + ".");
                }

                String usernamePromptLow = usernamePrompt.toLowerCase(Locale.ROOT);
                boolean waitingForPassword = usernamePromptLow.indexOf("password:") >= 0
                        || usernamePromptLow.indexOf("ssword:") >= 0;
                if (!waitingForPassword) {
                    writeNoShow(safeUserCLLS);
                }

                String passwordPrompt = waitingForPassword
                        ? usernamePrompt
                        : readUntil("ssword:");
                if (containsLoginFailureText(passwordPrompt)) {
                    return LoginValidationResult.invalidCredentials("Username or password was rejected on " + target + ".");
                }
                if (isTimeoutResponse(passwordPrompt)) {
                    return LoginValidationResult.retryableFailure("Password prompt did not appear on " + target + ".");
                }

                String passwordPromptLow = passwordPrompt.toLowerCase(Locale.ROOT);
                if (containsAuthPromptText(passwordPrompt)
                        && passwordPromptLow.indexOf("password:") < 0
                        && passwordPromptLow.indexOf("ssword:") < 0) {
                    return LoginValidationResult.invalidCredentials("Username or password was rejected on " + target + ".");
                }

                writeNoShow(safePwCLLS);
                String postPassword = (family == 'J')
                        ? readUntilAny(">", "#")
                        : waitForPostPasswordPrompt(safeUserCLLS, safePwCLLS);

                if (postPassword == null || postPassword.isEmpty()) {
                    return LoginValidationResult.retryableFailure("No response after password on " + target + ".");
                }
                if (containsLoginFailureText(postPassword)
                        || (containsAuthPromptText(postPassword) && !hasInteractivePromptToken(postPassword))) {
                    return LoginValidationResult.invalidCredentials("Username or password was rejected on " + target + ".");
                }
                if (isTimeoutResponse(postPassword) || containsTransportFailureText(postPassword)) {
                    return LoginValidationResult.retryableFailure("No usable response after password on " + target + ".");
                }
                if (!hasInteractivePromptToken(postPassword)) {
                    return LoginValidationResult.retryableFailure("Login could not be confirmed on " + target + ".");
                }

                return LoginValidationResult.success("Validated login on " + target + ".");
            } catch (ConnectException e) {
                return LoginValidationResult.retryableFailure("Could not connect to the gateway while validating " + target + ".");
            } catch (JSchException e) {
                return LoginValidationResult.retryableFailure("Gateway SSH login failed while validating "
                        + target + ": " + summarizeGatewayAttemptError(e));
            } catch (java.net.SocketTimeoutException e) {
                return LoginValidationResult.retryableFailure("Timed out while validating " + target + ".");
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = e.toString();
                }
                return LoginValidationResult.retryableFailure("Validation error on " + target + ": " + message);
            }
        }

        private String connectGateway(String server, String userServer, String pwServer, String target) throws Exception {
            GatewayEndpoint requestedEndpoint = parseGatewayEndpoint(server);
            List<GatewayEndpoint> candidates = buildGatewayCandidates(requestedEndpoint);
            List<String> errors = new ArrayList<>();

            for (GatewayEndpoint candidate : candidates) {
                try {
                    String gatewayReady;
                    if (candidate.protocol == GatewayProtocol.SSH) {
                        gatewayReady = connectSshGateway(candidate, userServer, pwServer, target);
                    } else {
                        gatewayReady = connectTelnetGateway(candidate, userServer, pwServer, target);
                    }
                    gatewayEndpoint = candidate;
                    return gatewayReady;
                } catch (Exception e) {
                    errors.add(candidate.displayTarget() + " -> " + summarizeGatewayAttemptError(e));
                    resetGatewayConnection();
                }
            }

            throw new IOException(buildGatewayFailureMessage(requestedEndpoint, errors));
        }

        private String connectTelnetGateway(GatewayEndpoint endpoint, String userServer, String pwServer, String target) throws Exception {
            telnet.setConnectTimeout(CONNECT_TIMEOUT_MS);
            telnet.connect(endpoint.host, endpoint.port);
            telnet.setSoTimeout(SOCKET_TIMEOUT_MS);
            in = telnet.getInputStream();
            out = new PrintStream(telnet.getOutputStream(), true);

            String loginPrompt = readUntil("login: ");
            if (isTimeoutResponse(loginPrompt)) {
                loginPrompt = readUntil("login: ");
            }
            if (isTimeoutResponse(loginPrompt)) {
                throw new IOException("Gateway login prompt did not appear for " + target + ".");
            }

            writeNoShow(userServer);
            String gatewayPasswordPrompt = readUntil("Password: ");
            if (isTimeoutResponse(gatewayPasswordPrompt)) {
                throw new IOException("Gateway password prompt did not appear for " + target + ".");
            }

            writeNoShow(pwServer);
            return readUntilPromptOnly(GATEWAY_READY_PATTERNS);
        }

        private String connectSshGateway(GatewayEndpoint endpoint, String userServer, String pwServer, String target) throws Exception {
            List<String> authErrors = new ArrayList<>();
            Exception lastFailure = null;

            for (SshAuthMode authMode : SSH_AUTH_MODES) {
                beginSshTrace();
                try {
                    SshConnectionHandles handles = openSshGatewayAttempt(endpoint, userServer, pwServer, authMode);
                    sshSession = handles.session;
                    sshChannel = handles.channel;
                    in = handles.inputStream;
                    out = handles.outputStream;

                    if (in == null || out == null) {
                        throw new IOException("SSH shell streams not initialized for " + endpoint.displayTarget());
                    }

                    String gatewayReady = waitForSshGatewayMenu(target, userServer, pwServer);
                    String traceSummary = endSshTrace();
                    return gatewayReady;
                } catch (Exception e) {
                    String traceSummary = endSshTrace();
                    authErrors.add(formatSshAttemptError(authMode, e, traceSummary));
                    lastFailure = e;
                    resetGatewayConnection();
                }
            }

            throw new JSchException("SSH auth failed for " + endpoint.displayTarget() + ": "
                    + String.join(" | ", authErrors), lastFailure);
        }

        private String waitForSshGatewayMenu(String target, String userServer, String pwServer) throws Exception {
            StringBuilder transcript = new StringBuilder();
            String firstResponse = "";

            for (int attempt = 0; attempt < 2; attempt++) {
                writeNoShow("");
                String response = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS,
                        GATEWAY_READY_PATTERNS);
                appendResponseChunk(transcript, response);
                if (response != null && !response.isEmpty() && !isTimeoutResponse(response)) {
                    firstResponse = response;
                    break;
                }
            }

            if (firstResponse == null || firstResponse.isEmpty() || isTimeoutResponse(firstResponse)) {
                throw new IOException("Gateway menu or login prompt did not appear after SSH login for " + target + ".");
            }

            if (containsGatewayMenuPrompt(firstResponse)) {
                return transcript.toString();
            }

            if (containsLoginFailureText(firstResponse)) {
                throw new IOException("Gateway username or password was rejected after SSH login for " + target + "."
                        + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
            }

            if (containsGatewayAuthPromptText(firstResponse)) {
                return finishSshGatewayAuthPrompt(target, userServer, pwServer, transcript, firstResponse);
            }

            if (hasInteractivePromptToken(firstResponse)) {
                String promptToken = extractPromptToken(firstResponse);
                throw new IOException("SSH session reached shell prompt " + promptToken
                        + " before gateway menu for " + target + "."
                        + " A shell-side command may be required after SSH login."
                        + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
            }

            throw new IOException("Gateway menu state could not be determined after SSH login for " + target + "."
                    + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
        }

        private String finishSshGatewayAuthPrompt(String target, String userServer, String pwServer,
                StringBuilder transcript, String firstResponse) throws Exception {
            String low = firstResponse == null ? "" : firstResponse.toLowerCase(Locale.ROOT);

            if (low.contains("login incorrect") || containsGatewayUsernamePrompt(firstResponse)) {
                if (userServer == null || userServer.trim().isEmpty()) {
                    throw new IOException("Gateway asked for username after SSH login for " + target
                            + " but User_server is empty.");
                }
                writeNoShow(userServer);
                String passwordPrompt = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS, "ssword:");
                appendResponseChunk(transcript, passwordPrompt);

                if (containsLoginFailureText(passwordPrompt)) {
                    throw new IOException("Gateway username or password was rejected after SSH username for " + target + "."
                            + " | response: " + summarizeResponseForLog(passwordPrompt, userServer, pwServer));
                }
                if (isTimeoutResponse(passwordPrompt)) {
                    throw new IOException("Gateway password prompt did not appear after SSH username for " + target + ".");
                }

                String passwordPromptLow = passwordPrompt == null ? "" : passwordPrompt.toLowerCase(Locale.ROOT);
                if (containsGatewayAuthPromptText(passwordPrompt)
                        && passwordPromptLow.indexOf("password:") < 0
                        && passwordPromptLow.indexOf("ssword:") < 0) {
                    throw new IOException("Gateway username or password was rejected after SSH username for " + target + "."
                            + " | response: " + summarizeResponseForLog(passwordPrompt, userServer, pwServer));
                }
            } else if (containsGatewayPasswordPrompt(firstResponse)) {
                // Gateway is already waiting for the password, continue below.
            } else {
                throw new IOException("Unexpected gateway login state after SSH login for " + target + "."
                        + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
            }

            if (pwServer == null || pwServer.trim().isEmpty()) {
                throw new IOException("Gateway asked for password after SSH login for " + target
                        + " but PW_server is empty.");
            }

            writeNoShow(pwServer);
            String gatewayReady = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS,
                    GATEWAY_READY_PATTERNS);
            appendResponseChunk(transcript, gatewayReady);

            if (containsGatewayMenuPrompt(gatewayReady)) {
                return transcript.toString();
            }
            if (containsLoginFailureText(gatewayReady)
                    || (containsGatewayAuthPromptText(gatewayReady) && !containsGatewayMenuPrompt(gatewayReady))) {
                throw new IOException("Gateway username or password was rejected after SSH password for " + target + "."
                        + " | response: " + summarizeResponseForLog(gatewayReady, userServer, pwServer));
            }
            if (isTimeoutResponse(gatewayReady)) {
                throw new IOException("Gateway menu did not appear after SSH password for " + target + ".");
            }
            if (hasInteractivePromptToken(gatewayReady)) {
                String promptToken = extractPromptToken(gatewayReady);
                throw new IOException("SSH session reached shell prompt " + promptToken
                        + " before gateway menu for " + target + "."
                        + " A shell-side command may be required after SSH login."
                        + " | response: " + summarizeResponseForLog(gatewayReady, userServer, pwServer));
            }

            throw new IOException("Gateway menu state could not be confirmed after SSH password for " + target + "."
                    + " | response: " + summarizeResponseForLog(gatewayReady, userServer, pwServer));
        }

        private void resetGatewayConnection() {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
            } catch (Exception ignore) {
            }
            out = null;

            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignore) {
            }
            in = null;

            try {
                if (telnet != null && telnet.isConnected()) {
                    telnet.disconnect();
                }
            } catch (Exception ignore) {
            }

            try {
                if (sshChannel != null && sshChannel.isConnected()) {
                    sshChannel.disconnect();
                }
            } catch (Exception ignore) {
            }
            sshChannel = null;

            try {
                if (sshSession != null && sshSession.isConnected()) {
                    sshSession.disconnect();
                }
            } catch (Exception ignore) {
            }
            sshSession = null;
        }

        private void write(String value) {
            if (out == null) {
                return;
            }
            if (sshSession != null && sshSession.isConnected()) {
                out.print(normalizeSshInteractiveInput(value));
            } else {
                out.println(value == null ? "" : value);
            }
            out.flush();
        }

        private void writeNoShow(String value) {
            write(value);
        }

        private String readUntil(String pattern) {
            return readUntilInternal(new String[]{pattern}, true);
        }

        private String readUntilAny(String... patterns) {
            return readUntilInternal(patterns, true);
        }

        private String readUntilPromptOnly(String... patterns) {
            return readUntilInternal(patterns, false);
        }

        private String readUntilInternal(String[] patterns, boolean stopOnAuthPrompt) {
            try {
                StringBuilder sb = new StringBuilder(4096);
                StringBuilder lowerTail = new StringBuilder(256);
                long lastData = System.currentTimeMillis();
                String[] safePatterns = patterns == null ? new String[0] : patterns;
                String[] normalizedPatterns = normalizePatterns(safePatterns);
                while (true) {
                    if (in == null) {
                        break;
                    }
                    if (in.available() <= 0) {
                        if (System.currentTimeMillis() - lastData > SOCKET_TIMEOUT_MS) {
                            return "[TIMEOUT-READ]";
                        }
                        sleepQuietly(25);
                        continue;
                    }

                    int c = in.read();
                    if (c == -1) {
                        break;
                    }
                    char ch = (char) c;
                    sb.append(ch);
                    appendLowerTail(lowerTail, ch, READ_MATCH_WINDOW_CHARS);
                    lastData = System.currentTimeMillis();

                    if (shouldInspectReadBuffer(ch, sb.length())) {
                        String promptToken = extractPromptToken(lowerTail);
                        if (matchesAnyPattern(lowerTail, promptToken, safePatterns, normalizedPatterns)) {
                            String data = sb.toString();
                            lastPromptToken = extractPromptToken(data);
                            return data;
                        }

                        if (containsPaginationMarker(lowerTail)) {
                            sendPaginationSpace(out, lowerTail);
                        }

                        if (stopOnAuthPrompt && containsAny(lowerTail, READ_AUTH_PROMPTS)) {
                            String data = sb.toString();
                            lastPromptToken = extractPromptToken(data);
                            return data;
                        }
                    }

                    if (sb.length() > 12000) {
                        sb.delete(0, sb.length() - 8000);
                    }
                }
            } catch (Exception e) {
                return "[TIMEOUT-READ]";
            }
            return "";
        }

        private String readPreLoginBanner(int totalTimeoutMs) {
            StringBuilder sb = new StringBuilder(4096);
            StringBuilder lowerTail = new StringBuilder(256);
            long start = System.currentTimeMillis();

            try {
                while (System.currentTimeMillis() - start < totalTimeoutMs) {
                    try {
                        if (in == null) {
                            break;
                        }
                        if (in.available() <= 0) {
                            sleepQuietly(25);
                            continue;
                        }

                        int c = in.read();
                        if (c == -1) {
                            break;
                        }

                        char ch = (char) c;
                        sb.append(ch);
                        appendLowerTail(lowerTail, ch, READ_MATCH_WINDOW_CHARS);

                        if (shouldInspectReadBuffer(ch, sb.length())) {
                            if (containsAny(lowerTail, PRELOGIN_STOP_TOKENS)
                                    || containsPreLoginReadyPrompt(sb)) {
                                return sb.toString();
                            }
                        }

                        if (sb.length() > 12000) {
                            sb.delete(0, sb.length() - 8000);
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            return sb.toString();
        }

        private String waitForPostPasswordPrompt(String username, String password) {
            String firstRead = readUntilAny(">", "#", "error:");
            if (firstRead == null || firstRead.isEmpty()) {
                return firstRead;
            }
            if (!containsAuthPromptText(firstRead) || hasInteractivePromptToken(firstRead)) {
                return firstRead;
            }

            String combined = firstRead;
            String low = firstRead.toLowerCase(Locale.ROOT);

            if ((low.contains("login incorrect")
                    || low.contains("username:")
                    || low.contains("user name:")
                    || low.contains("login:")
                    || low.contains("ogin:"))
                    && username != null && !username.trim().isEmpty()) {
                writeNoShow(username);
                String pwPrompt = readUntil("ssword:");
                if (pwPrompt != null) {
                    combined += pwPrompt;
                }
                if (containsAuthPromptText(pwPrompt) && password != null && !password.trim().isEmpty()) {
                    writeNoShow(password);
                }
            } else if (low.contains("password:") && password != null && !password.trim().isEmpty()) {
                writeNoShow(password);
            }

            String secondRead = readUntilAny(">", "#", "error:");
            if (secondRead == null || secondRead.isEmpty()) {
                return combined;
            }
            return combined + secondRead;
        }

        @Override
        public void close() {
            resetGatewayConnection();
        }
    }

    private String connectGateway(String server, String userServer, String pwServer) throws Exception {
        GatewayEndpoint requestedEndpoint = parseGatewayEndpoint(server);
        String connectionInfo = requestedEndpoint.displayTarget();
        logwork("[INFO] Gateway transport request: " + connectionInfo + "\n");
        System.out.println("[INFO] Gateway transport request: " + connectionInfo);

        List<GatewayEndpoint> candidates = buildGatewayCandidates(requestedEndpoint);
        List<String> errors = new ArrayList<>();

        for (GatewayEndpoint candidate : candidates) {
            logwork("[INFO] Gateway attempt: " + candidate.displayTarget() + "\n");
            System.out.println("[INFO] Gateway attempt: " + candidate.displayTarget());
            try {
                String gatewayReady;
                if (candidate.protocol == GatewayProtocol.SSH) {
                    gatewayReady = connectSshGateway(candidate, userServer, pwServer);
                } else {
                    gatewayReady = connectTelnetGateway(candidate, userServer, pwServer);
                }
                gatewayEndpoint = candidate;
                logwork("[INFO] Gateway selected: " + candidate.displayTarget() + "\n");
                System.out.println("[INFO] Gateway selected: " + candidate.displayTarget());
                return gatewayReady;
            } catch (Exception e) {
                String summary = summarizeGatewayAttemptError(e);
                errors.add(candidate.displayTarget() + " -> " + summary);
                logwork("[WARN] Gateway attempt failed: " + candidate.displayTarget() + " -> " + summary + "\n");
                System.out.println("[WARN] Gateway attempt failed: " + candidate.displayTarget() + " -> " + summary);
                resetGatewayConnection();
            }
        }

        throw new IOException(buildGatewayFailureMessage(requestedEndpoint, errors));
    }

    private String connectTelnetGateway(GatewayEndpoint endpoint, String userServer, String pwServer) throws Exception {
        telnet.setConnectTimeout(CONNECT_TIMEOUT_MS);
        telnet.connect(endpoint.host, endpoint.port);
        if (telnet == null || !telnet.isConnected()) {
            throw new ConnectException("Telnet connection not established properly for " + endpoint.displayTarget());
        }

        int baseTimeout = SOCKET_TIMEOUT_MS;
        try {
            telnet.setSoTimeout(baseTimeout);
            logwork("[INFO] Fixed Socket Timeout = " + baseTimeout + " ms\n");
        } catch (Exception e) {
            logwork("[WARN] Failed to set socket timeout: " + e + "\n");
        }

        in = telnet.getInputStream();
        out = new PrintStream(telnet.getOutputStream(), true);
        if (in == null || out == null) {
            throw new IOException("Telnet streams not initialized for " + endpoint.displayTarget());
        }

        String loginPrompt = readUntil("login: ");
        if (isTimeoutResponse(loginPrompt)) {
            logwork("[RETRY] Retrying gateway login prompt for " + endpoint.displayTarget() + "\n");
            loginPrompt = readUntil("login: ");
        }
        if (isTimeoutResponse(loginPrompt)) {
            throw new IOException("Gateway login prompt did not appear for " + endpoint.displayTarget());
        }

        write_NoShow(userServer);
        String passwordPrompt = readUntil("Password: ");
        if (isTimeoutResponse(passwordPrompt)) {
            throw new IOException("Gateway password prompt did not appear for " + endpoint.displayTarget());
        }

        write_NoShow(pwServer);
        return readUntilPromptOnly(GATEWAY_READY_PATTERNS);
    }

    private String connectSshGateway(GatewayEndpoint endpoint, String userServer, String pwServer) throws Exception {
        List<String> authErrors = new ArrayList<>();
        Exception lastFailure = null;

        for (SshAuthMode authMode : SSH_AUTH_MODES) {
            beginSshTrace();
            try {
                SshConnectionHandles handles = openSshGatewayAttempt(endpoint, userServer, pwServer, authMode);
                sshSession = handles.session;
                sshChannel = handles.channel;
                in = handles.inputStream;
                out = handles.outputStream;

                if (in == null || out == null) {
                    throw new IOException("SSH shell streams not initialized for " + endpoint.displayTarget());
                }

                String traceSummary = endSshTrace();
                String authInfo = "[INFO] SSH auth selected: " + authMode.label
                        + (traceSummary.isEmpty() ? "" : " | " + traceSummary);
                logwork(authInfo + "\n");
                System.out.println(authInfo);
                return waitForSshGatewayMenu(endpoint, userServer, pwServer);
            } catch (Exception e) {
                String traceSummary = endSshTrace();
                authErrors.add(formatSshAttemptError(authMode, e, traceSummary));
                lastFailure = e;
                resetGatewayConnection();
            }
        }

        throw new JSchException("SSH auth failed for " + endpoint.displayTarget() + ": "
                + String.join(" | ", authErrors), lastFailure);
    }

    private String waitForSshGatewayMenu(GatewayEndpoint endpoint, String userServer, String pwServer) throws Exception {
        StringBuilder transcript = new StringBuilder();
        String firstResponse = "";

        for (int attempt = 0; attempt < 2; attempt++) {
            write_NoShow("");
            String response = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS,
                    GATEWAY_READY_PATTERNS);
            appendResponseChunk(transcript, response);
            if (response != null && !response.isEmpty() && !isTimeoutResponse(response)) {
                firstResponse = response;
                break;
            }
        }

        if (firstResponse == null || firstResponse.isEmpty() || isTimeoutResponse(firstResponse)) {
            throw new IOException("Gateway menu or login prompt did not appear after SSH login for "
                    + endpoint.displayTarget() + ".");
        }

        if (containsGatewayMenuPrompt(firstResponse)) {
            return transcript.toString();
        }

        if (containsLoginFailureText(firstResponse)) {
            throw new IOException("Gateway username or password was rejected after SSH login for "
                    + endpoint.displayTarget() + "."
                    + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
        }

        if (containsGatewayAuthPromptText(firstResponse)) {
            return finishSshGatewayAuthPrompt(endpoint, userServer, pwServer, transcript, firstResponse);
        }

        if (hasInteractivePromptToken(firstResponse)) {
            String promptToken = extractPromptToken(firstResponse);
            throw new IOException("SSH session reached shell prompt " + promptToken
                    + " before gateway menu for " + endpoint.displayTarget() + "."
                    + " A shell-side command may be required after SSH login."
                    + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
        }

        throw new IOException("Gateway menu state could not be determined after SSH login for "
                + endpoint.displayTarget() + "."
                + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
    }

    private String finishSshGatewayAuthPrompt(GatewayEndpoint endpoint, String userServer, String pwServer,
            StringBuilder transcript, String firstResponse) throws Exception {
        String low = firstResponse == null ? "" : firstResponse.toLowerCase(Locale.ROOT);

        if (low.contains("login incorrect") || containsGatewayUsernamePrompt(firstResponse)) {
            if (userServer == null || userServer.trim().isEmpty()) {
                throw new IOException("Gateway asked for username after SSH login for "
                        + endpoint.displayTarget() + " but User_server is empty.");
            }
            write_NoShow(userServer);
            String passwordPrompt = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS, "ssword:");
            appendResponseChunk(transcript, passwordPrompt);

            if (containsLoginFailureText(passwordPrompt)) {
                throw new IOException("Gateway username or password was rejected after SSH username for "
                        + endpoint.displayTarget() + "."
                        + " | response: " + summarizeResponseForLog(passwordPrompt, userServer, pwServer));
            }
            if (isTimeoutResponse(passwordPrompt)) {
                throw new IOException("Gateway password prompt did not appear after SSH username for "
                        + endpoint.displayTarget() + ".");
            }

            String passwordPromptLow = passwordPrompt == null ? "" : passwordPrompt.toLowerCase(Locale.ROOT);
            if (containsGatewayAuthPromptText(passwordPrompt)
                    && passwordPromptLow.indexOf("password:") < 0
                    && passwordPromptLow.indexOf("ssword:") < 0) {
                throw new IOException("Gateway username or password was rejected after SSH username for "
                        + endpoint.displayTarget() + "."
                        + " | response: " + summarizeResponseForLog(passwordPrompt, userServer, pwServer));
            }
        } else if (containsGatewayPasswordPrompt(firstResponse)) {
            // Gateway is already waiting for the password, continue below.
        } else {
            throw new IOException("Unexpected gateway login state after SSH login for "
                    + endpoint.displayTarget() + "."
                    + " | response: " + summarizeResponseForLog(firstResponse, userServer, pwServer));
        }

        if (pwServer == null || pwServer.trim().isEmpty()) {
            throw new IOException("Gateway asked for password after SSH login for "
                    + endpoint.displayTarget() + " but PW_server is empty.");
        }

        write_NoShow(pwServer);
        String gatewayReady = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS,
                GATEWAY_READY_PATTERNS);
        appendResponseChunk(transcript, gatewayReady);

        if (containsGatewayMenuPrompt(gatewayReady)) {
            return transcript.toString();
        }
        if (containsLoginFailureText(gatewayReady)
                || (containsGatewayAuthPromptText(gatewayReady) && !containsGatewayMenuPrompt(gatewayReady))) {
            throw new IOException("Gateway username or password was rejected after SSH password for "
                    + endpoint.displayTarget() + "."
                    + " | response: " + summarizeResponseForLog(gatewayReady, userServer, pwServer));
        }
        if (isTimeoutResponse(gatewayReady)) {
            throw new IOException("Gateway menu did not appear after SSH password for "
                    + endpoint.displayTarget() + ".");
        }
        if (hasInteractivePromptToken(gatewayReady)) {
            String promptToken = extractPromptToken(gatewayReady);
            throw new IOException("SSH session reached shell prompt " + promptToken
                    + " before gateway menu for " + endpoint.displayTarget() + "."
                    + " A shell-side command may be required after SSH login."
                    + " | response: " + summarizeResponseForLog(gatewayReady, userServer, pwServer));
        }

        throw new IOException("Gateway menu state could not be confirmed after SSH password for "
                + endpoint.displayTarget() + "."
                + " | response: " + summarizeResponseForLog(gatewayReady, userServer, pwServer));
    }

    private void resetGatewayConnection() {
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (Exception ignore) {
        }
        out = null;

        try {
            if (in != null) {
                in.close();
            }
        } catch (Exception ignore) {
        }
        in = null;

        try {
            if (telnet != null && telnet.isConnected()) {
                telnet.disconnect();
            }
        } catch (Exception ignore) {
        }

        try {
            if (sshChannel != null && sshChannel.isConnected()) {
                sshChannel.disconnect();
            }
        } catch (Exception ignore) {
        }
        sshChannel = null;

        try {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.disconnect();
            }
        } catch (Exception ignore) {
        }
        sshSession = null;
    }

    public Telnet_Multi(String server, String User_server, String PW_server, String Loopback, String User_CLLS, String PW_CLLS, String cmdSet, String Device, int Num_row, String User_L2, String PW_L2) {
        //   background monitor  ()
        startWrongVendorMonitor(new PathFile());
        startCommandCompletionMonitor(new PathFile());
        startConnectionFailMonitor(new PathFile());
        this.Loopback = Loopback;   //   IP node
        this.gatewayServerAddress = safeTrim(server);
        this.gatewayUsername = User_server == null ? "" : User_server;
        this.gatewayPassword = PW_server == null ? "" : PW_server;
        this.nodeUsername = User_CLLS == null ? "" : User_CLLS;
        this.nodePassword = PW_CLLS == null ? "" : PW_CLLS;
        this.l2Username = User_L2 == null ? "" : User_L2;
        this.l2Password = PW_L2 == null ? "" : PW_L2;
        this.sessionFailureRecorded = false;
        try {
            //  - Telnet  Semaphore

            //  START log  - Node
            String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logwork("[START] " + Loopback + " (" + Device + ", " + cmdSet + ") at " + startTime + "\n");
            System.out.println("[START] " + Loopback + " (" + Device + ", " + cmdSet + ") at " + startTime);

            String cmd = "";
            String[] command = new String[100];
            int r = reloadCommandsFromExcel(FileInput, cmdSet, command);
            if (r == 0) {
                logwork("[ERROR] No commands found for cmdSet: " + cmdSet + "\n");
                Connection_failed(Num_row, Loopback, Device, cmdSet, "_[CmdSet missing]");
                return;
            }

            File completedEquivalentLog = findEquivalentCompletedLog(Loopback, Device, cmdSet, Num_row);
            if (completedEquivalentLog != null) {
                String skipMsg = String.format("[SKIP] Completed equivalent log already exists for %s (%s) [%s] -> %s",
                        Device, Loopback, cmdSet, completedEquivalentLog.getName());
                System.out.println(skipMsg);
                logwork(skipMsg + "\n");
                return;
            }

            String gatewayReady = connectGateway(server, User_server, PW_server);
            if (gatewayReady != null && !gatewayReady.isEmpty()) {
                LOG.append(gatewayReady);
            }
            writeLoopBack(Loopback);

            // ===================== AUTO Vendor detect (after enter Loopback, before user/pass) =====================
            String preLoginOut = readPreLoginBanner(12000); // 12s  ()
            if (preLoginOut != null && !preLoginOut.isEmpty()) {
                LOG.append(preLoginOut);
            }
            // System.out.println(preLoginOut);
            //  normalize  lower-case  banner 
            String beforeCmdSet = cmdSet;
            boolean preLoginNokia = preLoginOut != null && preLoginOut.toLowerCase().contains("ogin:");
            String fallbackVendor = extractVendorPrefix(beforeCmdSet);
            String vendor = preLoginNokia ? fallbackVendor : detectVendorFromText(preLoginOut, fallbackVendor);
            cmdSet = vendor + beforeCmdSet.substring(beforeCmdSet.indexOf("-"));

            //   Auto Vendor Detect  vendor ( N-PTP -> HW-PTP)  reload command  Excel  cmdSet 
            if (!cmdSet.equalsIgnoreCase(beforeCmdSet)) {
                try {
                    String[] newCommand = new String[100];
                    int newR = reloadCommandsFromExcel(FileInput, cmdSet, newCommand);

                    if (newR > 0) {
                        command = newCommand;
                        r = newR;
                        logwork("[INFO] Auto vendor detect adjusted cmdSet: " + beforeCmdSet + " -> " + cmdSet
                                + " (reloaded commands rows=" + r + ")\n");
                        System.out.println("[INFO] Auto vendor detect adjusted cmdSet: " + beforeCmdSet + " -> " + cmdSet
                                + " (reloaded commands rows=" + r + ")");
                    } else {
                        logwork("[WARN] Auto vendor detect adjusted cmdSet: " + beforeCmdSet + " -> " + cmdSet
                                + " but no cmdSet column found; keep original command list\n");
                        System.out.println("[WARN] Auto vendor detect adjusted cmdSet: " + beforeCmdSet + " -> " + cmdSet
                                + " but no cmdSet column found; keep original command list");
                    }
                } catch (Exception e) {
                    logwork("[WARN] Failed to reload commands after vendor detect: " + e + "\n");
                }
            }

            // ===============================================================================================
            File completedEquivalentLogAfterVendor = findEquivalentCompletedLog(Loopback, Device, cmdSet, Num_row);
            if (completedEquivalentLogAfterVendor != null) {
                String skipMsg = String.format("[SKIP] Completed equivalent log already exists after vendor adjust for %s (%s) [%s] -> %s",
                        Device, Loopback, cmdSet, completedEquivalentLogAfterVendor.getName());
                System.out.println(skipMsg);
                logwork(skipMsg + "\n");
                disconnect();
                return;
            }

            // ===============================================================================================
//   SAM gateway  (Connection timed out)
            if (LOG.toString().toLowerCase().contains("connection timed out")) {
                System.out.println("[ERROR] SAM-BB connection to node timed out: " + Loopback);
                logwork("[ERROR] SAM-BB connection to node timed out: " + Loopback + "\n");
                Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed]");
                disconnect();
                return; //   constructor --
            }

            while (true) {
                char loginVendorFamily = preLoginNokia ? 'N' : (cmdSet.charAt(0) == 'N' ? 'H' : cmdSet.charAt(0));
                boolean sshNodeLoginFlow = isSshGatewayConnection()
                        && (cmdSet.charAt(0) == 'H' || cmdSet.charAt(0) == 'N' || cmdSet.charAt(0) == 'Z');

                if (sshNodeLoginFlow) {
                    String resp = completeSshNodeLogin(Loopback, Device, cmdSet, preLoginOut, User_CLLS, PW_CLLS);
                    appendLoginTranscriptDelta(LOG, preLoginOut, resp);
                    if (resp == null || resp.isEmpty()
                            || resp.contains("[TIMEOUT-READ]")
                            || resp.contains("[TIMEOUT]")
                            || !hasNodeLoginSignal(resp)
                            || containsTransportFailureText(resp)) {
                        System.out.println("[ERROR] SSH node login did not return a usable response at " + Loopback);
                        logwork("[ERROR] SSH node login did not return a usable response at " + Loopback
                                + " | response: " + summarizeResponseForLog(resp, User_CLLS, PW_CLLS) + "\n");
                        Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed after password]");
                        failedAfterPassword = true;
                        disconnect();
                        return;
                    }
                    if (containsLoginFailureText(resp)
                            || (containsAuthPromptText(resp) && !hasInteractivePromptToken(resp))) {
                        System.out.println("\n[ERROR] Username or password rejected at " + Loopback);
                        logwork("\n[ERROR] Username or password rejected at " + Loopback
                                + " | response: " + summarizeResponseForLog(resp, User_CLLS, PW_CLLS) + "\n");
                        Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Auth failed - username or password rejected]");
                        failedAfterPassword = true;
                        disconnect();
                        return;
                    }

                    String detectedVendorAfterPassword = detectVendorFromPrompt(resp, extractVendorPrefix(cmdSet), preLoginNokia);
                    if (cmdSet.indexOf("-") > 0) {
                        String adjustedCmdSet = detectedVendorAfterPassword + cmdSet.substring(cmdSet.indexOf("-"));
                        if (!adjustedCmdSet.equalsIgnoreCase(cmdSet)) {
                            int newR = reloadCommandsFromExcel(FileInput, adjustedCmdSet, command);
                            if (newR > 0) {
                                logwork("[INFO] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                        + " (reloaded commands rows=" + newR + ")\n");
                                System.out.println("[INFO] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                        + " (reloaded commands rows=" + newR + ")");
                                cmdSet = adjustedCmdSet;
                                r = newR;
                            } else {
                                logwork("[WARN] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                        + " but no cmdSet column found; keep original command list\n");
                                System.out.println("[WARN] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                        + " but no cmdSet column found; keep original command list");
                                cmdSet = adjustedCmdSet;
                            }
                        }
                    }

                    updateRuntimeDeviceNameFromResponse(resp, Device, cmdSet);

                    if (!checkEnvCommand(cmdSet, Loopback, Device, Num_row)) {
                        return;
                    }
                } else if (loginVendorFamily == 'H' || loginVendorFamily == 'Z') {
                    sleepQuietly(randomDelayMs(
                            LOGIN_PROMPT_DELAY_BASE_MS,
                            LOGIN_PROMPT_DELAY_JITTER_MS));

                    if (!checkVendorLoginPrompt("Username:", "ogin:", "H/Z", Loopback, Device, cmdSet, Num_row, LOG)) {
                        return;
                    }
                    write_NoShow(User_CLLS);
                } else if (loginVendorFamily == 'N') {
                    sleepQuietly(randomDelayMs(
                            LOGIN_PROMPT_DELAY_BASE_MS,
                            LOGIN_PROMPT_DELAY_JITTER_MS));

                    if (!checkVendorLoginPrompt("ogin:", "Username:", "N", Loopback, Device, cmdSet, Num_row, LOG)) {
                        return;
                    }
                    write_NoShow(User_CLLS);
                } else if (cmdSet.charAt(0) == 'J') {
                    LOG.append(Checklogin("ogin:"));
                    if (LOG.toString().contains("Login_failed")) {
                        Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed]");
                        break;
                    }
                    write_NoShow(User_CLLS);
                } else if (cmdSet.charAt(0) == 'O') {
                    LOG.append(Checklogin(":"));
                    if (LOG.toString().contains("Login_failed")) {
                        Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed]");
                        break;
                    }
                    write_NoShow(User_CLLS);
                } else if (cmdSet.charAt(0) == 'L') {
                    LOG.append(Checklogin("Username:"));
                    if (LOG.toString().contains("Login_failed")) {
                        Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed]");
                        break;
                    }
                    write_NoShow(User_L2);
                }
                if (!sshNodeLoginFlow) {
                    if (cmdSet.charAt(0) == 'L') {
                        LOG.append(readUntil("ssword:"));
                        write_NoShow(PW_L2);
                    } else if (cmdSet.charAt(0) == 'O') {
                        LOG.append(readUntil(":"));
                        write_NoShow(PW_CLLS);
                    } else {
                        LOG.append(readUntil("ssword:"));
                        write_NoShow(PW_CLLS);
                    }
                    if (cmdSet.charAt(0) == 'J') {
                        String resp = readUntil(">");
                        LOG.append(resp);
                        if (resp == null
                                || resp.contains("[TIMEOUT-READ]")
                                || resp.contains("[TIMEOUT]")
                                || resp.contains("[TIMEOUT-READ-LIMIT]")) {

                            System.out.println("\n[ERROR] Login prompt detected but no response after password at " + Loopback);
                            logwork("\n[ERROR] Login prompt detected but no response after password at " + Loopback + "\n");

                            //  - Connection Failed
                            Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed - Timeout Limit]");
                            failedAfterPassword = true;
                            disconnect();
                            return;
                        }

                        write("");
                    } else if (cmdSet.charAt(0) == 'H' || cmdSet.charAt(0) == 'N' || cmdSet.charAt(0) == 'Z') {
                        //   timeout  password ( Password:[TIMEOUT-READ])
                        if (isTimeoutLog()) {
                            System.out.println("[ERROR] No response after password at " + Loopback);
                            logwork("[ERROR] No response after password at " + Loopback + "\n");
                            Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed - no response after password]");
                            failedAfterPassword = true;
                            disconnect();
                            return;
                        }

                        String resp = waitForPostPasswordPrompt(Loopback, User_CLLS, PW_CLLS);
                        LOG.append(resp);
                        if (resp == null
                                || resp.contains("[TIMEOUT-READ]")
                                || resp.contains("[TIMEOUT]")
                                || containsLoginFailureText(resp)
                                || (containsAuthPromptText(resp) && !hasInteractivePromptToken(resp))) {
                            boolean invalidCredentials = containsLoginFailureText(resp)
                                    || (containsAuthPromptText(resp) && !hasInteractivePromptToken(resp));
                            if (invalidCredentials) {
                                System.out.println("\n[ERROR] Username or password rejected at " + Loopback);
                                logwork("\n[ERROR] Username or password rejected at " + Loopback
                                        + " | response: " + summarizeResponseForLog(resp, User_CLLS, PW_CLLS) + "\n");
                            } else {
                                System.out.println("\n[ERROR] Login prompt detected but no response after password at " + Loopback);
                                logwork("\n[ERROR] Login prompt detected but no response after password at " + Loopback + "\n");
                            }
                            Connection_failed(Num_row, Loopback, Device, cmdSet,
                                    invalidCredentials
                                            ? "_[Auth failed - username or password rejected]"
                                            : "_[Connection failed after password]");
                            failedAfterPassword = true;
                            disconnect();
                            return; //   constructor --  save log,  END
                        }

                        String detectedVendorAfterPassword = detectVendorFromPrompt(resp, extractVendorPrefix(cmdSet), preLoginNokia);
                        if (cmdSet.indexOf("-") > 0) {
                            String adjustedCmdSet = detectedVendorAfterPassword + cmdSet.substring(cmdSet.indexOf("-"));
                            if (!adjustedCmdSet.equalsIgnoreCase(cmdSet)) {
                                int newR = reloadCommandsFromExcel(FileInput, adjustedCmdSet, command);
                                if (newR > 0) {
                                    logwork("[INFO] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                            + " (reloaded commands rows=" + newR + ")\n");
                                    System.out.println("[INFO] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                            + " (reloaded commands rows=" + newR + ")");
                                    cmdSet = adjustedCmdSet;
                                    r = newR;
                                } else {
                                    logwork("[WARN] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                            + " but no cmdSet column found; keep original command list\n");
                                    System.out.println("[WARN] Prompt vendor detect adjusted cmdSet: " + cmdSet + " -> " + adjustedCmdSet
                                            + " but no cmdSet column found; keep original command list");
                                    cmdSet = adjustedCmdSet;
                                }
                            }
                        }

                        updateRuntimeDeviceNameFromResponse(resp, Device, cmdSet);

                        if (!checkEnvCommand(cmdSet, Loopback, Device, Num_row)) {
                            return;
                        }
                        write("");
                    } else if (cmdSet.charAt(0) == 'O') {
                        LOG.append(readUntil(">"));
                        write("enable");
                        LOG.append(readUntil("#"));
                        write("configure terminal");
                        LOG.append(readUntil("#"));
                        write("");
                    } else if (cmdSet.charAt(0) == 'L') {
                        LOG.append(readUntil(">"));
                        write("system-view");
                        readUntil("]");
                        write("user-interface vty 0 4");
                        readUntil("]");
                        write("screen-length 0");
                        readUntil("]");
                        write("q");
                        readUntil("]");
                        write("q");
                        readUntil(">");
                        write("");
                    }
                }
                if (cmdSet.charAt(0) == 'N') {
                    sleepQuietly(randomDelayMs(
                            NOKIA_POST_LOGIN_DELAY_BASE_MS,
                            NOKIA_POST_LOGIN_DELAY_JITTER_MS));
                }

                //
                for (int i = 1; i < r; i++) {
                    if (cmdSet.charAt(0) == 'N' && i > 1) {
                        sleepQuietly(randomDelayMs(
                                NOKIA_BETWEEN_COMMAND_DELAY_BASE_MS,
                                NOKIA_BETWEEN_COMMAND_DELAY_JITTER_MS));
                    }

                    System.out.println("[CMD]" + buildConsoleNodePrefix(Num_row, Loopback, Device)
                            + " " + summarizeCommandForConsole(command[i]));
                    //   log 
                    if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, command[i])) {
                        return;
                    }

                    //  - (r-2)  N-OPTICAL_LLDP  N-LLDP-Link_OPTIC
                    if (i == r - 2 && (cmdSet.equals("N-OPTICAL_LLDP") || cmdSet.equals("N-LLDP-Link_OPTIC") || cmdSet.equals("N-OPTICAL") || cmdSet.equals("N-LLDP"))) {
                        try {
                            Thread.sleep(300); //  log 

                            //   log 
                            String fileName = buildDailyLogFileName(Loopback, Device, cmdSet, Num_row);
                            File logFile = new File(FileInput.getLog(), fileName);

                            if (!logFile.exists()) {
                                System.out.println("[PORT-SCAN] Log file not found: " + logFile.getAbsolutePath());
                                continue;
                            }

                            //  - show port
                            StringBuilder sb = new StringBuilder();
                            boolean startCapture = false;

                            try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    //  show port
                                    if (line.toLowerCase().contains("show port")) {
                                        startCapture = true;
                                        continue;
                                    }
                                    if (startCapture) {
                                        sb.append(line).append("\n");
                                    }
                                }
                            }

                            String portSection = sb.toString();

                            //   (Port ID  1/1/5)
                            //  -- port ( 1/1/1, 2/1/c1, 2/1/c1/1)
                            Pattern portPattern = Pattern.compile("^\\s*(\\d+/\\d+/(?:[a-zA-Z]\\d+|\\d+)(?:/\\d+)?)\\b", Pattern.MULTILINE);

                            Matcher matcher = portPattern.matcher(portSection);

                            LinkedHashSet<String> uniquePorts = new LinkedHashSet<>();
                            while (matcher.find()) {
                                uniquePorts.add(matcher.group(1).trim());
                            }

                            if (uniquePorts.isEmpty()) {
                                System.out.println("[PORT-SCAN] No ports found after show port section.");
                                continue;
                            }

                            System.out.println("[PORT-SCAN] Found " + uniquePorts.size() + " ports -> sending LLDP commands...");

                            //   show port <port> -
                            for (String port : uniquePorts) {
                                String cmd1 = "show port " + port + " ethernet lldp remote-info";
                                System.out.println("[PORT CMD] " + cmd1);
                                if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, cmd1)) {
                                    return;
                                }

                                String cmd2 = "show port " + port;
                                System.out.println("[PORT CMD] " + cmd2);
                                if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, cmd2)) {
                                    return;
                                }
                            }

                        } catch (Exception e) {
                            System.out.println("[ERROR] Port-scan phase failed: " + e.getMessage());
                        }
                    } else if (i == r - 2 && (cmdSet.equals("HW-Config_PORT_Reserved"))) {
                        try {
                            Thread.sleep(300);

                            String fileName = buildDailyLogFileName(Loopback, Device, cmdSet, Num_row);
                            File logFile = new File(FileInput.getLog(), fileName);

                            if (!logFile.exists()) {
                                System.out.println("[PORT-CONFIG] Log file not found: " + logFile.getAbsolutePath());
                                continue;
                            }

                            //   interface  log
                            StringBuilder section = new StringBuilder();
                            boolean inInterface = false;
                            try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    if (line.contains("Interface                     PHY     Protocol Description")) {
                                        inInterface = true;
                                        continue;
                                    }
                                    if (line.trim().startsWith(">quit")) {
                                        break;
                                    }
                                    if (inInterface) {
                                        section.append(line).append("\n");
                                    }
                                }
                            }
                            String content = section.toString();

                            // === 1  10G - undo ===
                            // === 1  10G - undo ===
                            Pattern freePattern = Pattern.compile(
                                    "^(?:GE|10GE|XGE)\\S*\\(10G\\)\\s+\\*?down\\s+down\\s*$",
                                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

                            List<String> beforeFreePorts = new ArrayList<>();
                            Matcher mBefore = freePattern.matcher(content);
                            while (mBefore.find()) {
                                String line = mBefore.group(0).trim();

                                //   (Interface / PHY / Protocol / Description?)
                                String[] cols = line.split("\\s+");

                                //   3  ( description -)
                                if (cols.length <= 3) {
                                    String port = cols[0].trim();
                                    if (!port.contains(".")) {
                                        beforeFreePorts.add(port);
                                    }
                                }
                            }

                            // === 2  description - Reserved_For_Rehoming  Reserved_For_OLT ===
                            Pattern reservedPattern = Pattern.compile(
                                    "^(?<port>(?:10GE|GE)\\S*\\(10G\\))\\s+\\S+\\s+\\S+.*(Reserved[_ ]For[_ ]Rehoming|Reserved[_ ]For[_ ]OLT).*",
                                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                            Matcher mOld = reservedPattern.matcher(content);
                            List<String> afterUndoPorts = new ArrayList<>();
                            if (!content.isEmpty()) {
                                boolean hasUndo = false;

                                List<String> undoBatch = new ArrayList<>();
                                int batchSize = 5;

                                while (mOld.find()) {
                                    String port = mOld.group("port").trim();
                                    if (port.contains(".")) {
                                        System.out.println("[SKIP] Skip port with '.' -> " + port);
                                        continue;
                                    }
                                    undoBatch.add(port);

                                    //   5 port  port -   block command 
                                    if (undoBatch.size() >= batchSize) {
                                        if (!sendUndoBatch(this, Loopback, Device, cmdSet, Num_row, undoBatch, afterUndoPorts)) {
                                            return;
                                        }

                                        undoBatch.clear();
                                    }
                                }

//  - 5
                                if (!undoBatch.isEmpty()) {
                                    if (!sendUndoBatch(this, Loopback, Device, cmdSet, Num_row, undoBatch, afterUndoPorts)) {
                                        return;
                                    }

                                }

                                if (hasUndo) {
                                    if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, "commit")) {
                                        return;
                                    }
                                    if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, "quit")) {
                                        return;
                                    }
                                }
                            }

                            //  --
                            if (afterUndoPorts.isEmpty()) {
                                System.out.println("[INFO]  No ports were undone  Proceeding to reserve ports directly.");
                                //    
                                afterUndoPorts.addAll(beforeFreePorts);
                            } else {
                                System.out.println("\n=============================");
                                System.out.println("[SUMMARY] Ports removed:");
                                afterUndoPorts.forEach(p -> System.out.println(" - " + p));
                                System.out.println("=============================\n");
                            }

                            // === 3  undo ===
                            Set<String> allFreeSet = new LinkedHashSet<>(beforeFreePorts);
                            allFreeSet.addAll(afterUndoPorts);

                            //   port - "." 
                            List<String> allFreePorts = allFreeSet.stream()
                                    .filter(p -> !p.contains("."))
                                    .collect(Collectors.toList());

                            if (allFreePorts.isEmpty()) {
                                System.out.println("[WARN] No free ports found!");
                                return;
                            }

                            // === 4  card/slot/port ===
                            Comparator<String> portComparator = Comparator.comparingInt(p -> {
                                Matcher m = Pattern.compile("(\\d+)/(\\d+)/(\\d+)").matcher(p);
                                if (m.find()) {
                                    int card = Integer.parseInt(m.group(1));
                                    int slot = Integer.parseInt(m.group(2));
                                    int portNo = Integer.parseInt(m.group(3));
                                    return card * 10000 + slot * 100 + portNo;
                                }
                                return Integer.MAX_VALUE;
                            });
                            allFreePorts.sort(portComparator);

                            System.out.println("[INFO] Sorted free ports -> " + allFreePorts);

// === 5 - Rehoming / OLT ===
                            int totalFree = allFreePorts.size();
                            int rehomingCount;
                            int oltCount;

//   15 port
                            final int TARGET_TOTAL = 15;
                            final int TARGET_REHOMING = 10;
                            final int TARGET_OLT = 5;

                            if (totalFree >= TARGET_TOTAL) {
                                rehomingCount = TARGET_REHOMING;
                                oltCount = TARGET_OLT;
                            } else {
                                //  15   2:1
                                rehomingCount = (int) Math.round(totalFree * (2.0 / 3.0));
                                oltCount = totalFree - rehomingCount;
                            }

                            System.out.println("[INFO] totalFree=" + totalFree
                                    + ", Rehoming=" + rehomingCount + ", OLT=" + oltCount);

                            // === 6  card/slot  slot - ===
                            Map<String, List<String>> slotMap = new LinkedHashMap<>();
                            for (String port : allFreePorts) {
                                Matcher m = Pattern.compile("(\\d+/\\d+)/").matcher(port);
                                if (m.find()) {
                                    String slotKey = m.group(1); //  2/1
                                    slotMap.computeIfAbsent(slotKey, k -> new ArrayList<>()).add(port);
                                }
                            }

//   slot   
                            List<Map.Entry<String, List<String>>> sortedSlots = new ArrayList<>(slotMap.entrySet());
                            sortedSlots.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

//   port  slot - (card/slot - port )
                            List<String> interleavedPorts = new ArrayList<>();

//   slot  card  slot --
                            int index = 0;
                            while (true) {
                                boolean added = false;
                                for (Map.Entry<String, List<String>> entry : sortedSlots) {
                                    List<String> ports = entry.getValue();
                                    if (index < ports.size()) {
                                        interleavedPorts.add(ports.get(index));
                                        added = true;
                                    }
                                }
                                if (!added) {
                                    break;
                                }
                                index++;
                            }

//  card/slot -
                            System.out.println("[INFO] Slot availability (descending):");
                            for (Map.Entry<String, List<String>> entry : sortedSlots) {
                                System.out.printf("  - %-5s : %d free ports%n", entry.getKey(), entry.getValue().size());
                            }

                            //  -
                            System.out.println("\n=============================");
                            System.out.println("[SUMMARY] Ports to be reserved:");
                            for (int idx = 0; idx < interleavedPorts.size(); idx++) {
                                String port = interleavedPorts.get(idx);
                                String type = (idx < rehomingCount) ? "Rehoming"
                                        : (idx < rehomingCount + oltCount) ? "OLT" : "Skip";
                                System.out.printf(" - %-20s -> %s%n", port, type);
                            }
                            System.out.println("=============================\n");

                            // === 7  ===
                            System.out.println("[ACTION] Start reserving ports...");

//   system-view  config  ( undo)
                            if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, "system-view")) {
                                return;
                            }

                            boolean startedReserve = false;

                            List<String> reserveBatch = new ArrayList<>();
                            int batchSize = 5;

                            for (int idx = 0; idx < interleavedPorts.size(); idx++) {
                                String port = interleavedPorts.get(idx);
                                String intf = port.replace("(10G)", "")
                                        .replaceAll("(?i)(?:10)?GE", "GigabitEthernet ")
                                        .trim();

                                String desc;
                                if (idx < rehomingCount) {
                                    desc = "description Reserved_For_Rehoming";
                                } else if (idx < rehomingCount + oltCount) {
                                    desc = "description Reserved_For_OLT";
                                } else {
                                    break;
                                }

                                reserveBatch.add("interface " + intf + "\n" + desc + "\nquit\n");

                                //   block - 5 port
                                if (reserveBatch.size() >= batchSize) {
                                    if (!sendReserveBatch(this, Loopback, Device, cmdSet, Num_row, reserveBatch)) {
                                        return;
                                    }

                                    reserveBatch.clear();
                                }
                            }
//  - <5
                            if (!reserveBatch.isEmpty()) {
                                if (!sendReserveBatch(this, Loopback, Device, cmdSet, Num_row, reserveBatch)) {
                                    return;
                                }

                            }

//  Commit  system-view 
                            if (startedReserve) {
                                if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, "commit")) {
                                    return;
                                }
                                if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, "quit")) {
                                    return;
                                }
                            }

                            logwork(String.format(
                                    "[AUTO-CONFIG] %s - %d ports rehoming, %d ports OLT (%s)%n",
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                                    rehomingCount, oltCount, Loopback));

                        } catch (Exception e) {
                            System.out.println("[ERROR] HW-Config_PORT_Reserved: " + e.getMessage());
                        }
                    } else if (i == r - 2 && (cmdSet.equals("N-ISIS_Cost"))) {
                        try {
                            Thread.sleep(300); //  log 

                            //   log 
                            String fileName = buildDailyLogFileName(Loopback, Device, cmdSet, Num_row);
                            File logFile = new File(FileInput.getLog(), fileName);

                            if (!logFile.exists()) {
                                System.out.println("[ISIS-COST] Log file not found: " + logFile.getAbsolutePath());
                                continue;
                            }

                            //  -
                            StringBuilder sb = new StringBuilder();
                            try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    sb.append(line).append("\n");
                                }
                            }

                            String content = sb.toString();

                            //   pattern "isis <number>"
                            Pattern isisPattern = Pattern.compile("^\\s*isis\\s+(\\d+)\\b", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                            Matcher m = isisPattern.matcher(content);

                            LinkedHashSet<String> isisNumbers = new LinkedHashSet<>();
                            while (m.find()) {
                                isisNumbers.add(m.group(1).trim());
                            }

                            if (isisNumbers.isEmpty()) {
                                System.out.println("[ISIS-COST] No ISIS instances found in log file.");
                                continue;
                            }

                            System.out.println("[ISIS-COST] Found ISIS instances: " + isisNumbers);

                            //   show router isis <number> interface -
                            for (String num : isisNumbers) {
                                cmd = "show router isis " + num + " interface";
                                System.out.println("[ISIS CMD] " + cmd);
                                if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, cmd)) {
                                    return;
                                }
                            }

                        } catch (Exception e) {
                            System.out.println("[ERROR] ISIS-cost phase failed: " + e.getMessage());
                        }
                    } else if (i == r - 2 && (cmdSet.equals("N-ARP"))) {
                        try {
                            Thread.sleep(300); //  log 

                            String fileName = buildDailyLogFileName(Loopback, Device, cmdSet, Num_row);
                            File logFile = new File(FileInput.getLog(), fileName);

                            if (!logFile.exists()) {
                                System.out.println("[N-ARP] Log file not found: " + logFile.getAbsolutePath());
                                continue;
                            }

                            StringBuilder sb = new StringBuilder();
                            try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                                String line;
                                while ((line = br.readLine()) != null) {
                                    sb.append(line).append("\n");
                                }
                            }

                            String fullLog = sb.toString();
                            String marker = "show service service-using vprn";
                            String lowerLog = fullLog.toLowerCase();
                            int markerIndex = lowerLog.lastIndexOf(marker);
                            String vprnSection = (markerIndex >= 0) ? fullLog.substring(markerIndex) : fullLog;

                            Pattern vprnPattern = Pattern.compile("(?m)^\\s*(\\d+)\\s+VPRN\\b");
                            Matcher matcher = vprnPattern.matcher(vprnSection);

                            LinkedHashSet<String> uniqueServiceIds = new LinkedHashSet<>();
                            while (matcher.find()) {
                                uniqueServiceIds.add(matcher.group(1).trim());
                            }

                            if (uniqueServiceIds.isEmpty()) {
                                System.out.println("[N-ARP] No VPRN service ID found after 'show service service-using vprn'.");
                                continue;
                            }

                            System.out.println("[N-ARP] Found " + uniqueServiceIds.size() + " VPRN services -> sending ARP commands...");

                            for (String serviceId : uniqueServiceIds) {
                                String arpCmd = "show service id " + serviceId + " arp";
                                System.out.println("[N-ARP CMD] " + arpCmd);
                                if (!executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, arpCmd)) {
                                    return;
                                }
                            }

                        } catch (Exception e) {
                            System.out.println("[ERROR] N-ARP phase failed: " + e.getMessage());
                        }
                    }

                }

                if (failedAfterPassword) {
                    disconnect();
                    return; //   constructor -- -  END
                }

                disconnect(); //  Telnet -

                break;
            }
//   fail  password  TIMEOUT  log   save log
            if (failedAfterPassword || isTimeoutLog()) {
                System.out.println("[FAIL] Connection lost or no response after password at " + Loopback);
                logwork("[FAIL] Connection lost or no response after password at " + Loopback + "\n");

                //  - ConnectionFailed log
                Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed - no response after password]");

                disconnect();
                return; //   constructor  save node log
            }

//   readStreamToFile ()
            String fileName = buildDailyLogFileName(Loopback, Device, cmdSet, Num_row);

//  
            File logDir = new File(FileInput.getLog());
            File logFile = new File(logDir, fileName);
            double fileSizeKB = logFile.exists() ? (logFile.length() / 1024.0) : 0.0;

//  -
            String saveLog = String.format("[SAVE][%d] %s (%.1f KB)", Num_row, fileName, fileSizeKB);
            System.out.println("\n" + saveLog);
            logwork(saveLog + "\n");

//  END log   node
            String endTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String endLog = String.format("[END] %s (%s, %s) at %s",
                    Loopback, Device, cmdSet, endTime);
            System.out.println(endLog);
            logwork(endLog + "\n");

            //    readUntil("[press q/Q to quit]:");
            //    write_NoShow("q");
        } catch (java.net.SocketTimeoutException e) {
            String timeoutTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logwork("[TIMEOUT] Telnet read timed out on " + Loopback + " (" + Device + ") at " + timeoutTime + "\n");
            System.out.println("[TIMEOUT] " + Loopback + " timed out at " + timeoutTime + " - skipping node.");
            disconnect();
            Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed]");
            return;
        } catch (ConnectException ex) {

            //    Node offline   log  return
            System.out.println("[WARN] Connection failed: " + ex);
            logwork("[WARN] Connection failed: " + ex + "\n");
            Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed - connect exception]");
            return;

        } catch (JSchException ex) {
            String detail = summarizeGatewayAttemptError(ex);
            System.out.println("[WARN] SSH gateway failed: " + detail);
            logwork("[WARN] SSH gateway failed: " + detail + "\n");
            String reason = detail.toLowerCase(Locale.ROOT).contains("auth")
                    ? "_[Auth failed - SSH gateway]"
                    : "_[Connection failed - SSH gateway]";
            Connection_failed(Num_row, Loopback, Device, cmdSet, reason);
            return;

        } catch (Exception ex) {
            System.out.println("[ERROR] Telnet_Multi: " + ex);
            //  FAIL log  -- Node 
            String failTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logwork("[FAIL] " + Loopback + " (" + Device + ", " + cmdSet + ") at " + failTime + " - " + ex + "\n");
            System.out.println("[FAIL] " + Loopback + " (" + Device + ", " + cmdSet + ") at " + failTime);

            logwork("[ERROR] Telnet_Multi: " + ex + "\n");

            //   Excel lock  restart -
            if (ex instanceof org.apache.poi.ooxml.POIXMLException || ex instanceof java.util.zip.ZipException) {
                logwork("[ZIP_ERROR] Excel corrupted or locked: " + ex + "\n");
                System.out.println("[RESTART] Restarting BotGetLog_TrueCorp due to Excel lock...");
                BotGetLog_TrueCorp.RunBatch(FileInput.getCurrentFolder());
                return;
            }

            Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed - " + ex.getClass().getSimpleName() + "]");

        } finally {
            clearActiveLogSession();

        }

    }

    public String readUntil(String pattern) {
        return readUntilInternal(
                new String[]{pattern},
                "[TIMEOUT-READ] waiting for '" + pattern + "' (30000ms)\n",
                "[ERROR-readUntil] ",
                true);
    }

    private String readUntilInternal(String[] patterns, String timeoutLog, String errorLogPrefix, boolean stopOnAuthPrompt) {
        try {
            StringBuilder sb = new StringBuilder(4096); //  buffer 
            StringBuilder lowerTail = new StringBuilder(256);
            long lastData = System.currentTimeMillis();
            int timeoutMs = 30000; // 30s 
            String[] safePatterns = patterns == null ? new String[0] : patterns;
            String[] normalizedPatterns = normalizePatterns(safePatterns);
            while (true) {
                if (in == null) {
                    break;
                }
                if (in.available() <= 0) {
                    if (System.currentTimeMillis() - lastData > timeoutMs) {
                        logwork(timeoutLog);
                        return "[TIMEOUT-READ]";
                    }
                    sleepQuietly(currentReadPollDelayMs());
                    continue;
                }

                int c = in.read();
                if (c == -1) {
                    break;
                }
                char ch = (char) c;
                sb.append(ch);
                appendLowerTail(lowerTail, ch, READ_MATCH_WINDOW_CHARS);
                lastData = System.currentTimeMillis();

                if (shouldInspectReadBuffer(ch, sb.length())) {
                    String promptToken = extractPromptToken(lowerTail);

                    //   pattern
                    if (matchesAnyPattern(lowerTail, promptToken, safePatterns, normalizedPatterns)) {
                        String data = sb.toString();
                        s = extractPromptToken(data);
                        return data;
                    }

                    //  Pagination auto skip
                    if (containsPaginationMarker(lowerTail)) {
                        sendPaginationSpace(out, lowerTail);
                    }

                    //  Username / password / login prompt
                    if (stopOnAuthPrompt && containsAny(lowerTail, READ_AUTH_PROMPTS)) {
                        String data = sb.toString();
                        s = extractPromptToken(data);
                        return data;
                    }
                }

                //   CPU
                if (sb.length() % 512 == 0) {
                    Thread.yield();
                }
                if (sb.length() > 12000) {
                    sb.delete(0, sb.length() - 8000);
                }
            }
        } catch (Exception e) {
            logwork(errorLogPrefix + e + "\n");
            return "[TIMEOUT-READ]";
        }
        return "";
    }

    private static String extractPromptToken(String data) {
        if (data == null) {
            return "";
        }

        return extractPromptToken((CharSequence) data);
    }

    private static String cleanPromptToken(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").trim();
        return cleaned;
    }

    private static String extractPromptToken(CharSequence data) {
        if (data == null) {
            return "";
        }

        int end = data.length() - 1;
        while (end >= 0 && Character.isWhitespace(data.charAt(end))) {
            end--;
        }
        if (end < 0) {
            return "";
        }

        int start = end;
        while (start >= 0 && !Character.isWhitespace(data.charAt(start))) {
            start--;
        }

        int tokenStart = start + 1;
        for (int i = tokenStart - 1; i >= 0; i--) {
            char ch = data.charAt(i);
            if (ch == '\n' || ch == '\r') {
                break;
            }
            if (!Character.isWhitespace(ch) && !Character.isISOControl(ch)) {
                return "";
            }
        }
        return data.subSequence(tokenStart, end + 1).toString().trim();
    }

    private static boolean isPromptTerminatorPattern(String pattern) {
        return ">".equals(pattern) || "#".equals(pattern) || "]".equals(pattern);
    }

    private static boolean hasUnclosedBracketPrompt(String promptToken) {
        if (promptToken == null || promptToken.isEmpty()) {
            return false;
        }
        return (promptToken.startsWith("<") && promptToken.indexOf('>') < 0)
                || (promptToken.startsWith("[") && promptToken.indexOf(']') < 0);
    }

    private static boolean isStablePromptTokenForPattern(String promptToken, String pattern) {
        if (promptToken == null || promptToken.isEmpty() || pattern == null || pattern.isEmpty()) {
            return false;
        }
        if (!promptToken.endsWith(pattern)) {
            return false;
        }

        if ("#".equals(pattern) && hasUnclosedBracketPrompt(promptToken)) {
            return false;
        }
        if ("]".equals(pattern) && promptToken.startsWith("[") && promptToken.indexOf(']') < 0) {
            return false;
        }
        if (">".equals(pattern) && promptToken.startsWith("<") && promptToken.indexOf('>') < 0) {
            return false;
        }
        return true;
    }

    private static String extractPromptCandidateFromLine(String line, String vendor, String device) {
        String safeLine = cleanPromptToken(line);
        if (safeLine.isEmpty()) {
            return "";
        }

        if ("HW".equals(vendor)) {
            if (safeLine.startsWith("<")) {
                int end = safeLine.indexOf('>');
                if (end > 0) {
                    return safeLine.substring(0, end + 1);
                }
            }
        } else if ("N".equals(vendor)) {
            Matcher matcher = Pattern.compile("(\\*?A:[^\\s#]+#)").matcher(safeLine);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } else if ("ZTE".equals(vendor)) {
            String safeDevice = device == null ? "" : device.trim();
            if (!safeDevice.isEmpty()) {
                String expected = safeDevice + "#";
                int idx = safeLine.indexOf(expected);
                if (idx >= 0) {
                    return expected;
                }
            }
            Matcher matcher = Pattern.compile("([^\\s<>:#][^\\s#]*#)").matcher(safeLine);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        return "";
    }

    private static String extractPromptCandidateFromText(String text, String vendor, String device) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String safeVendor = safeTrim(vendor).toUpperCase(Locale.ROOT);
        if (!"HW".equals(safeVendor) && !"N".equals(safeVendor) && !"ZTE".equals(safeVendor)) {
            return "";
        }

        String[] lines = text.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String candidate = extractPromptCandidateFromLine(lines[i], vendor, device);
            if (!candidate.isEmpty()) {
                return cleanPromptToken(candidate);
            }
        }
        return "";
    }

    private static boolean isIgnoredPromptCandidate(String candidate, String vendor) {
        String value = cleanPromptToken(candidate);
        if (value.isEmpty()) {
            return true;
        }

        if ("HW".equals(vendor) && value.startsWith("<") && value.endsWith(">") && value.length() > 2) {
            String inner = value.substring(1, value.length() - 1).trim().toLowerCase(Locale.ROOT);
            if (inner.equals("active")
                    || inner.equals("inactive")
                    || inner.equals("up")
                    || inner.equals("down")
                    || inner.equals("enable")
                    || inner.equals("enabled")
                    || inner.equals("disable")
                    || inner.equals("disabled")
                    || inner.equals("ready")
                    || inner.equals("success")) {
                return true;
            }
        }

        return false;
    }

    private static String sanitizeFileNameComponent(String value) {
        String cleaned = safeTrim(value);
        if (cleaned.isEmpty()) {
            return "";
        }

        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]+", "_");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1).trim();
        }
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private static String sanitizeDeviceNameForFileName(String value) {
        return sanitizeFileNameComponent(value).replace('_', '-');
    }

    private static String extractNodeNameFromPromptToken(String promptToken) {
        String token = cleanPromptToken(promptToken);
        if (token.isEmpty()) {
            return "";
        }

        if (token.startsWith("*")) {
            token = token.substring(1).trim();
        }

        if (token.startsWith("<") && token.endsWith(">") && token.length() > 2) {
            token = token.substring(1, token.length() - 1).trim();
        } else {
            while (!token.isEmpty() && (token.endsWith("#") || token.endsWith(">") || token.endsWith("]"))) {
                token = token.substring(0, token.length() - 1).trim();
            }
        }

        if (token.matches("^[A-Za-z]:.+")) {
            token = token.substring(token.indexOf(':') + 1).trim();
        }

        return sanitizeDeviceNameForFileName(token);
    }

    private String resolveLogDeviceName(String configuredDevice) {
        String runtimeName = sanitizeDeviceNameForFileName(runtimeDeviceName);
        if (!runtimeName.isEmpty()) {
            return runtimeName;
        }

        String configuredName = sanitizeDeviceNameForFileName(configuredDevice);
        return configuredName.isEmpty() ? "UNKNOWN_DEVICE" : configuredName;
    }

    private void updateRuntimeDeviceNameFromResponse(String response, String configuredDevice, String vendorOrCmdSet) {
        String vendor = extractVendorPrefix(vendorOrCmdSet);
        String promptToken = extractPromptCandidateFromText(response, vendor, configuredDevice);
        String actualNodeName = extractNodeNameFromPromptToken(promptToken);
        if (actualNodeName.isEmpty()) {
            return;
        }

        String currentName = resolveLogDeviceName(configuredDevice);
        if (!actualNodeName.equals(runtimeDeviceName)) {
            runtimeDeviceName = actualNodeName;
        }

        if (!actualNodeName.equals(currentName)) {
            logwork("[INFO] Using actual node name for log file: " + currentName + " -> " + actualNodeName + "\n");
            System.out.println("[INFO] Using actual node name for log file: " + currentName + " -> " + actualNodeName);
        }
    }

    private static boolean matchesDailyLogTarget(String fileName, int numRow, String loopback, String cmdSet) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }

        Matcher matcher = DAILY_LOG_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return false;
        }

        String fileRow = safeTrim(matcher.group(1));
        String fileLoopback = safeTrim(matcher.group(2));
        String fileCmdSet = safeTrim(matcher.group(4));
        return String.valueOf(numRow).equals(fileRow)
                && safeTrim(loopback).equals(fileLoopback)
                && isEquivalentCmdSet(cmdSet, fileCmdSet);
    }

    private static List<String> readPromptCandidatesFromLog(File logFile, String device, String cmdSet) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (logFile == null || !logFile.exists()) {
            return new ArrayList<>();
        }

        String vendor = extractVendorPrefix(cmdSet);
        try ( java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
            long fileLength = raf.length();
            long seekPos = Math.max(0, fileLength - 8192);
            raf.seek(seekPos);
            byte[] buf = new byte[(int) (fileLength - seekPos)];
            raf.readFully(buf);
            String tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            String[] lines = tail.split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String candidate = extractPromptCandidateFromLine(lines[i], vendor, device);
                if (!candidate.isEmpty() && !isIgnoredPromptCandidate(candidate, vendor)) {
                    candidates.add(cleanPromptToken(candidate));
                    if (candidate.startsWith("*")) {
                        candidates.add(candidate.substring(1));
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[PROMPT-LOG] Failed to read prompt from log: " + logFile.getName() + " -> " + e.getMessage());
        }

        return new ArrayList<>(candidates);
    }

    private static List<String> buildPromptCandidates(File logFile, String device, String cmdSet, String lastPromptToken) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        List<String> promptFromLog = readPromptCandidatesFromLog(logFile, device, cmdSet);
        for (String candidate : promptFromLog) {
            if (candidate != null && !candidate.isEmpty()) {
                candidates.add(candidate);
            }
        }

        String safeDevice = device == null ? "" : device.trim();
        String vendor = extractVendorPrefix(cmdSet);

        if (!safeDevice.isEmpty()) {
            if ("HW".equals(vendor)) {
                candidates.add("<" + safeDevice + ">");
            } else if ("N".equals(vendor)) {
                candidates.add("*A:" + safeDevice + "#");
                candidates.add("A:" + safeDevice + "#");
                candidates.add(safeDevice + "#");
            } else if ("ZTE".equals(vendor)) {
                candidates.add(safeDevice + "#");
            }
        }

        String promptFromState = cleanPromptToken(extractPromptToken(lastPromptToken));
        if (!promptFromState.isEmpty() && !isIgnoredPromptCandidate(promptFromState, vendor)) {
            candidates.add(promptFromState);
            if (promptFromState.startsWith("*")) {
                candidates.add(promptFromState.substring(1));
            }
        }

        List<String> cleaned = new ArrayList<>();
        for (String candidate : candidates) {
            String value = cleanPromptToken(candidate);
            if (!value.isEmpty()
                    && !isIgnoredPromptCandidate(value, vendor)
                    && !cleaned.contains(value)) {
                cleaned.add(value);
            }
        }
        return cleaned;
    }

    private static boolean isStandaloneNodeExitCommand(String command) {
        if (command == null) {
            return false;
        }

        String normalized = command.replace('\r', '\n');
        String[] lines = normalized.split("\n");
        String lastNonEmpty = "";
        int nonEmptyCount = 0;
        for (String line : lines) {
            String trimmed = safeTrim(line);
            if (!trimmed.isEmpty()) {
                lastNonEmpty = trimmed;
                nonEmptyCount++;
            }
        }

        if (nonEmptyCount != 1) {
            return false;
        }

        String low = lastNonEmpty.toLowerCase(Locale.ROOT);
        return "quit".equals(low) || "exit".equals(low) || "logout".equals(low);
    }

    private static List<String> expandStreamPromptCandidates(List<String> promptCandidates, boolean sshGatewayConnection, String command) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        if (promptCandidates != null) {
            expanded.addAll(promptCandidates);
        }
        if (isStandaloneNodeExitCommand(command)) {
            expanded.add("Enter IP address [press q/Q to quit]:");
            expanded.add("Enter IP address [press q/Q to quit]");
            expanded.add("Please Enter IP address");
        }
        return new ArrayList<>(expanded);
    }

    private static String canonicalGatewayMenuPrompt() {
        return "Enter IP address [press q/Q to quit]:";
    }

    private String buildConsoleNodePrefix(int numRow, String loopback, String device) {
        return String.format("[%d|%s|%s]",
                numRow,
                sanitizeFileNameComponent(loopback),
                resolveLogDeviceName(device));
    }

    private static String summarizeCommandForConsole(String command) {
        if (command == null) {
            return "";
        }

        String normalized = command.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return "";
        }

        String[] lines = normalized.split("\n");
        String firstLine = safeTrim(lines[0]);
        if (lines.length <= 1) {
            return firstLine;
        }

        return firstLine + " ... (+" + (lines.length - 1) + " lines)";
    }

    private static String summarizePromptForConsole(String promptCandidate, String responseText,
            String device, String cmdSet) {
        String cleaned = cleanPromptToken(promptCandidate);
        if (containsGatewayMenuPrompt(cleaned) || containsGatewayMenuPrompt(responseText)) {
            return canonicalGatewayMenuPrompt();
        }
        if (!cleaned.isEmpty()) {
            return cleaned;
        }

        String vendor = extractVendorPrefix(cmdSet);
        String extracted = extractPromptCandidateFromText(responseText, vendor, device);
        if (!extracted.isEmpty()) {
            return extracted;
        }

        if (responseText != null && hasInteractivePromptToken(responseText)) {
            String fallbackToken = cleanPromptToken(extractPromptToken(responseText));
            if (!fallbackToken.isEmpty()) {
                return fallbackToken;
            }
            return "interactive prompt";
        }
        return "<prompt detected>";
    }

    private static String joinQuotedValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            String cleaned = cleanPromptToken(value);
            if (!cleaned.isEmpty()) {
                quoted.add("\"" + cleaned + "\"");
            }
        }
        return String.join(", ", quoted);
    }

    private String formatPromptWaitMessage(int numRow, String loopback, String device,
            List<String> promptCandidates, String command) {
        LinkedHashSet<String> nodePrompts = new LinkedHashSet<>();
        boolean hasGatewayMenuPrompt = false;

        if (promptCandidates != null) {
            for (String candidate : promptCandidates) {
                String cleaned = cleanPromptToken(candidate);
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (containsGatewayMenuPrompt(cleaned)) {
                    hasGatewayMenuPrompt = true;
                    continue;
                }
                nodePrompts.add(cleaned);
            }
        }

        StringBuilder message = new StringBuilder("[PROMPT-WAIT]");
        message.append(buildConsoleNodePrefix(numRow, loopback, device)).append(" ");
        List<String> orderedNodePrompts = new ArrayList<>(nodePrompts);

        if (!orderedNodePrompts.isEmpty()) {
            int primaryIndex = 0;
            for (int i = 0; i < orderedNodePrompts.size(); i++) {
                if (!orderedNodePrompts.get(i).startsWith("*")) {
                    primaryIndex = i;
                    break;
                }
            }

            String primaryPrompt = orderedNodePrompts.get(primaryIndex);
            message.append("prompt=\"").append(primaryPrompt).append("\"");
        } else if (hasGatewayMenuPrompt) {
            message.append("prompt=\"").append(canonicalGatewayMenuPrompt()).append("\"");
        } else {
            message.append("prompt=<none>");
        }

        if (hasGatewayMenuPrompt && !nodePrompts.isEmpty()) {
            message.append(isStandaloneNodeExitCommand(command) ? " | after-exit=" : " | gateway-menu=");
            message.append("\"").append(canonicalGatewayMenuPrompt()).append("\"");
        }

        return message.toString();
    }

    private CommandReadResult readStreamToFileSsh(BufferedOutputStream outFile, List<String> promptCandidates,
            long startTime, long maxWaitMs, long idleTimeoutMs, String consolePrefix) throws IOException {
        StringBuilder response = new StringBuilder(4096);
        StringBuilder lowerTail = new StringBuilder(256);
        long lastDataAt = startTime;
        long lastNoticeAt = startTime;
        boolean tailAtPrompt = false;
        String[] safePatterns = promptCandidates == null ? new String[0] : promptCandidates.toArray(new String[0]);
        String[] normalizedPatterns = normalizePatterns(safePatterns);
        final long PROMPT_SETTLE_MS = 90L;
        final int pollDelayMs = 10;

        while (true) {
            if (in == null) {
                return CommandReadResult.ioError("SSH input stream unavailable");
            }

            if (in.available() <= 0) {
                long now = System.currentTimeMillis();
                if (tailAtPrompt && now - lastDataAt >= PROMPT_SETTLE_MS) {
                    return CommandReadResult.prompt();
                }
                if (now - lastNoticeAt >= COMMAND_WAIT_LOG_INTERVAL_MS) {
                    System.out.println("[WAITING]" + consolePrefix + " prompt elapsed="
                            + formatDurationSeconds(now - startTime)
                            + " idle=" + formatDurationSeconds(now - lastDataAt));
                    lastNoticeAt = now;
                }
                if (now - lastDataAt > idleTimeoutMs) {
                    String detail = "no output for " + formatDurationSeconds(idleTimeoutMs)
                            + " while waiting for prompt";
                    System.out.println("[END STREAM]" + consolePrefix + " " + detail);
                    return CommandReadResult.timeout(detail);
                }
                if (now - startTime > maxWaitMs) {
                    String detail = "prompt wait exceeded " + formatDurationSeconds(maxWaitMs);
                    System.out.println("[END STREAM]" + consolePrefix + " " + detail);
                    return CommandReadResult.timeout(detail);
                }
                sleepQuietly(pollDelayMs);
                continue;
            }

            int next = in.read();
            if (next == -1) {
                String snapshot = response.toString();
                if (containsRemoteSessionClosedText(snapshot)) {
                    return CommandReadResult.remoteClosed(summarizeRemoteSessionCloseReason(snapshot));
                }
                return CommandReadResult.ioError("SSH stream ended before prompt");
            }

            outFile.write(next);

            char ch = (char) next;
            response.append(ch);
            appendLowerTail(lowerTail, ch, READ_MATCH_WINDOW_CHARS);
            lastDataAt = System.currentTimeMillis();

            if (response.length() > 4096) {
                response.delete(0, response.length() - 4096);
            }

            if (containsPaginationMarker(lowerTail)) {
                sendPaginationSpace(out, lowerTail);
            }

            String lowerSnapshot = lowerTail.toString();
            if (containsRemoteSessionClosedText(lowerSnapshot)) {
                return CommandReadResult.remoteClosed(summarizeRemoteSessionCloseReason(lowerSnapshot));
            }

            if (!promptCandidates.isEmpty() && shouldInspectReadBuffer(ch, response.length())) {
                String promptToken = extractPromptToken(lowerTail);
                tailAtPrompt = matchesAnyPattern(lowerTail, promptToken, safePatterns, normalizedPatterns)
                        || isInteractivePromptToken(promptToken)
                        || containsGatewayMenuPrompt(lowerSnapshot);
            }

            if (response.length() % 512 == 0) {
                Thread.yield();
            }
        }
    }

    private String resolveSshCommandLogPrompt(String device, String cmdSet) {
        String promptToken = cleanPromptToken(extractPromptToken(s));
        if (!promptToken.isEmpty()) {
            return promptToken;
        }

        String safeDevice = resolveLogDeviceName(device);
        if (safeDevice.isEmpty()) {
            return "";
        }

        String vendor = extractVendorPrefix(cmdSet);
        if ("HW".equals(vendor)) {
            return "<" + safeDevice + ">";
        }
        if ("N".equals(vendor) || "ZTE".equals(vendor)) {
            return safeDevice + "#";
        }
        return "";
    }

    private static void appendSentCommandToStreamLog(BufferedOutputStream outFile, String promptToken, String command) throws IOException {
        if (outFile == null || command == null) {
            return;
        }

        String normalized = command.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.trim().isEmpty()) {
            return;
        }

        String transcriptBody = normalized;
        String cleanPrompt = safeTrim(promptToken);
        if (!cleanPrompt.isEmpty() && normalized.indexOf('\n') < 0) {
            transcriptBody = cleanPrompt + normalized;
        }
        String transcript = "\r\n" + transcriptBody.replace("\n", "\r\n");
        if (!transcript.endsWith("\r\n")) {
            transcript = transcript + "\r\n";
        }
        outFile.write(transcript.getBytes(StandardCharsets.UTF_8));
    }

    private void drainPendingSshStreamToLog(BufferedOutputStream outFile) throws IOException {
        if (outFile == null || in == null) {
            return;
        }

        final long maxDrainMs = 120L;
        final long quietMs = 30L;
        long start = System.currentTimeMillis();
        long lastReadAt = -1L;
        boolean drainedAny = false;

        while (true) {
            int available = in.available();
            if (available > 0) {
                int next = in.read();
                if (next == -1) {
                    break;
                }
                outFile.write(next);
                lastReadAt = System.currentTimeMillis();
                drainedAny = true;
                continue;
            }

            long now = System.currentTimeMillis();
            if (!drainedAny) {
                break;
            }
            if (lastReadAt >= 0L && now - lastReadAt >= quietMs) {
                break;
            }
            if (now - start >= maxDrainMs) {
                break;
            }
            sleepQuietly(10);
        }
    }

    private static boolean isPromptOnlyLine(String line) {
        String trimmed = safeTrim(line);
        if (trimmed.isEmpty()) {
            return false;
        }

        String promptToken = extractLeadingPromptToken(trimmed);
        return !promptToken.isEmpty() && trimmed.equals(promptToken);
    }

    private static String extractLeadingPromptToken(String line) {
        String trimmed = safeTrim(line);
        if (trimmed.isEmpty()) {
            return "";
        }

        if (trimmed.startsWith("<")) {
            int close = trimmed.indexOf('>');
            if (close > 0) {
                return trimmed.substring(0, close + 1);
            }
        }

        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close > 0) {
                return trimmed.substring(0, close + 1);
            }
        }

        int hash = trimmed.indexOf('#');
        if (hash > 0) {
            return trimmed.substring(0, hash + 1);
        }

        return "";
    }

    private static boolean isPromptedCommandLine(String line, String command) {
        String trimmed = safeTrim(line);
        String normalizedCommand = safeTrim(command);
        if (trimmed.isEmpty() || normalizedCommand.isEmpty()) {
            return false;
        }

        String promptToken = extractLeadingPromptToken(trimmed);
        if (promptToken.isEmpty() || !trimmed.startsWith(promptToken) || trimmed.length() <= promptToken.length()) {
            return false;
        }

        return normalizedCommand.equals(trimmed.substring(promptToken.length()));
    }

    private static boolean isPromptWithBodyLine(String line) {
        String trimmed = safeTrim(line);
        if (trimmed.isEmpty()) {
            return false;
        }

        String promptToken = extractLeadingPromptToken(trimmed);
        return !promptToken.isEmpty()
                && trimmed.startsWith(promptToken)
                && trimmed.length() > promptToken.length()
                && !safeTrim(trimmed.substring(promptToken.length())).isEmpty();
    }

    private static boolean isPromptedCommandLineLoose(String line, String command) {
        String trimmed = safeTrim(line);
        String normalizedCommand = safeTrim(command);
        if (trimmed.isEmpty() || normalizedCommand.isEmpty()) {
            return false;
        }

        String promptToken = extractLeadingPromptToken(trimmed);
        if (promptToken.isEmpty() || !trimmed.startsWith(promptToken) || trimmed.length() <= promptToken.length()) {
            return false;
        }

        String body = safeTrim(trimmed.substring(promptToken.length()));
        return normalizedCommand.equals(body);
    }

    private static String normalizePromptCommandSpacing(String line, String command) {
        String trimmed = safeTrim(line);
        String normalizedCommand = safeTrim(command);
        if (trimmed.isEmpty() || normalizedCommand.isEmpty()) {
            return trimmed;
        }

        String promptToken = extractLeadingPromptToken(trimmed);
        if (promptToken.isEmpty() || !trimmed.startsWith(promptToken) || trimmed.length() <= promptToken.length()) {
            return trimmed;
        }

        String body = safeTrim(trimmed.substring(promptToken.length()));
        if (normalizedCommand.equals(body)) {
            return promptToken + normalizedCommand;
        }
        return trimmed;
    }

    private static String collapseRepeatedCommandEcho(String line, String command) {
        String trimmed = safeTrim(line);
        String normalizedCommand = safeTrim(command);
        if (trimmed.isEmpty() || normalizedCommand.isEmpty()) {
            return trimmed;
        }

        String promptToken = extractLeadingPromptToken(trimmed);
        String prefix = "";
        String body = trimmed;
        if (!promptToken.isEmpty() && trimmed.startsWith(promptToken) && trimmed.length() > promptToken.length()) {
            prefix = promptToken;
            body = trimmed.substring(promptToken.length());
        }

        int index = 0;
        int repeatCount = 0;
        while (body.startsWith(normalizedCommand, index)) {
            repeatCount++;
            index += normalizedCommand.length();
        }

        if (repeatCount >= 2 && index == body.length()) {
            return prefix + normalizedCommand;
        }
        return trimmed;
    }

    private static void normalizeSshCommandTranscript(File logFile, String command) {
        if (logFile == null || !logFile.exists()) {
            return;
        }

        String normalizedCommand = safeTrim(command);
        if (normalizedCommand.isEmpty()
                || normalizedCommand.indexOf('\n') >= 0
                || normalizedCommand.indexOf('\r') >= 0) {
            return;
        }

        try {
            byte[] rawBytes = Files.readAllBytes(logFile.toPath());
            String original = new String(rawBytes, StandardCharsets.ISO_8859_1);
            String sanitizedOriginal = stripTerminalControlSequences(original);
            String newline = original.contains("\r\n") ? "\r\n" : "\n";
            String normalized = sanitizedOriginal.replace("\r\n", "\n").replace('\r', '\n');
            List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));

            boolean changed = !original.equals(sanitizedOriginal);
            while (lines.size() > 1
                    && safeTrim(lines.get(0)).isEmpty()
                    && isPromptWithBodyLine(lines.get(1))) {
                lines.remove(0);
                changed = true;
            }

            for (int i = 0; i < lines.size(); i++) {
                String normalizedLine = normalizePromptCommandSpacing(lines.get(i), normalizedCommand);
                if (!safeTrim(lines.get(i)).equals(normalizedLine)) {
                    lines.set(i, normalizedLine);
                    changed = true;
                }
            }

            for (int i = 1; i < lines.size(); i++) {
                if (safeTrim(lines.get(0)).equals(normalizedCommand)
                        && isPromptedCommandLineLoose(lines.get(i), normalizedCommand)) {
                    for (int removeIdx = i - 1; removeIdx >= 0; removeIdx--) {
                        lines.remove(removeIdx);
                    }
                    changed = true;
                    break;
                }
            }

            for (int i = 0; i < lines.size() - 1; i++) {
                String collapsedCurrent = collapseRepeatedCommandEcho(lines.get(i), normalizedCommand);
                if (!safeTrim(lines.get(i)).equals(collapsedCurrent)) {
                    lines.set(i, collapsedCurrent);
                    changed = true;
                }

                String collapsedNext = collapseRepeatedCommandEcho(lines.get(i + 1), normalizedCommand);
                if (!safeTrim(lines.get(i + 1)).equals(collapsedNext)) {
                    lines.set(i + 1, collapsedNext);
                    changed = true;
                }

                String currentLine = safeTrim(lines.get(i));
                String nextLine = safeTrim(lines.get(i + 1));

                if (isPromptOnlyLine(lines.get(i)) && isPromptWithBodyLine(lines.get(i + 1))) {
                    lines.remove(i);
                    changed = true;
                    i = Math.max(-1, i - 1);
                    continue;
                }

                if (isPromptOnlyLine(lines.get(i)) && normalizedCommand.equals(nextLine)) {
                    lines.set(i, currentLine + normalizedCommand);
                    lines.remove(i + 1);
                    changed = true;

                    while (i + 1 < lines.size() && normalizedCommand.equals(safeTrim(lines.get(i + 1)))) {
                        lines.remove(i + 1);
                        changed = true;
                    }
                    continue;
                }

                if (!currentLine.isEmpty()
                        && currentLine.endsWith(normalizedCommand)
                        && normalizedCommand.equals(nextLine)) {
                    lines.remove(i + 1);
                    changed = true;
                }
            }

            if (!changed) {
                return;
            }

            String rebuilt = String.join(newline, lines);
            Files.write(logFile.toPath(), rebuilt.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            System.out.println("[WARN] normalizeSshCommandTranscript failed: " + e.getMessage());
        }
    }

    private static String stripTerminalControlSequences(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        StringBuilder cleaned = new StringBuilder(text.length());
        int length = text.length();
        int index = 0;
        while (index < length) {
            char ch = text.charAt(index);
            if (ch == '\u001B') {
                index = consumeTerminalEscapeSequence(text, index, cleaned);
                continue;
            }
            if (ch == '\b') {
                removeLastLogCharacter(cleaned);
                index++;
                continue;
            }
            if (Character.isISOControl(ch) && ch != '\r' && ch != '\n' && ch != '\t') {
                index++;
                continue;
            }
            cleaned.append(ch);
            index++;
        }
        return cleaned.toString();
    }

    private static int consumeTerminalEscapeSequence(String text, int start, StringBuilder cleaned) {
        int length = text.length();
        if (start + 1 >= length) {
            return length;
        }

        char type = text.charAt(start + 1);
        if (type == '[') {
            int cursor = start + 2;
            while (cursor < length) {
                char terminator = text.charAt(cursor);
                if (terminator >= '@' && terminator <= '~') {
                    applyAnsiCursorCommand(text.substring(start + 2, cursor + 1), cleaned);
                    return cursor + 1;
                }
                cursor++;
            }
            return length;
        }

        if (type == ']') {
            int cursor = start + 2;
            while (cursor < length) {
                char current = text.charAt(cursor);
                if (current == '\u0007') {
                    return cursor + 1;
                }
                if (current == '\u001B' && cursor + 1 < length && text.charAt(cursor + 1) == '\\') {
                    return cursor + 2;
                }
                cursor++;
            }
            return length;
        }

        return Math.min(start + 2, length);
    }

    private static void applyAnsiCursorCommand(String sequence, StringBuilder cleaned) {
        if (sequence == null || sequence.isEmpty()) {
            return;
        }

        Matcher matcher = ANSI_CURSOR_LEFT_PATTERN.matcher(sequence);
        if (!matcher.matches()) {
            return;
        }

        String digits = matcher.group(1);
        int count = 1;
        if (digits != null && !digits.isEmpty()) {
            try {
                count = Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }

        for (int i = 0; i < count; i++) {
            removeLastLogCharacter(cleaned);
        }
    }

    private static void removeLastLogCharacter(StringBuilder cleaned) {
        if (cleaned == null || cleaned.length() == 0) {
            return;
        }

        char last = cleaned.charAt(cleaned.length() - 1);
        if (last != '\n' && last != '\r') {
            cleaned.deleteCharAt(cleaned.length() - 1);
        }
    }

    // Match prompt terminators only when they are at the end of the prompt token.
    private static boolean matchesAnyPattern(StringBuilder lowerTail, String promptToken, String[] patterns, String[] normalizedPatterns) {
        if (lowerTail == null || patterns == null || patterns.length == 0) {
            return false;
        }

        for (int i = 0; i < patterns.length; i++) {
            String pattern = patterns[i];
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            if (isPromptTerminatorPattern(pattern)) {
                if (isStablePromptTokenForPattern(promptToken, pattern)) {
                    return true;
                }
            } else if (lowerTail.indexOf(normalizedPatterns[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInteractivePromptToken(String promptToken) {
        String token = cleanPromptToken(promptToken);
        if (token.isEmpty()) {
            return false;
        }
        char lastChar = token.charAt(token.length() - 1);
        return (lastChar == '>' && isStablePromptTokenForPattern(token, ">"))
                || (lastChar == '#' && isStablePromptTokenForPattern(token, "#"))
                || (lastChar == ']' && isStablePromptTokenForPattern(token, "]"));
    }

    public String readUntilAny(String... patterns) {
        return readUntilInternal(
                patterns,
                "[TIMEOUT-READ] waiting for any prompt (30000ms)\n",
                "[ERROR-readUntilAny] ",
                true);
    }

    private String readUntilPromptOnly(String... patterns) {
        return readUntilInternal(
                patterns,
                "[TIMEOUT-READ] waiting for actual prompt only (30000ms)\n",
                "[ERROR-readUntilPromptOnly] ",
                false);
    }

    private static String[] normalizePatterns(String[] patterns) {
        if (patterns == null || patterns.length == 0) {
            return new String[0];
        }

        String[] normalized = new String[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            normalized[i] = patterns[i] == null ? "" : patterns[i].toLowerCase();
        }
        return normalized;
    }

    private static void appendLowerTail(StringBuilder lowerTail, char ch, int maxChars) {
        if (lowerTail == null) {
            return;
        }

        lowerTail.append(Character.toLowerCase(ch));
        if (lowerTail.length() > maxChars) {
            lowerTail.delete(0, lowerTail.length() - maxChars);
        }
    }

    private static void appendLowerTail(StringBuilder lowerTail, CharSequence text, int maxChars) {
        if (lowerTail == null || text == null || text.length() == 0) {
            return;
        }

        String chunk = text.toString().toLowerCase(Locale.ROOT);
        if (chunk.length() >= maxChars) {
            lowerTail.setLength(0);
            lowerTail.append(chunk, chunk.length() - maxChars, chunk.length());
            return;
        }

        lowerTail.append(chunk);
        if (lowerTail.length() > maxChars) {
            lowerTail.delete(0, lowerTail.length() - maxChars);
        }
    }

    private static int randomDelayMs(int baseMs, int jitterMs) {
        int safeBase = Math.max(0, baseMs);
        int safeJitter = Math.max(0, jitterMs);
        if (safeJitter == 0) {
            return safeBase;
        }
        return safeBase + java.util.concurrent.ThreadLocalRandom.current().nextInt(safeJitter + 1);
    }

    private static void sleepQuietly(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ignored) {
        }
    }

    private int currentReadPollDelayMs() {
        return isSshGatewayConnection() ? 10 : 25;
    }

    private static boolean shouldInspectReadBuffer(char ch, int length) {
        if (length <= 32 || length % READ_CHECK_INTERVAL_CHARS == 0) {
            return true;
        }
        if (Character.isWhitespace(ch)) {
            return true;
        }
        switch (ch) {
            case ':':
            case '>':
            case '#':
            case ']':
            case '?':
            case '-':
                return true;
            default:
                return false;
        }
    }

    private static boolean containsAny(StringBuilder lowerTail, String... markers) {
        if (lowerTail == null || markers == null || markers.length == 0) {
            return false;
        }

        for (String marker : markers) {
            if (marker != null && !marker.isEmpty() && lowerTail.indexOf(marker) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPaginationMarker(StringBuilder lowerTail) {
        if (lowerTail == null || lowerTail.length() == 0) {
            return false;
        }
        if (containsAny(lowerTail, READ_PAGINATION_MARKERS)) {
            return true;
        }
        return READ_PAGINATION_PATTERN.matcher(lowerTail).find();
    }

    private static void sendPaginationSpace(PrintStream output, StringBuilder lowerTail) {
        if (output == null) {
            return;
        }
        output.print(" ");
        output.flush();
        if (lowerTail != null) {
            lowerTail.setLength(0);
        }
        sleepQuietly(50);
    }

    private static boolean containsAuthPromptText(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase();
        return low.contains("username:")
                || low.contains("user name:")
                || low.contains("login:")
                || low.contains("ogin:")
                || low.contains("password:");
    }

    private static boolean containsLoginFailureText(String text) {
        if (text == null) {
            return false;
        }
        String low = text.toLowerCase();
        return low.contains("login incorrect")
                || low.contains("authentication failed")
                || low.contains("invalid password")
                || low.contains("access denied")
                || low.contains("username or password error")
                || low.contains("password error")
                || low.contains("user was locked")
                || low.contains("the ip has been blocked")
                || low.contains("cannot log on it");
    }

    private static boolean hasInteractivePromptToken(String text) {
        String promptToken = extractPromptToken(text);
        return isInteractivePromptToken(promptToken);
    }

    private static String summarizeResponseForLog(String text, String... secrets) {
        if (text == null) {
            return "<null>";
        }
        String normalized = text
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && !secret.isEmpty()) {
                    normalized = normalized.replace(secret, "<redacted>");
                }
            }
        }
        if (normalized.length() > 400) {
            return normalized.substring(0, 400) + "...";
        }
        return normalized;
    }

    private static String normalizeSshInteractiveInput(String value) {
        String safe = value == null ? "" : value;
        safe = safe.replace("\r\n", "\r").replace("\n", "\r");
        if (!safe.endsWith("\r")) {
            safe = safe + "\r";
        }
        return safe;
    }

    private boolean isSshGatewayConnection() {
        return gatewayEndpoint != null
                && gatewayEndpoint.protocol == GatewayProtocol.SSH
                && sshSession != null
                && sshSession.isConnected();
    }

    private static void appendLoginTranscriptDelta(StringBuilder log, String initialResponse, String combinedResponse) {
        if (log == null || combinedResponse == null || combinedResponse.isEmpty()) {
            return;
        }

        String base = initialResponse == null ? "" : initialResponse;
        if (!base.isEmpty() && combinedResponse.startsWith(base)) {
            log.append(combinedResponse.substring(base.length()));
        } else {
            log.append(combinedResponse);
        }
    }

    private static boolean hasNodeLoginSignal(String text) {
        if (text == null) {
            return false;
        }
        if (containsGatewayMenuPrompt(text)
                && !containsAuthPromptText(text)
                && !containsLoginFailureText(text)
                && !containsTransportFailureText(text)) {
            return false;
        }
        return containsAuthPromptText(text)
                || hasInteractivePromptToken(text)
                || containsLoginFailureText(text)
                || containsTransportFailureText(text);
    }

    private static boolean isGatewayMenuEchoOnly(String text) {
        return text != null
                && containsGatewayMenuPrompt(text)
                && !containsAuthPromptText(text)
                && !containsLoginFailureText(text)
                && !containsTransportFailureText(text);
    }

    private static String[] buildSshNodePromptPatterns(String device, String cmdSet) {
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        String safeDevice = safeTrim(device);
        if (!safeDevice.isEmpty()) {
            patterns.add("<" + safeDevice + ">");
            patterns.add("*A:" + safeDevice + "#");
            patterns.add("A:" + safeDevice + "#");
            patterns.add(safeDevice + "#");
        }
        patterns.add("error:");
        return patterns.toArray(new String[0]);
    }

    private String completeSshNodeLogin(String loopback, String device, String cmdSet,
            String initialResponse, String username, String password) {
        StringBuilder combined = new StringBuilder();
        String current = initialResponse == null ? "" : initialResponse;
        String[] promptPatterns = buildSshNodePromptPatterns(device, cmdSet);

        if (!current.isEmpty()) {
            logwork("[SSH-NODE-RAW-0] " + loopback + " => "
                    + summarizeResponseForLog(current, username, password) + "\n");
            if (!isGatewayMenuEchoOnly(current)) {
                combined.append(current);
            }
        }

        int settleRead = 1;
        while (!hasNodeLoginSignal(combined.toString()) && settleRead <= 3) {
            current = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS, promptPatterns);
            logwork("[SSH-NODE-RAW-" + settleRead + "] " + loopback + " => "
                    + summarizeResponseForLog(current, username, password) + "\n");
            if (current == null || current.isEmpty()) {
                break;
            }
            if (isTimeoutResponse(current)) {
                if (combined.length() == 0 && settleRead < 3) {
                    logwork("[SSH-NODE-NUDGE] Sending Enter to continue node login for " + loopback + "\n");
                    write_NoShow("");
                    settleRead++;
                    continue;
                }
                combined.append(current);
                break;
            }
            if (!isGatewayMenuEchoOnly(current)) {
                combined.append(current);
            }
            settleRead++;
        }

        String transcript = combined.toString();
        if (containsLoginFailureText(transcript) || hasInteractivePromptToken(transcript)) {
            return transcript;
        }

        String loginState = transcript;
        if (containsGatewayUsernamePrompt(loginState) && username != null && !username.trim().isEmpty()) {
            logwork("[SSH-NODE] Sending username for " + loopback + "\n");
            write_NoShow(username);
            current = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS, promptPatterns);
            logwork("[SSH-NODE-RAW-USER] " + loopback + " => "
                    + summarizeResponseForLog(current, username, password) + "\n");
            if (current != null && !current.isEmpty()) {
                combined.append(current);
                loginState = current;
            }
        }

        transcript = combined.toString();
        if (containsLoginFailureText(transcript) || hasInteractivePromptToken(transcript)) {
            return transcript;
        }

        if (containsGatewayPasswordPrompt(loginState) && password != null && !password.trim().isEmpty()) {
            logwork("[SSH-NODE] Sending password for " + loopback + "\n");
            write_NoShow(password);
            current = readSshBootstrapResponse(in, out, SOCKET_TIMEOUT_MS, promptPatterns);
            logwork("[SSH-NODE-RAW-PASS] " + loopback + " => "
                    + summarizeResponseForLog(current, username, password) + "\n");
            if (current != null && !current.isEmpty()) {
                combined.append(current);
            }
        }

        return combined.toString();
    }

    private String waitForPostPasswordPrompt(String loopback, String username, String password) {
        String firstRead = readUntilAny(">", "#", "error:");
        if (firstRead == null || firstRead.isEmpty()) {
            return firstRead;
        }
        logwork("[LOGIN-RAW-1] " + loopback + " => " + summarizeResponseForLog(firstRead, username, password) + "\n");
        if (!containsAuthPromptText(firstRead) || hasInteractivePromptToken(firstRead)) {
            return firstRead;
        }

        logwork("[LOGIN-WAIT] Auth prompt still visible after password for " + loopback
                + ", waiting for actual prompt\n");
        String combined = firstRead;
        String low = firstRead.toLowerCase();

        if ((low.contains("login incorrect") || low.contains("username:") || low.contains("user name:") || low.contains("login:") || low.contains("ogin:"))
                && username != null && !username.trim().isEmpty()) {
            logwork("[LOGIN-RECOVER] Re-sending username for " + loopback + "\n");
            write_NoShow(username);
            String pwPrompt = readUntil("ssword:");
            logwork("[LOGIN-RAW-USER] " + loopback + " => " + summarizeResponseForLog(pwPrompt, username, password) + "\n");
            if (pwPrompt != null) {
                combined += pwPrompt;
            }
            if (containsAuthPromptText(pwPrompt) && password != null && !password.trim().isEmpty()) {
                logwork("[LOGIN-RECOVER] Re-sending password for " + loopback + "\n");
                write_NoShow(password);
            }
        } else if (low.contains("password:") && password != null && !password.trim().isEmpty()) {
            logwork("[LOGIN-RECOVER] Re-sending password for " + loopback + "\n");
            write_NoShow(password);
        }

        String secondRead = readUntilAny(">", "#", "error:");
        logwork("[LOGIN-RAW-2] " + loopback + " => " + summarizeResponseForLog(secondRead, username, password) + "\n");
        if (secondRead == null || secondRead.isEmpty()) {
            return combined;
        }
        return combined + secondRead;
    }

    public void write(String value) {
        try {
            if (isSshGatewayConnection()) {
                out.print(normalizeSshInteractiveInput(value));
            } else {
                out.println(value);
            }
            out.flush();

            //   IP node --
            String target = (Loopback != null && !Loopback.isEmpty()) ? Loopback : "Unknown-IP";

            //System.out.println("[CMD -> " + target + "] " + value);
            logwork("[CMD -> " + target + "] " + value + "\n");

        } catch (Exception e) {
            System.out.println("[ERROR] Failed to send command: " + e.getMessage());
        }
    }

    public void writeLoopBack(String value) {
        try {
            if (isSshGatewayConnection()) {
                out.print(normalizeSshInteractiveInput(value));
            } else {
                out.println(value);
            }
            out.flush();

            System.out.println("\n[Entering node] " + value);
            logwork("\n[Entering node] " + value + "\n");

        } catch (Exception e) {
            System.out.println(e.toString());
        }
    }

    public void write_NoShow(String value) {
        try {
            if (isSshGatewayConnection()) {
                out.print(normalizeSshInteractiveInput(value));
            } else {
                out.println(value);
            }
            out.flush();

        } catch (Exception e) {
            System.out.println(e.toString());
        }
    }

    public String sendCommand(String command) {
        try {
            write(command);
            return readUntil(prompt + " ");
        } catch (Exception e) {
            System.out.println(e.toString());
        }
        return null;
    }

    public void disconnect() {
        try {
            resetGatewayConnection();
            System.out.println("\n[INFO] Disconnected from node");
            logwork("\n[INFO] Disconnected from node\n");
        } catch (Exception e) {
            System.out.println("\n[WARN] Disconnect error: " + e);
            logwork("\n[WARN] Disconnect error: " + e + "\n");
        } finally {
            clearActiveLogSession();
        }
    }

    public void Connection_failed(int Num_row, String Loopback, String Device, String cmdSet, String reason) {
        try {
            sessionFailureRecorded = true;
            String cleanReason = reason.startsWith("_") ? reason.substring(1) : reason;
            String timeNow = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String dateTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            String ip = "";
            String dev = Device;
            if (Loopback.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*")) {
                ip = Loopback;
            } else if (Device.contains("_")) {
                String[] parts = Device.split("_");
                if (parts.length >= 2) {
                    ip = parts[0];
                    dev = parts[1];
                }
            }

            //  -
            String logType;
            if (cleanReason.contains("Wrong vendor")) {
                logType = "Node_WrongVendor_" + dateTag + ".txt";
            } else {
                logType = "Node_ConnectionFailed_" + dateTag + ".txt";
            }

            //   timestamp  Wrong Vendor format
            String message = String.format(
                    "[AUTO]%s,[%d]%s_%s_%s_%s.txt,[%s]",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), // 
                    Num_row,
                    Loopback,
                    Device,
                    cmdSet,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), // --
                    cleanReason
            );

            System.out.println(message);

            String failLogPath = FileInput.getLogWork() + "\\" + logType;
            synchronized (LOG_LOCK) {
                BufferedWriter nodeFail = getTelnetLogWriter(failLogPath);
                nodeFail.write(message);
                nodeFail.newLine();
                flushTelnetLogIfNeeded(failLogPath, nodeFail, message, true);
            }

            logwork("[FAIL] " + message + "\n");
            String reasonLower = cleanReason.toLowerCase(Locale.ROOT);
            if (reasonLower.contains("wrong vendor")) {
                BotGetLog_TrueCorp.recordVendorFailure();
            } else if (reasonLower.contains("auth") || reasonLower.contains("password rejected")
                    || reasonLower.contains("login failed")) {
                BotGetLog_TrueCorp.recordAuthFailure();
            } else if (reasonLower.contains("cmdset") || reasonLower.contains("cmd set")) {
                BotGetLog_TrueCorp.recordCmdSetFailure();
            } else if (reasonLower.contains("log missing") || reasonLower.contains("missing log")) {
                BotGetLog_TrueCorp.recordLogMissingFailure();
            } else if (reasonLower.contains("incomplete") || reasonLower.contains("missing last command")
                    || reasonLower.contains("missing session end")) {
                BotGetLog_TrueCorp.recordIncompleteFailure();
            } else {
                BotGetLog_TrueCorp.recordNetworkFailure();
            }

        } catch (IOException ex) {
            System.out.println(ex.toString());
        }
    }

    private static final Object LOG_LOCK = new Object();
    private static final int TELNET_LOG_BUFFER_SIZE = 16 * 1024;
    private static final long TELNET_LOG_FLUSH_INTERVAL_MS = 1500L;
    private static final java.util.Map<String, BufferedWriter> TELNET_LOG_WRITERS = new java.util.HashMap<>();
    private static final java.util.concurrent.ConcurrentMap<String, Long> TELNET_LOG_LAST_FLUSH_MS
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static BufferedWriter getTelnetLogWriter(String filePath) throws IOException {
        BufferedWriter writer = TELNET_LOG_WRITERS.get(filePath);
        if (writer == null) {
            writer = new BufferedWriter(new FileWriter(filePath, true), TELNET_LOG_BUFFER_SIZE);
            TELNET_LOG_WRITERS.put(filePath, writer);
        }
        return writer;
    }

    private static boolean shouldForceTelnetLogFlush(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase();
        return normalized.contains("[error]")
                || normalized.contains("[fail]")
                || normalized.contains("[warn]")
                || normalized.contains("[timeout")
                || normalized.contains("[auto]")
                || normalized.contains("[summary]");
    }

    private static void flushTelnetLogIfNeeded(String filePath, BufferedWriter writer, String text, boolean force) throws IOException {
        long now = System.currentTimeMillis();
        long lastFlush = TELNET_LOG_LAST_FLUSH_MS.getOrDefault(filePath, 0L);
        if (force || shouldForceTelnetLogFlush(text) || now - lastFlush >= TELNET_LOG_FLUSH_INTERVAL_MS) {
            writer.flush();
            TELNET_LOG_LAST_FLUSH_MS.put(filePath, now);
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (LOG_LOCK) {
                for (BufferedWriter writer : TELNET_LOG_WRITERS.values()) {
                    try {
                        writer.flush();
                        writer.close();
                    } catch (IOException ignore) {
                    }
                }
                TELNET_LOG_WRITERS.clear();
                TELNET_LOG_LAST_FLUSH_MS.clear();
            }
        }));
    }

    public synchronized void logwork(String logWork) {
        synchronized (LOG_LOCK) {
            try {
                String logPath = FileInput.getLogWork() + "\\" + formattedDateTimeLOG + ".txt";
                BufferedWriter log = getTelnetLogWriter(logPath);
                log.write(logWork);
                if (!logWork.endsWith("\n")) {
                    log.newLine();
                }
                flushTelnetLogIfNeeded(logPath, log, logWork, false);
            } catch (IOException ex) {
                System.out.println(ex.toString());
            }
        }
    }

//   Banner - (Huawei, ZTE, Nokia, Juniper, Raisecom)
    private String Checklogin(String pattern) {
        try {
            StringBuilder sb = new StringBuilder(4096);
            StringBuilder lowerTail = new StringBuilder(256);
            long startTime = System.currentTimeMillis();

            //  Adaptive timeout  threads 
            int threadCount = Thread.activeCount();
            int maxWait = 45000 + (threadCount * 500); //  0.5s  thread

            sleepQuietly(randomDelayMs(
                    CHECKLOGIN_DELAY_BASE_MS,
                    CHECKLOGIN_DELAY_JITTER_MS));

            while (true) {
                if (in == null) {
                    break;
                }
                if (in.available() <= 0) {
                    if (System.currentTimeMillis() - startTime > maxWait) {
                        logwork("[TIMEOUT-Checklogin] Exceeded " + maxWait + "ms waiting for prompt '" + pattern + "'\n");
                        return "[TIMEOUT-Checklogin]";
                    }
                    sleepQuietly(25);
                    continue;
                }

                int c = in.read();
                if (c == -1) {
                    break;
                }
                char ch = (char) c;
                sb.append(ch);
                appendLowerTail(lowerTail, ch, READ_MATCH_WINDOW_CHARS);

                if (shouldInspectReadBuffer(ch, sb.length())) {
                    boolean warningBanner = containsAny(lowerTail, CHECKLOGIN_WARNING_BANNER_TOKENS);

                    if (warningBanner) {
                        if (containsAny(lowerTail, READ_AUTH_PROMPTS) || lowerTail.indexOf("user name:") >= 0) {
                            logwork("[BANNER-DETECTED] Found login prompt after warning banner\n");
                            return sb.toString();
                        }
                        continue;
                    }

                    if (containsAny(lowerTail, CHECKLOGIN_BANNER_NOISE_TOKENS)) {
                        continue;
                    }

                    if (containsAny(lowerTail, CHECKLOGIN_PROMPT_TOKENS)) {
                        if (sb.length() > 20) {
                            sleepQuietly(PROMPT_STABILIZE_DELAY_MS);
                        }
                        return sb.toString();
                    }

                    //   node  error 
                    if (containsAny(lowerTail, CHECKLOGIN_FAIL_TOKENS)) {
                        return "Login_failed";
                    }
                }

                if (sb.length() > 12000) {
                    sb.delete(0, sb.length() - 8000);
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            logwork("[TIMEOUT-Checklogin] Socket timeout while waiting for '" + pattern + "'\n");
            return "[TIMEOUT-Checklogin]";
        } catch (Exception e) {
            logwork("[ERROR-Checklogin] " + e + "\n");
        }
        return null;
    }

    private boolean checkEnvCommand(String cmdSet, String Loopback, String Device, int Num_row) {
        boolean vendorError = false;
        String promptChar = "#";
        String envCmd = "";

        //   prefix
        if (cmdSet.charAt(0) == 'H') {
            promptChar = ">";
            envCmd = "screen-length 0 temporary";
        } else if (cmdSet.charAt(0) == 'N') {
            promptChar = "#";
            envCmd = "environment no more";
        } else if (cmdSet.charAt(0) == 'Z') {
            promptChar = "#";
            envCmd = "terminal length 0";
        } else {
            return true; //  vendor H/N/Z  
        }

        try {

            write(envCmd);
            String resp = readUntil(promptChar);
            logwork("[ENV SET] " + envCmd + " response: " + resp + "\n");
            if (resp == null) {
                resp = "";
            }

            updateRuntimeDeviceNameFromResponse(resp, Device, cmdSet);

            //   tolerance   WrongVendor --
            //  resp.contains("^")  
            if (resp.toLowerCase().contains("invalid") || resp.toLowerCase().contains("unknown")) {
                logwork("[WARN] '" + envCmd + "' might not be supported, skipping vendor check\n");
                System.out.println("[WARN] '" + envCmd + "' might not be supported, continue execution...");
                //   wrong vendor  
                return true;
            }

        } catch (Exception e) {
            logwork("[ERROR] checkEnvCommand: " + e + "\n");
            System.out.println("[ERROR] checkEnvCommand: " + e);
            return true; // 
        }

        return true; // 
    }

    private boolean checkVendorLoginPrompt(String expectedPrompt, String wrongPrompt,
            String vendorName, String Loopback,
            String Device, String cmdSet,
            int Num_row, StringBuilder LOG) {
        //   pre-read banner/prompt  Loopback  (auto-vendor) 
        String lowLog = (LOG == null) ? "" : LOG.toString().toLowerCase();
        if (lowLog.contains("username:") || lowLog.contains("user name:") || lowLog.contains("login:") || lowLog.contains("ogin:")) {
            return true;
        }

        sleepQuietly(randomDelayMs(
                CHECKLOGIN_DELAY_BASE_MS,
                CHECKLOGIN_DELAY_JITTER_MS));

        String checkResult = Checklogin(expectedPrompt);

        //  retry  3  ( 2)
        int retryCount = 0;
        while ((checkResult == null || checkResult.contains("[TIMEOUT-Checklogin]")) && retryCount < 3) {
            retryCount++;
            logwork("[RETRY] Prompt not detected (" + retryCount + "/3) for " + Loopback + "\n");
            sleepQuietly(randomDelayMs(
                    PROMPT_RETRY_DELAY_BASE_MS,
                    PROMPT_RETRY_DELAY_JITTER_MS));
            checkResult = Checklogin(expectedPrompt);
        }

        //   retry
        if (checkResult == null || checkResult.contains("[TIMEOUT-Checklogin]")) {
            //  retry - force read
            logwork("[FORCE-RETRY] Second layer check for " + Loopback + "\n");
            String force = readUntil("ogin:");
            if (force != null && force.toLowerCase().contains("ogin:")) {
                logwork("[FORCE-RETRY] Prompt found on force read  continue " + Loopback + "\n");
                LOG.append(force);
                return true;
            }
//  -  prompt - (ZTE / Huawei banner)
            if (force == null || !force.toLowerCase().contains("ogin:")) {
                String all = readUntilStable(":");
                if (all.toLowerCase().contains("username") || all.toLowerCase().contains("login")) {
                    logwork("[RECOVER] Prompt recovered in second read for " + Loopback + "\n");
                    LOG.append(all);
                    return true;
                }
            }

            System.out.println("\n[ERROR] Wrong vendor or no prompt: Expected " + vendorName
                    + " but got none/timeout from " + Loopback);
            logwork("\n[ERROR] Wrong vendor or no prompt: Expected " + vendorName
                    + " but got none/timeout from " + Loopback + "\n");
            Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Wrong vendor or no prompt]");
            disconnect();
            return false;
        }

        //   prompt - critical ( login: vs Username:)
        if (checkResult.toLowerCase().contains(wrongPrompt.toLowerCase())
                && !checkResult.toLowerCase().contains(expectedPrompt.toLowerCase())) {
            System.out.println("\n[WARN] Prompt mismatch (continue): Expected "
                    + vendorName + " found " + wrongPrompt + " at " + Loopback);
            logwork("\n[WARN] Prompt mismatch but continuing: " + checkResult + "\n");
        }

        LOG.append(checkResult);

        //   login fail 
        if (LOG.toString().contains("Login_failed")) {
            Connection_failed(Num_row, Loopback, Device, cmdSet, "_[Connection failed]");
            disconnect();
            return false;
        }

        sleepQuietly(POST_LOGIN_FINAL_DELAY_MS); //  delay  login
        return true;
    }

//   LOG  TIMEOUT 
    private boolean isTimeoutLog() {
        if (LOG == null) {
            return false;
        }
        String log = LOG.toString();
        return log.contains("[TIMEOUT]")
                || log.contains("[TIMEOUT-READ]")
                || log.contains("[TIMEOUT-READ-LIMIT]");
    }
//   output  

    public String readUntilStable(String pattern) {
        StringBuilder all = new StringBuilder();
        String lastChunk;
        int idleCount = 0;
        int maxIdle = 3; //   3 

        do {
            lastChunk = readUntil(pattern);
            if (lastChunk != null) {
                all.append(lastChunk);
            }

            if (lastChunk == null || lastChunk.contains("[TIMEOUT-READ]")) {
                idleCount++;
            } else {
                idleCount = 0;
            }

            try {
                Thread.sleep(100); //   node 
            } catch (InterruptedException ignored) {
            }

        } while (idleCount < maxIdle);

        return all.toString();
    }

    //   Banner/Prompt  Enter Loopback ( user/pass) 
    // -  (username/login/password) -
    private String readPreLoginBanner(int totalTimeoutMs) {
        StringBuilder sb = new StringBuilder(4096);
        StringBuilder lowerTail = new StringBuilder(256);
        long start = System.currentTimeMillis();

        try {
            while (System.currentTimeMillis() - start < totalTimeoutMs) {
                try {
                    if (in == null) {
                        break;
                    }
                    if (in.available() <= 0) {
                        sleepQuietly(currentReadPollDelayMs());
                        continue;
                    }

                    int c = in.read(); //  telnet.setSoTimeout(...)  throw SocketTimeoutException
                    if (c == -1) {
                        break;
                    }

                    char ch = (char) c;
                    sb.append(ch);
                    appendLowerTail(lowerTail, ch, READ_MATCH_WINDOW_CHARS);

                    if (shouldInspectReadBuffer(ch, sb.length())
                            && (containsAny(lowerTail, PRELOGIN_STOP_TOKENS)
                            || containsPreLoginReadyPrompt(sb))) {
                        return sb.toString();
                    }

                    //  buffer 
                    if (sb.length() > 12000) {
                        sb.delete(0, sb.length() - 8000);
                    }
                } catch (java.net.SocketTimeoutException ste) {
                    //   
                }
            }
        } catch (Exception e) {
            logwork("[WARN-readPreLoginBanner] " + e + "\n");
        }
        return sb.toString();
    }

//   Telnet  Dynamic  Turbo/Normal
//   Telnet  Dynamic  Turbo/Normal
    public static synchronized void updateTelnetLimit(boolean turboMode) {
        int cpu = Runtime.getRuntime().availableProcessors();
        int previousLimit = currentTelnetLimit;
        int newLimit = turboMode ? getTurboTelnetLimit() : NORMAL_TELNET_LIMIT;
        int diff = newLimit - previousLimit;

        if (diff > 0) {
            TELNET_LIMIT.release(diff);
        } else if (diff < 0) {
            TELNET_LIMIT.shrinkPermits(-diff);
        }

        currentTelnetLimit = newLimit;
        int active = getActivePermits();

        //  log -
        System.out.printf("[%s]  Telnet limit adjusted: %d -> %d (%s mode) | CPU: %d cores | Active permits: %d%n",
                java.time.LocalTime.now().withNano(0),
                previousLimit, newLimit,
                turboMode ? "TURBO" : "NORMAL",
                cpu,
                active);
    }

    private static int getActivePermits() {
        return Math.max(0, currentTelnetLimit - TELNET_LIMIT.availablePermits());
    }

    private String buildDailyLogFileName(String Loopback, String Device, String cmdSet, int Num_row) {
        String dateTag = resolveActiveLogDateTag(Loopback, Device, cmdSet, Num_row);
        return buildDailyLogFileName(Loopback, Device, cmdSet, Num_row, dateTag);
    }

    private String resolveActiveLogDateTag(String Loopback, String Device, String cmdSet, int Num_row) {
        String identity = Num_row + "|"
                + sanitizeFileNameComponent(Loopback) + "|"
                + resolveLogDeviceName(Device) + "|"
                + sanitizeFileNameComponent(cmdSet);
        if (!identity.equals(activeLogSessionIdentity) || activeLogDateTag == null || activeLogDateTag.isEmpty()) {
            activeLogSessionIdentity = identity;
            activeLogDateTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        return activeLogDateTag;
    }

    private String buildDailyLogFileName(String Loopback, String Device, String cmdSet, int Num_row, String dateTag) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String safeDateTag = dateTag == null || dateTag.trim().isEmpty()
                ? LocalDateTime.now().format(dtf)
                : dateTag.trim();
        return String.format("[%d]%s_%s_%s_%s.txt",
                Num_row,
                sanitizeFileNameComponent(Loopback),
                resolveLogDeviceName(Device),
                sanitizeFileNameComponent(cmdSet),
                safeDateTag);
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

    private static String extractVendorPrefix(String cmdSet) {
        if (cmdSet == null) {
            return "HW";
        }
        String value = cmdSet.trim();
        int dash = value.indexOf('-');
        if (dash > 0) {
            value = value.substring(0, dash).trim();
        }
        if (value.isEmpty()) {
            return "HW";
        }
        return value.toUpperCase();
    }

    private static String detectVendorFromText(String text, String fallbackVendor) {
        String fallback = (fallbackVendor == null || fallbackVendor.trim().isEmpty())
                ? "HW"
                : fallbackVendor.trim().toUpperCase();

        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }

        if ("N".equals(fallback)) {
            return "HW";
        }
        return fallback;
    }

    private static String detectVendorFromPrompt(String text, String fallbackVendor, boolean preLoginNokia) {
        String fallback = (fallbackVendor == null || fallbackVendor.trim().isEmpty())
                ? "HW"
                : fallbackVendor.trim().toUpperCase();

        if (text == null || text.trim().isEmpty()) {
            if ("N".equals(fallback)) {
                return "HW";
            }
            return fallback;
        }

        String promptToken = extractPromptToken(text);
        if (!promptToken.isEmpty()) {
            char lastChar = promptToken.charAt(promptToken.length() - 1);
            if (lastChar == '>') {
                return "HW";
            }
            if (lastChar == '#') {
                return promptToken.contains(":") ? "N" : "ZTE";
            }
        }

        String low = text.toLowerCase();
        if (low.matches("(?s).*:[^\\r\\n]*#.*")) {
            return "N";
        }
        if (low.matches("(?s).*<[^\\r\\n>]+>.*") || low.contains(">")) {
            return "HW";
        }
        if (low.contains("#")) {
            return "ZTE";
        }
        if (preLoginNokia) {
            return "N";
        }
        if ("N".equals(fallback)) {
            return "HW";
        }
        return fallback;
    }

    private static String detectVendorFromLogLine(String line) {
        if (line == null) {
            return "";
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.matches("(?i)^\\*?a:[^\\r\\n#]*#.*")) {
            return "N";
        }
        if (trimmed.matches("^<[^\\r\\n>]+>.*")) {
            return "HW";
        }
        if (trimmed.matches("(?i)^[^\\s<:][^\\r\\n#]*#.*")) {
            return "ZTE";
        }
        return "";
    }

    private static String detectVendorFromLog(File logFile, String fallbackCmdSet) {
        String fallbackVendor = extractVendorPrefix(fallbackCmdSet);
        if (fallbackVendor == null || fallbackVendor.trim().isEmpty()) {
            fallbackVendor = "HW";
        }
        if (logFile == null || !logFile.exists()) {
            return fallbackVendor;
        }

        StringBuilder sample = new StringBuilder(2048);
        try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            int lineCount = 0;
            while ((line = br.readLine()) != null && lineCount < 200 && sample.length() < 16384) {
                String detected = detectVendorFromLogLine(line);
                if (!detected.isEmpty()) {
                    return detected;
                }
                if (!line.trim().isEmpty()) {
                    sample.append(line).append('\n');
                }
                lineCount++;
            }
        } catch (IOException e) {
            System.out.println("[MONITOR]  Failed to detect vendor from log: " + logFile.getName() + " -> " + e.getMessage());
            return fallbackVendor;
        }

        String sampleText = sample.toString();
        String low = sampleText.toLowerCase();
        if (low.contains("environment no more")) {
            return "N";
        }
        if (low.contains("screen-length 0")) {
            return "HW";
        }
        if (low.contains("terminal length 0")) {
            return "ZTE";
        }
        return detectVendorFromPrompt(sampleText, fallbackVendor, false);
    }

    private static String adjustCmdSetVendorFromLog(File logFile, String cmdSet) {
        if (cmdSet == null || cmdSet.trim().isEmpty() || cmdSet.indexOf('-') < 0) {
            return cmdSet == null ? "" : cmdSet.trim();
        }
        String detectedVendor = detectVendorFromLog(logFile, cmdSet);
        return detectedVendor + cmdSet.substring(cmdSet.indexOf('-'));
    }

    private int reloadCommandsFromExcel(PathFile fileInput, String cmdSet, String[] targetCommand) {
        int newR = BotGetLog_TrueCorp.copyCachedCommands(cmdSet, targetCommand);
        if (newR > 0) {
            return newR;
        }

        try ( Workbook wb = WorkbookFactory.create(new File(fileInput.getUserInterface_Input()))) {
            Sheet sheet = wb.getSheet("cmdSet");
            if (sheet != null && sheet.getRow(0) != null) {
                for (int j = 0; j < sheet.getRow(0).getLastCellNum(); j++) {
                    if (sheet.getRow(0).getCell(j) == null) {
                        continue;
                    }
                    String header = BotGetLog_TrueCorp.getCellValue(sheet.getRow(0).getCell(j));
                    if (header != null && cmdSet.equalsIgnoreCase(header.trim())) {
                        Arrays.fill(targetCommand, null);
                        for (int rowIdx = 0; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                            Row currentRow = sheet.getRow(rowIdx);
                            if (currentRow != null && currentRow.getCell(j) != null) {
                                String cellValue = BotGetLog_TrueCorp.getCellValue(currentRow.getCell(j));
                                if (cellValue != null && !cellValue.trim().isEmpty() && newR < targetCommand.length) {
                                    targetCommand[newR] = cellValue;
                                    newR++;
                                }
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logwork("[WARN] Reload cmdSet from Excel failed: " + e + "\n");
        }

        return newR;
    }

    private static boolean isEquivalentCmdSet(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizeCmdSetFamily(left).equals(normalizeCmdSetFamily(right));
    }

    private static String extractCmdSetFromLogName(String fileName) {
        if (fileName == null) {
            return "";
        }
        Matcher matcher = DAILY_LOG_FILE_PATTERN.matcher(fileName);
        if (matcher.find()) {
            return matcher.group(4);
        }
        return "";
    }

    private static boolean hasStrongSessionEnd(String tail) {
        if (tail == null) {
            return false;
        }
        String low = tail.toLowerCase();
        if (low.contains("enter ip address [press q")) {
            return true;
        }

        boolean hasClose = low.contains("connection closed")
                || low.contains("foreign host")
                || low.contains("logout");
        boolean hasExitSignal = low.contains("script done")
                || low.contains("quit");
        return hasClose && hasExitSignal;
    }

    private static boolean isExpectedStandaloneExitCompletion(File logFile, String command, String detail) {
        if (!isStandaloneNodeExitCommand(command)) {
            return false;
        }

        String normalizedDetail = safeTrim(detail).toLowerCase(Locale.ROOT);
        if (normalizedDetail.contains("timed out")
                || normalizedDetail.contains("unreachable")
                || normalizedDetail.contains("refused")
                || normalizedDetail.contains("network is unreachable")
                || normalizedDetail.contains("connection reset")) {
            return false;
        }

        if (logFile == null || !logFile.exists()) {
            return false;
        }

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
            long fileLength = raf.length();
            long seekPos = Math.max(0, fileLength - 4096);
            raf.seek(seekPos);
            byte[] buf = new byte[(int) (fileLength - seekPos)];
            raf.readFully(buf);
            String tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

            if (tail.contains("enter ip address")) {
                return true;
            }

            boolean hasClose = tail.contains("foreign host")
                    || tail.contains("connection closed")
                    || tail.contains("logout");
            boolean hasExitSignal = tail.contains("script done") || tail.contains("quit");
            return hasClose && hasExitSignal;
        } catch (Exception e) {
            System.out.println("[EXIT-CHECK-FAIL] Cannot inspect exit tail: " + logFile.getName() + " -> " + e.getMessage());
            return false;
        }
    }

    private static boolean containsWrongVendorSignal(String text) {
        if (text == null) {
            return false;
        }
        return WRONG_VENDOR_SIGNAL_PATTERN.matcher(text.toLowerCase()).matches();
    }

    static boolean hasWrongVendorSignal(File logFile) {
        if (logFile == null || !logFile.exists()) {
            return false;
        }
        try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (containsWrongVendorSignal(line)) {
                    return true;
                }
            }
        } catch (IOException e) {
            System.out.println("[MONITOR]  Failed to inspect wrong-vendor signal: " + logFile.getName() + " -> " + e.getMessage());
        }
        return false;
    }

    private File findEquivalentCompletedLog(String loopback, String device, String cmdSet, int numRow) {
        try {
            File logDir = new File(FileInput.getLog());
            if (!logDir.exists() || !logDir.isDirectory()) {
                return null;
            }

            File[] files = logDir.listFiles((dir, name)
                    -> name != null
                    && name.startsWith("[" + numRow + "]")
                    && name.contains(loopback + "_")
                    && name.endsWith(".txt"));

            if (files == null || files.length == 0) {
                return null;
            }

            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });

            for (File file : files) {
                if (!matchesDailyLogTarget(file.getName(), numRow, loopback, cmdSet)) {
                    continue;
                }
                if (isCompleteExistingLog(file)) {
                    return file;
                }
            }
        } catch (Exception e) {
            System.out.println("[PRE-CHECK-FAIL] Cannot search equivalent completed log: " + e.getMessage());
        }
        return null;
    }

    private boolean isCompleteExistingLog(File logFile) {
        if (logFile == null || !logFile.exists()) {
            return false;
        }
        if (hasWrongVendorSignal(logFile)) {
            return false;
        }

        try ( java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
            long fileLength = raf.length();
            long seekPos = Math.max(0, fileLength - 8192);
            raf.seek(seekPos);
            byte[] buf = new byte[(int) (fileLength - seekPos)];
            raf.readFully(buf);
            String tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8).toLowerCase();

            return hasStrongSessionEnd(tail);
        } catch (Exception e) {
            System.out.println("[PRE-CHECK-FAIL] Cannot inspect existing log: " + logFile.getName() + " -> " + e.getMessage());
            return false;
        }
    }

    private static String readLogHead(File logFile, int maxLines) {
        if (logFile == null || !logFile.exists()) {
            return "";
        }
        try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
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
            System.out.println("[HEAD-CHECK-FAIL] Cannot read log head: " + logFile.getName() + " -> " + e.getMessage());
        }
        return "";
    }

    private static boolean isValidLogHead(File logFile, String device, String cmdSet) {
        String head = readLogHead(logFile, 20);
        if (head.isEmpty()) {
            return false;
        }

        String safeHead = head.toLowerCase();
        String comparableHead = safeHead.replace('_', '-');
        String safeDevice = device == null ? "" : device.trim().toLowerCase();
        String comparableDevice = safeDevice.replace('_', '-');
        String safeCmdSet = cmdSet == null ? "" : cmdSet.trim().toLowerCase();

        if (safeHead.contains("enter ip address") || safeHead.contains("connection closed") || safeHead.contains("script done")) {
            return false;
        }

        if (safeCmdSet.startsWith("zte-")) {
            return comparableHead.startsWith(comparableDevice + "#") && safeHead.contains("terminal length 0");
        }
        if (safeCmdSet.startsWith("n-")) {
            return comparableHead.startsWith("a:" + comparableDevice + "#") && safeHead.contains("environment no more");
        }
        if (safeCmdSet.startsWith("hw-")) {
            return comparableHead.startsWith("<" + comparableDevice + ">") && safeHead.contains("screen-length 0");
        }

        return (comparableHead.contains(comparableDevice) && (safeHead.contains("#") || safeHead.contains(">")));
    }

    private File prepareFreshLogFile(String Loopback, String Device, String cmdSet, int Num_row) {
        File logDir = new File(FileInput.getLog());
        if (!logDir.exists()) {
            logDir.mkdirs();
        }

        String fileName = buildDailyLogFileName(Loopback, Device, cmdSet, Num_row);
        File logFile = new File(logDir, fileName);
        String sessionKey = logFile.getAbsolutePath();

        if (activeLogSessionPath != null && !activeLogSessionPath.equals(sessionKey)) {
            activeLogSessions.remove(activeLogSessionPath);
        }
        activeLogSessionPath = sessionKey;
        activeLogSessions.add(sessionKey);

        //  
        if (!sessionKey.equals(preparedLogSessionKey)) {
            if (logFile.exists() && !isValidLogHead(logFile, resolveLogDeviceName(Device), cmdSet)) {
                System.out.println("[PRE-HEAD-INVALID] Invalid log head, delete before new session: " + logFile.getName());
                if (!logFile.delete()) {
                    System.out.println("[PRE-HEAD-INVALID] Cannot delete invalid-head log: " + logFile.getAbsolutePath());
                }
            }

            if (logFile.exists()) {
                if (isCompleteExistingLog(logFile)) {
                    System.out.println("[PRE-SKIP] Keep completed log file: " + logFile.getName());
                    preparedLogSessionKey = sessionKey;
                    return logFile;
                }

                if (logFile.delete()) {
                    System.out.println("[PRE-CLEAR] Removed existing incomplete log before new run: " + logFile.getName());
                } else {
                    System.out.println("[PRE-CLEAR-FAIL] Cannot remove existing log before new run: " + logFile.getAbsolutePath());
                }
            }

            File[] duplicateFiles = logDir.listFiles((dir, name)
                    -> name != null
                    && name.endsWith(".txt")
                    && matchesDailyLogTarget(name, Num_row, Loopback, cmdSet));

            if (duplicateFiles != null) {
                for (File oldFile : duplicateFiles) {
                    if (!oldFile.getAbsolutePath().equals(logFile.getAbsolutePath())) {
                        if (isCompleteExistingLog(oldFile)) {
                            System.out.println("[PRE-KEEP] Keep old completed duplicate: " + oldFile.getName());
                            continue;
                        }

                        boolean deleted = oldFile.delete();
                        if (deleted) {
                            System.out.println("[PRE-DELETE] Removed old incomplete duplicate: " + oldFile.getName());
                        } else {
                            System.out.println("[PRE-DELETE-FAIL] Cannot delete old duplicate: " + oldFile.getAbsolutePath());
                        }
                    }
                }
            }

            preparedLogSessionKey = sessionKey;
        }

        return logFile;
    }
//   ( RAM) 

    public CommandReadResult readStreamToFile(String Loopback, String Device, String cmdSet, int Num_row, String command) {
        byte[] buffer = new byte[8192]; // buffer 8KB
        int bytesRead;
        boolean sshGatewayConnection = isSshGatewayConnection();
        String consolePrefix = buildConsoleNodePrefix(Num_row, Loopback, Device);
        CommandReadResult result = CommandReadResult.prompt();

        File logFile = prepareFreshLogFile(Loopback, Device, cmdSet, Num_row);
        System.out.println("[LOG]" + consolePrefix + " " + logFile.getName());

        try ( BufferedOutputStream outFile = new BufferedOutputStream(new FileOutputStream(logFile, true))) {
            long startTime = System.currentTimeMillis();
            long lastData = startTime;
            final long maxWaitMs = getCommandMaxWaitMs();
            final long idleTimeoutMs = getCommandIdleTimeoutMs();

            List<String> promptCandidates = expandStreamPromptCandidates(
                    buildPromptCandidates(logFile, Device, cmdSet, s),
                    sshGatewayConnection,
                    command);
            String[] safePatterns = promptCandidates.toArray(new String[0]);
            String[] normalizedPatterns = normalizePatterns(safePatterns);
            System.out.println(formatPromptWaitMessage(Num_row, Loopback, Device, promptCandidates, command));

            //  
            if (sshGatewayConnection) {
                drainPendingSshStreamToLog(outFile);
                appendSentCommandToStreamLog(outFile, resolveSshCommandLogPrompt(Device, cmdSet), command);
            }
            write(command);
            sleepQuietly(sshGatewayConnection ? 15 : 150);

            StringBuilder response = new StringBuilder(1024);
            StringBuilder lowerTail = new StringBuilder(512);
            boolean waitForPrompt = !promptCandidates.isEmpty();

            if (sshGatewayConnection) {
                result = readStreamToFileSsh(outFile, promptCandidates, startTime, maxWaitMs, idleTimeoutMs, consolePrefix);
                if (result.promptDetected) {
                    System.out.println("[PROMPT-OK]" + consolePrefix + " SSH prompt/menu");
                }
            } else {
                result = CommandReadResult.ioError("stream ended before prompt");
                while ((bytesRead = in.read(buffer)) != -1) {
                    if (bytesRead > 0) {
                        outFile.write(buffer, 0, bytesRead);

                        String chunk = new String(buffer, 0, bytesRead);
                        appendLowerTail(lowerTail, chunk, READ_MATCH_WINDOW_CHARS);

                        if (waitForPrompt) {
                            response.append(chunk);
                            if (response.length() > 4096) {
                                response.delete(0, response.length() - 4096);
                            }
                        }
                        lastData = System.currentTimeMillis();

                        String lowerSnapshot = lowerTail.toString();
                        if (containsRemoteSessionClosedText(lowerSnapshot)) {
                            result = CommandReadResult.remoteClosed(summarizeRemoteSessionCloseReason(lowerSnapshot));
                            break;
                        }

                        if (containsPaginationMarker(lowerTail)) {
                            sendPaginationSpace(out, lowerTail);
                        }

                        //   prompt   
                        char inspectChar = chunk.charAt(chunk.length() - 1);
                        if (waitForPrompt && shouldInspectReadBuffer(inspectChar, response.length())) {
                            String promptToken = extractPromptToken(lowerTail);
                            if (matchesAnyPattern(lowerTail, promptToken, safePatterns, normalizedPatterns)
                                    || isInteractivePromptToken(promptToken)
                                    || containsGatewayMenuPrompt(lowerSnapshot)) {
                                System.out.println("[PROMPT-OK]" + consolePrefix + " "
                                        + summarizePromptForConsole(promptToken, response.toString(), Device, cmdSet));
                                waitForPrompt = false;
                                result = CommandReadResult.prompt();
                            }
                            if (!waitForPrompt) {
                                break;
                            }
                        }
                    }

                    //  prompt   
                    long now = System.currentTimeMillis();
                    if (now - lastData > COMMAND_WAIT_LOG_INTERVAL_MS) {
                        System.out.println("[WAITING]" + consolePrefix + " prompt "
                                + formatDurationSeconds(now - startTime));
                        lastData = now;
                    }

                    if (now - startTime > maxWaitMs) {
                        String detail = "prompt wait exceeded " + formatDurationSeconds(maxWaitMs);
                        System.out.println("[END STREAM]" + consolePrefix + " " + detail);
                        result = CommandReadResult.timeout(detail);
                        break;
                    }
                }
            }

            double fileSizeKB = logFile.length() / 1024.0;
            System.out.printf("[LOG-SAVE]%s %s (%.1f KB)%n", consolePrefix, logFile.getName(), fileSizeKB);

        } catch (IOException e) {
            System.out.println("[ERROR-stream]" + consolePrefix + " " + e.getMessage());
            result = containsRemoteSessionClosedText(e.getMessage())
                    ? CommandReadResult.remoteClosed(summarizeRemoteSessionCloseReason(e.getMessage()))
                    : CommandReadResult.ioError(e.getMessage());
        }

        normalizeSshCommandTranscript(logFile, command);
        if (result.remoteClosed && isExpectedStandaloneExitCompletion(logFile, command, result.detail)) {
            System.out.println("[EXIT-OK]" + consolePrefix + " "
                    + summarizeCommandForConsole(command) + " -> " + result.detail);
            return CommandReadResult.prompt();
        }
        if (result.remoteClosed) {
            boolean recovered = recoverRemoteClosedSession(Loopback, Device, cmdSet, Num_row, command, result.detail);
            return recovered
                    ? CommandReadResult.remoteClosedRecovered(result.detail)
                    : result;
        }
        return result;
    }

    private boolean executeCommandWithReconnect(String Loopback, String Device, String cmdSet, int Num_row, String command) {
        CommandReadResult result = readStreamToFile(Loopback, Device, cmdSet, Num_row, command);
        if (result == null) {
            return true;
        }
        if (result.promptDetected || (result.remoteClosed && result.recovered)) {
            return true;
        }

        String consolePrefix = buildConsoleNodePrefix(Num_row, Loopback, Device);
        String commandSummary = summarizeCommandForConsole(command);
        String detail = result.detail.isEmpty() ? "unknown error" : result.detail;

        if (result.remoteClosed) {
            String message = "[FAIL-REMOTE]" + consolePrefix + " " + commandSummary + " -> " + detail;
            System.out.println(message);
            logwork(message + "\n");
            recordSessionFailureOnce(Num_row, Loopback, Device, cmdSet, "_[Connection failed - remote closed]");
        } else if (result.timedOut) {
            String message = "[FAIL-TIMEOUT]" + consolePrefix + " " + commandSummary + " -> " + detail;
            System.out.println(message);
            logwork(message + "\n");
            recordSessionFailureOnce(Num_row, Loopback, Device, cmdSet, "_[Connection failed - command timeout]");
        } else if (result.ioError) {
            String message = "[FAIL-STREAM]" + consolePrefix + " " + commandSummary + " -> " + detail;
            System.out.println(message);
            logwork(message + "\n");
            recordSessionFailureOnce(Num_row, Loopback, Device, cmdSet, "_[Connection failed - stream error]");
        }

        failedAfterPassword = true;
        disconnect();
        return false;
    }

    private void recordSessionFailureOnce(int Num_row, String Loopback, String Device, String cmdSet, String reason) {
        if (sessionFailureRecorded) {
            return;
        }
        sessionFailureRecorded = true;
        Connection_failed(Num_row, Loopback, Device, cmdSet, reason);
    }

    private boolean recoverRemoteClosedSession(String Loopback, String Device, String cmdSet,
            int Num_row, String command, String detail) {
        String consolePrefix = buildConsoleNodePrefix(Num_row, Loopback, Device);
        String reason = detail == null || detail.isEmpty() ? "remote session closed" : detail;
        String summary = summarizeCommandForConsole(command);

        System.out.println("[RECOVER]" + consolePrefix + " " + summary + " -> " + reason + " | reconnecting");
        logwork("[RECOVER] " + Loopback + " (" + Device + ", " + cmdSet + ") after "
                + summary + " -> " + reason + "\n");

        try {
            resetGatewayConnection();
            String gatewayReady = connectGateway(gatewayServerAddress, gatewayUsername, gatewayPassword);
            if (gatewayReady != null && !gatewayReady.isEmpty()) {
                LOG.append(gatewayReady);
            }

            writeLoopBack(Loopback);
            String preLoginOut = readPreLoginBanner(12000);
            if (preLoginOut != null && !preLoginOut.isEmpty()) {
                LOG.append(preLoginOut);
            }

            if (!restoreNodeSessionAfterReconnect(Loopback, Device, cmdSet, Num_row, preLoginOut)) {
                System.out.println("[RECOVER-FAIL]" + consolePrefix + " session restore failed after " + summary);
                logwork("[RECOVER-FAIL] Session restore failed after " + summary + " on " + Loopback + "\n");
                return false;
            }

            System.out.println("[RECOVER-OK]" + consolePrefix + " session restored after " + summary);
            logwork("[RECOVER-OK] Session restored after " + summary + " on " + Loopback + "\n");
            return true;
        } catch (Exception e) {
            System.out.println("[RECOVER-FAIL]" + consolePrefix + " reconnect error: " + e.getMessage());
            logwork("[RECOVER-FAIL] Reconnect error on " + Loopback + " -> " + e + "\n");
            return false;
        }
    }

    private boolean restoreNodeSessionAfterReconnect(String Loopback, String Device, String cmdSet,
            int Num_row, String preLoginOut) {
        boolean preLoginNokia = preLoginOut != null && preLoginOut.toLowerCase(Locale.ROOT).contains("ogin:");
        char loginVendorFamily = preLoginNokia ? 'N' : (cmdSet.charAt(0) == 'N' ? 'H' : cmdSet.charAt(0));
        boolean sshNodeLoginFlow = isSshGatewayConnection()
                && (cmdSet.charAt(0) == 'H' || cmdSet.charAt(0) == 'N' || cmdSet.charAt(0) == 'Z');

        if (sshNodeLoginFlow) {
            String resp = completeSshNodeLogin(Loopback, Device, cmdSet, preLoginOut, nodeUsername, nodePassword);
            appendLoginTranscriptDelta(LOG, preLoginOut, resp);
            if (resp == null || resp.isEmpty()
                    || resp.contains("[TIMEOUT-READ]")
                    || resp.contains("[TIMEOUT]")
                    || !hasNodeLoginSignal(resp)
                    || containsTransportFailureText(resp)) {
                logwork("[RECOVER-FAIL] SSH node login did not return a usable response at "
                        + Loopback + " | response: " + summarizeResponseForLog(resp, nodeUsername, nodePassword) + "\n");
                return false;
            }
            if (containsLoginFailureText(resp)
                    || (containsAuthPromptText(resp) && !hasInteractivePromptToken(resp))) {
                logwork("[RECOVER-FAIL] Username or password rejected during reconnect at "
                        + Loopback + " | response: " + summarizeResponseForLog(resp, nodeUsername, nodePassword) + "\n");
                return false;
            }
            updateRuntimeDeviceNameFromResponse(resp, Device, cmdSet);
            return checkEnvCommand(cmdSet, Loopback, Device, Num_row);
        }

        if (loginVendorFamily == 'H' || loginVendorFamily == 'Z') {
            sleepQuietly(randomDelayMs(LOGIN_PROMPT_DELAY_BASE_MS, LOGIN_PROMPT_DELAY_JITTER_MS));
            if (!checkVendorLoginPrompt("Username:", "ogin:", "H/Z", Loopback, Device, cmdSet, Num_row, LOG)) {
                return false;
            }
            write_NoShow(nodeUsername);
        } else if (loginVendorFamily == 'N') {
            sleepQuietly(randomDelayMs(LOGIN_PROMPT_DELAY_BASE_MS, LOGIN_PROMPT_DELAY_JITTER_MS));
            if (!checkVendorLoginPrompt("ogin:", "Username:", "N", Loopback, Device, cmdSet, Num_row, LOG)) {
                return false;
            }
            write_NoShow(nodeUsername);
        } else if (cmdSet.charAt(0) == 'J') {
            LOG.append(Checklogin("ogin:"));
            if (LOG.toString().contains("Login_failed")) {
                return false;
            }
            write_NoShow(nodeUsername);
        } else if (cmdSet.charAt(0) == 'O') {
            LOG.append(Checklogin(":"));
            if (LOG.toString().contains("Login_failed")) {
                return false;
            }
            write_NoShow(nodeUsername);
        } else if (cmdSet.charAt(0) == 'L') {
            LOG.append(Checklogin("Username:"));
            if (LOG.toString().contains("Login_failed")) {
                return false;
            }
            write_NoShow(l2Username);
        }

        if (!sshNodeLoginFlow) {
            if (cmdSet.charAt(0) == 'L') {
                LOG.append(readUntil("ssword:"));
                write_NoShow(l2Password);
            } else if (cmdSet.charAt(0) == 'O') {
                LOG.append(readUntil(":"));
                write_NoShow(nodePassword);
            } else {
                LOG.append(readUntil("ssword:"));
                write_NoShow(nodePassword);
            }

            if (cmdSet.charAt(0) == 'J') {
                String resp = readUntil(">");
                LOG.append(resp);
                if (resp == null
                        || resp.contains("[TIMEOUT-READ]")
                        || resp.contains("[TIMEOUT]")
                        || resp.contains("[TIMEOUT-READ-LIMIT]")) {
                    return false;
                }
                write("");
            } else if (cmdSet.charAt(0) == 'H' || cmdSet.charAt(0) == 'N' || cmdSet.charAt(0) == 'Z') {
                if (isTimeoutLog()) {
                    return false;
                }

                String resp = waitForPostPasswordPrompt(Loopback, nodeUsername, nodePassword);
                LOG.append(resp);
                if (resp == null
                        || resp.contains("[TIMEOUT-READ]")
                        || resp.contains("[TIMEOUT]")
                        || containsLoginFailureText(resp)
                        || (containsAuthPromptText(resp) && !hasInteractivePromptToken(resp))) {
                    return false;
                }

                updateRuntimeDeviceNameFromResponse(resp, Device, cmdSet);
                if (!checkEnvCommand(cmdSet, Loopback, Device, Num_row)) {
                    return false;
                }
                write("");
            } else if (cmdSet.charAt(0) == 'O') {
                LOG.append(readUntil(">"));
                write("enable");
                LOG.append(readUntil("#"));
                write("configure terminal");
                LOG.append(readUntil("#"));
                write("");
            } else if (cmdSet.charAt(0) == 'L') {
                LOG.append(readUntil(">"));
                write("system-view");
                readUntil("]");
                write("user-interface vty 0 4");
                readUntil("]");
                write("screen-length 0");
                readUntil("]");
                write("q");
                readUntil("]");
                write("q");
                readUntil(">");
                write("");
            }
        }

        if (cmdSet.charAt(0) == 'N') {
            sleepQuietly(randomDelayMs(
                    NOKIA_POST_LOGIN_DELAY_BASE_MS,
                    NOKIA_POST_LOGIN_DELAY_JITTER_MS));
        }
        return true;
    }

//  Background Thread  Wrong Vendor - 2 -
    private static volatile boolean backgroundMonitorsActive = true;
    private static Thread wrongVendorMonitorThread = null;
    private static final Map<String, Long> completedLogCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> activeLogSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ConcurrentMap<String, MonitorScanState> wrongVendorScanCache
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<String, MonitorScanState> commandMonitorScanCache
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<String, MonitorScanState> connFailMonitorScanCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class MonitorScanState {

        final long lastModified;
        final long fileSize;
        final long nextCheckAtMs;

        MonitorScanState(long lastModified, long fileSize, long nextCheckAtMs) {
            this.lastModified = lastModified;
            this.fileSize = fileSize;
            this.nextCheckAtMs = nextCheckAtMs;
        }
    }

    private void clearActiveLogSession() {
        if (activeLogSessionPath != null) {
            activeLogSessions.remove(activeLogSessionPath);
            activeLogSessionPath = null;
        }
        activeLogSessionIdentity = null;
        activeLogDateTag = null;
    }

    private static boolean isActiveLogSession(File file) {
        return file != null && activeLogSessions.contains(file.getAbsolutePath());
    }

    private static boolean isCachedCompletedLog(File file) {
        if (file == null) {
            return false;
        }
        String key = file.getAbsolutePath();
        Long cachedLastModified = completedLogCache.get(key);
        if (cachedLastModified == null) {
            return false;
        }
        if (!file.exists() || file.lastModified() != cachedLastModified.longValue()) {
            completedLogCache.remove(key);
            return false;
        }
        if (hasWrongVendorSignal(file)) {
            completedLogCache.remove(key);
            return false;
        }
        return true;
    }

    private static void markCompletedLog(File file) {
        if (file != null && file.exists() && !hasWrongVendorSignal(file)) {
            completedLogCache.put(file.getAbsolutePath(), file.lastModified());
        }
    }

    private static void rememberMonitorScan(java.util.concurrent.ConcurrentMap<String, MonitorScanState> cache, File file, long nextCheckAtMs) {
        if (cache == null || file == null || !file.exists()) {
            return;
        }
        cache.put(file.getAbsolutePath(),
                new MonitorScanState(file.lastModified(), file.length(), nextCheckAtMs));
    }

    private static void clearMonitorScan(java.util.concurrent.ConcurrentMap<String, MonitorScanState> cache, File file) {
        if (cache == null || file == null) {
            return;
        }
        cache.remove(file.getAbsolutePath());
    }

    private static boolean shouldSkipMonitorScan(java.util.concurrent.ConcurrentMap<String, MonitorScanState> cache, File file, long now) {
        if (cache == null || file == null || !file.exists()) {
            return false;
        }
        MonitorScanState state = cache.get(file.getAbsolutePath());
        if (state == null) {
            return false;
        }
        if (state.lastModified != file.lastModified() || state.fileSize != file.length()) {
            cache.remove(file.getAbsolutePath(), state);
            return false;
        }
        return now < state.nextCheckAtMs;
    }

    private static void pruneMonitorScanCache(java.util.concurrent.ConcurrentMap<String, MonitorScanState> cache, File[] logs) {
        if (cache == null || cache.isEmpty()) {
            return;
        }
        Set<String> livePaths = new HashSet<>();
        if (logs != null) {
            for (File log : logs) {
                if (log != null) {
                    livePaths.add(log.getAbsolutePath());
                }
            }
        }
        for (String path : new ArrayList<>(cache.keySet())) {
            if (!livePaths.contains(path)) {
                cache.remove(path);
            }
        }
    }

    private static boolean shouldBackgroundMonitorsRun() {
        return backgroundMonitorsActive && BotGetLog_TrueCorp.shouldBackgroundWorkersRun();
    }

    public static synchronized void stopBackgroundMonitors() {
        backgroundMonitorsActive = false;
        interruptMonitorThread(wrongVendorMonitorThread);
        interruptMonitorThread(commandMonitorThread);
        interruptMonitorThread(connFailMonitorThread);
        wrongVendorMonitorThread = null;
        commandMonitorThread = null;
        connFailMonitorThread = null;
    }

    private static void interruptMonitorThread(Thread thread) {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }

    public static synchronized void startWrongVendorMonitor(PathFile fileInput) {
        backgroundMonitorsActive = true;
        if (wrongVendorMonitorThread != null && wrongVendorMonitorThread.isAlive()) {
            return;
        }

        wrongVendorMonitorThread = new Thread(() -> {
            System.out.println("[MONITOR]  Wrong Vendor Monitor started (every 60 sec)");
            DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd");

            while (shouldBackgroundMonitorsRun()) {
                try {
                    Thread.sleep(60 * 1000); //  - 2 -

                    File logDir = new File(fileInput.getLog());
                    if (!logDir.exists()) {
                        System.out.println("[MONITOR]  Log directory not found: " + logDir.getAbsolutePath());
                        continue;
                    }

                    File[] logs = logDir.listFiles((dir, name) -> name.endsWith(".txt"));
                    if (logs == null || logs.length == 0) {
                        continue;
                    }
                    pruneMonitorScanCache(wrongVendorScanCache, logs);

                    long now = System.currentTimeMillis();
                    String today = LocalDateTime.now().format(dateFmt);
                    File wrongVendorLog = new File(fileInput.getLogWork(), "Node_WrongVendor_" + today + ".txt");

                    int checked = 0;
                    int deleted = 0;
                    int rerunTriggered = 0;

                    for (File f : logs) {
                        if (isCachedCompletedLog(f)) {
                            continue;
                        }
                        if (isActiveLogSession(f)) {
                            continue;
                        }
                        if (shouldSkipMonitorScan(wrongVendorScanCache, f, now)) {
                            continue;
                        }
                        long idleTime = now - f.lastModified();
                        if (idleTime < WRONG_VENDOR_INACTIVE_THRESHOLD_MS) {
                            rememberMonitorScan(wrongVendorScanCache, f, f.lastModified() + WRONG_VENDOR_INACTIVE_THRESHOLD_MS);
                            continue; //  1 -
                        }
                        checked++;
                        boolean isWrongVendor = hasWrongVendorSignal(f);
                        if (!isWrongVendor && hasCompletedSessionTail(f)) {
                            markCompletedLog(f);
                            clearMonitorScan(wrongVendorScanCache, f);
                            continue;
                        }

                        if (isWrongVendor) {
                            deleted++;
                            BotGetLog_TrueCorp.recordVendorFailure();
                            String timestamp = LocalDateTime.now().format(timeFmt);
                            Matcher matcher = DAILY_LOG_FILE_PATTERN.matcher(f.getName());
                            boolean parsed = matcher.find();
                            int numRow = parsed ? Integer.parseInt(matcher.group(1)) : -1;
                            String loopback = parsed ? matcher.group(2) : "";
                            String device = parsed ? matcher.group(3) : "";
                            String cmdSet = parsed ? matcher.group(4) : "";
                            String rerunCmdSet = parsed ? adjustCmdSetVendorFromLog(f, cmdSet) : cmdSet;
                            boolean groupEligible = parsed && getRetryFlagFromExcelStatic(fileInput, numRow);
                            String action = groupEligible
                                    ? "Wrong vendor detected - auto deleted and node re-executed as " + rerunCmdSet
                                    : "Wrong vendor detected - auto deleted";

                            //  - log wrong vendor
                            try ( FileWriter fw = new FileWriter(wrongVendorLog, true)) {
                                fw.write(String.format("[AUTO]%s,%s,[%s]\n",
                                        timestamp, f.getName(), action));
                            }

                            // - 
                            if (f.delete()) {
                                System.out.println("[MONITOR] - Deleted wrong vendor file: " + f.getName());
                            } else {
                                System.out.println("[MONITOR]  Failed to delete: " + f.getAbsolutePath());
                            }
                            clearMonitorScan(wrongVendorScanCache, f);

                            if (!parsed) {
                                System.out.printf("[MONITOR]  Skip wrong-vendor re-run because filename cannot be parsed: %s%n", f.getName());
                            } else if (!groupEligible) {
                                System.out.printf("[MONITOR]  Skip wrong-vendor re-run because Group != Y: %s%n", f.getName());
                            } else {
                                rerunTriggered++;
                                BotGetLog_TrueCorp.RerunNode(numRow, loopback, device, rerunCmdSet);
                                System.out.printf("[MONITOR]  Wrong-vendor recheck triggered for %s (%s, %s -> %s)%n",
                                        loopback, device, cmdSet, rerunCmdSet);
                            }
                        } else {
                            rememberMonitorScan(wrongVendorScanCache, f, Long.MAX_VALUE);
                        }
                    }

                    //   summary log -
                    String summary = String.format("[MONITOR]  %s - Checked: %d, WrongVendorDeleted: %d, Rechecked: %d",
                            LocalDateTime.now().format(timeFmt), checked, deleted, rerunTriggered);
                    System.out.println(summary);

                    try ( FileWriter fw = new FileWriter(fileInput.getLogWork() + "\\MonitorLog.txt", true)) {
                        fw.write(summary + "\n");
                    }

                } catch (InterruptedException e) {
                    System.out.println("[MONITOR]  Stopped Wrong Vendor Monitor.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (!shouldBackgroundMonitorsRun()) {
                        break;
                    }
                    System.out.println("[MONITOR]  Unexpected error: " + e.getMessage());
                }
            }
        });

        wrongVendorMonitorThread.setDaemon(true); // - background
        wrongVendorMonitorThread.start();
    }
//  Background Thread  Command Completion - 2 -
//  Background Thread  Command Completion - 2 -
//  Background Thread  Command Completion - 2 -

    private static boolean hasCompletedSessionTail(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (hasWrongVendorSignal(file)) {
            return false;
        }
        try ( java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long fileLength = raf.length();
            long seekPos = Math.max(0, fileLength - 8192);
            raf.seek(seekPos);
            byte[] buf = new byte[(int) (fileLength - seekPos)];
            raf.readFully(buf);
            String tail = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            return hasStrongSessionEnd(tail);
        } catch (Exception e) {
            System.out.println("[MONITOR]  Failed to inspect tail: " + e.getMessage());
            return false;
        }
    }

    private static Thread commandMonitorThread = null;

    public static synchronized void startCommandCompletionMonitor(PathFile fileInput) {
        backgroundMonitorsActive = true;
        if (commandMonitorThread != null && commandMonitorThread.isAlive()) {
            return;
        }

        commandMonitorThread = new Thread(() -> {
            System.out.println("[MONITOR]  Command Completion Monitor started (every 60 sec)");
            DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd");

            while (shouldBackgroundMonitorsRun()) {
                try {
                    Thread.sleep(60 * 1000); //  - 2 -

                    File logDir = new File(fileInput.getLog());
                    if (!logDir.exists()) {
                        System.out.println("[MONITOR]  Log directory not found: " + logDir.getAbsolutePath());
                        continue;
                    }

                    File[] logs = logDir.listFiles((dir, name) -> name.endsWith(".txt"));
                    if (logs == null || logs.length == 0) {
                        continue;
                    }
                    pruneMonitorScanCache(commandMonitorScanCache, logs);

                    String today = LocalDateTime.now().format(dateFmt);
                    File recheckFile = new File(fileInput.getLogWork(), "Node_Recheck_" + today + ".txt");
                    long now = System.currentTimeMillis();

                    int checked = 0, rechecked = 0;

                    for (File f : logs) {
                        if (isCachedCompletedLog(f)) {
                            continue;
                        }
                        if (isActiveLogSession(f)) {
                            continue;
                        }
                        if (shouldSkipMonitorScan(commandMonitorScanCache, f, now)) {
                            continue;
                        }
                        checked++;

                        //  - ( 5 -)
                        long idleTime = now - f.lastModified();
                        if (idleTime < 2 * 60 * 1000) {
                            rememberMonitorScan(commandMonitorScanCache, f, f.lastModified() + (2 * 60 * 1000L));
                            continue;
                        }

                        //  
                        Matcher matcher = DAILY_LOG_FILE_PATTERN.matcher(f.getName());
                        if (!matcher.find()) {
                            rememberMonitorScan(commandMonitorScanCache, f, Long.MAX_VALUE);
                            continue;
                        }

                        int numRow = Integer.parseInt(matcher.group(1));
                        String loopback = matcher.group(2);
                        String device = matcher.group(3);
                        String cmdSet = matcher.group(4);
                        boolean groupEligible = getRetryFlagFromExcelStatic(fileInput, numRow);

                        boolean validHead = isValidLogHead(f, device, cmdSet);
                        if (!validHead) {
                            if (!groupEligible) {
                                System.out.printf("[MONITOR]  Skip invalid-head auto action because Group != Y: %s%n", f.getName());
                                rememberMonitorScan(commandMonitorScanCache, f, Long.MAX_VALUE);
                                continue;
                            }
                            System.out.printf("[MONITOR]  Invalid log head for %s%n", f.getName());
                            boolean shouldRerun = !hasConnectionFailureLog(f);
                            if (f.delete()) {
                                System.out.println("[MONITOR] - Deleted invalid-head file: " + f.getName());
                            }
                            clearMonitorScan(commandMonitorScanCache, f);
                            if (shouldRerun) {
                                BotGetLog_TrueCorp.RerunNode(numRow, loopback, device, cmdSet);
                            }
                            Thread.sleep(1000);
                            continue;
                        }

                        //   session   rerun  vendor/cmdSet 
                        if (hasCompletedSessionTail(f)) {
                            markCompletedLog(f);
                            clearMonitorScan(commandMonitorScanCache, f);
                            System.out.printf("[MONITOR]  Completed log cached, skipping future checks: %s%n", f.getName());
                            continue;
                        }

                        String lastCmd = getLastCommandFromExcelStatic(fileInput, cmdSet);
                        if (lastCmd == null || lastCmd.isEmpty()) {
                            BotGetLog_TrueCorp.recordCmdSetFailure();
                            rememberMonitorScan(commandMonitorScanCache, f, Long.MAX_VALUE);
                            continue;
                        }

                        //  -
                        boolean found = false;
                        boolean connectionFailureLog = false;
                        String lastLine = "";
                        String cleanCmd = lastCmd.replaceAll("[^a-zA-Z0-9#:/\\- ]", "").toLowerCase();
                        try ( BufferedReader br = new BufferedReader(new FileReader(f))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                lastLine = line.trim();
                                String cleanLine = line.replaceAll("[^a-zA-Z0-9#:/\\- ]", "").toLowerCase();
                                if (cleanLine.contains(cleanCmd)) {
                                    found = true;
                                }
                                if (containsConnectionFailureSignal(line)) {
                                    connectionFailureLog = true;
                                }
                            }
                        }

                        //   command incomplete -> classify rerun type
                        String incompleteReason = found
                                ? "missing session end"
                                : "missing '" + lastCmd + "'";
                        if (!found || groupEligible) {
                            if (!groupEligible) {
                                System.out.printf("[MONITOR]  Skip incomplete auto action because Group != Y: %s%n", f.getName());
                                rememberMonitorScan(commandMonitorScanCache, f, Long.MAX_VALUE);
                                continue;
                            }
                            System.out.printf("[MONITOR]  Incomplete log for %s (%s) - %s - last line='%s'%n",
                                    device, loopback, incompleteReason, lastLine);
                            if (!connectionFailureLog) {
                                BotGetLog_TrueCorp.recordIncompleteFailure();
                            }

                            if (f.delete()) {
                                System.out.println("[MONITOR] - Deleted incomplete file: " + f.getName());
                            } else {
                                System.out.println("[MONITOR]  Failed to delete: " + f.getAbsolutePath());
                            }
                            clearMonitorScan(commandMonitorScanCache, f);

                            String ts = LocalDateTime.now().format(timeFmt);
                            try ( FileWriter fw = new FileWriter(fileInput.getLogWork() + "\\MonitorLog.txt", true)) {
                                fw.write(String.format("[MONITOR] %s - Deleted incomplete log %s (%s)\n",
                                        ts, f.getName(), incompleteReason));
                            }

                            if (connectionFailureLog) {
                                System.out.println("[MONITOR]  Skip re-run because monitor determined this as connection failure.");
                            } else if (groupEligible) {
                                rechecked++;
                                String timestamp = LocalDateTime.now().format(timeFmt);

                                try ( FileWriter fw = new FileWriter(recheckFile, true)) {
                                    fw.write(String.format("[AUTO]%s,%s,[Incomplete log: %s - Node re-executed]\n",
                                            timestamp, f.getName(), incompleteReason));
                                }

                                BotGetLog_TrueCorp.RerunNode(numRow, loopback, device, cmdSet);
                                System.out.printf("[MONITOR]  Recheck triggered for %s (%s, %s)%n", loopback, device, cmdSet);

                                try ( FileWriter fw = new FileWriter(fileInput.getLogWork() + "\\MonitorLog.txt", true)) {
                                    fw.write(String.format("[MONITOR]  %s - Recheck node %s (%s, %s)\n",
                                            LocalDateTime.now().format(timeFmt), loopback, device, cmdSet));
                                }
                            } else {
                                //  Re-run node now
                                System.out.printf("[MONITOR]  Immediate re-run node %s (%s, %s)%n", loopback, device, cmdSet);
                                try {
                                    BotGetLog_TrueCorp.RerunNode(numRow, loopback, device, cmdSet);
                                } catch (Exception e) {
                                    System.out.println("[MONITOR]  Immediate re-run error: " + e.getMessage());
                                }

                                try ( FileWriter fw = new FileWriter(fileInput.getLogWork() + "\\MonitorLog.txt", true)) {
                                    fw.write(String.format("[MONITOR] %s - Triggered immediate re-run for %s (%s, %s)\n",
                                            LocalDateTime.now().format(timeFmt), loopback, device, cmdSet));
                                }
                            }

                            Thread.sleep(1000); //  thread
                            continue;
                        }

                        rememberMonitorScan(commandMonitorScanCache, f, Long.MAX_VALUE);
                    }

                    //  
                    String summary = String.format("[MONITOR] %s - Checked:%d | Rechecked:%d",
                            LocalDateTime.now().format(timeFmt), checked, rechecked);
                    System.out.println(summary);
                    try ( FileWriter fw = new FileWriter(fileInput.getLogWork() + "\\MonitorLog.txt", true)) {
                        fw.write(summary + "\n");
                    }

                } catch (InterruptedException e) {
                    System.out.println("[MONITOR]  Command Completion Monitor stopped.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (!shouldBackgroundMonitorsRun()) {
                        break;
                    }
                    System.out.println("[MONITOR]  Unexpected error: " + e.getMessage());
                }
            }
        });

        commandMonitorThread.setDaemon(true);
        commandMonitorThread.start();
    }

//  - sheet cmdSet
    private static String getLastCommandFromExcelStatic(PathFile fileInput, String cmdSet) {
        String cached = BotGetLog_TrueCorp.getCachedLastCommand(cmdSet);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        try {
            File excelFile = new File(fileInput.getUserInterface_Input());
            try ( Workbook workbook = WorkbookFactory.create(excelFile)) {
                Sheet sheet = workbook.getSheet("cmdSet");
                for (int j = 0; j < sheet.getRow(0).getLastCellNum(); j++) {
                    String name = BotGetLog_TrueCorp.getCellValue(sheet.getRow(0).getCell(j));
                    if (cmdSet.equalsIgnoreCase(name)) {
                        String lastCmd = "";
                        Iterator<Row> rows = sheet.rowIterator();
                        while (rows.hasNext()) {
                            Row r = rows.next();
                            if (r.getCell(j) != null) {
                                String val = BotGetLog_TrueCorp.getCellValue(r.getCell(j));
                                if (val != null && !val.trim().isEmpty()) {
                                    lastCmd = val.trim();
                                }
                            }
                        }
                        return lastCmd;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[MONITOR]  Error reading Excel cmdSet: " + e.getMessage());
        }
        return null;
    }

//   Group = Y  sheet deviceList
    private static boolean getRetryFlagFromExcelStatic(PathFile fileInput, int rowNum) {
        if (BotGetLog_TrueCorp.isRetryEnabledForRow(rowNum)) {
            return true;
        }
        try {
            File excelFile = new File(fileInput.getUserInterface_Input());
            try ( Workbook workbook = WorkbookFactory.create(excelFile)) {
                Sheet sheet = BotGetLog_TrueCorp.getSheetAny(workbook, "deviceList_TRUE", "deviceList");
                if (sheet == null) {
                    return false;
                }
                Row row = sheet.getRow(rowNum);
                if (row == null) {
                    return false;
                }

                if (row.getCell(0) != null) { //   Group
                    String retry = BotGetLog_TrueCorp.getCellValue(row.getCell(0));
                    return retry != null && retry.trim().equalsIgnoreCase("Y");
                }
            }
        } catch (Exception e) {
            System.out.println("[MONITOR]  Error reading Excel deviceList: " + e.getMessage());
        }
        return false;
    }

    private static boolean hasConnectionFailureLog(File logFile) {
        try ( BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (containsConnectionFailureSignal(line)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean containsConnectionFailureSignal(String text) {
        if (text == null) {
            return false;
        }
        String line = text.toLowerCase();
        return line.contains("connection failed")
                || line.contains("connection timed out")
                || line.contains("unable to connect")
                || line.contains("cannot connect")
                || line.contains("connection refused")
                || line.contains("sam-bb connection to node timed out")
                || line.contains("no response after password")
                || line.contains("telnet read timed out")
                || line.contains("connectexception")
                || line.contains("[error] telnet");
    }
    //  Background Thread  Connection Fail - 2 -
//  Background Thread  Connection Fail  ( < 3KB  fail)
    private static Thread connFailMonitorThread = null;

    public static synchronized void startConnectionFailMonitor(PathFile fileInput) {
        backgroundMonitorsActive = true;
        //  
        if (connFailMonitorThread != null && connFailMonitorThread.isAlive()) {
            return;
        }

        connFailMonitorThread = new Thread(() -> {

            DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd");

            while (shouldBackgroundMonitorsRun()) {
                try {
                    Thread.sleep(60 * 1000); //  - 2 -

                    File logDir = new File(fileInput.getLog());
                    if (!logDir.exists()) {
                        continue;
                    }

                    File[] logs = logDir.listFiles((dir, name) -> name.endsWith(".txt"));
                    if (logs == null || logs.length == 0) {
                        continue;
                    }
                    pruneMonitorScanCache(connFailMonitorScanCache, logs);

                    String today = LocalDateTime.now().format(dateFmt);
                    File connFailLog = new File(fileInput.getLogWork(), "Node_ConnectionFailed_" + today + ".txt");
                    long now = System.currentTimeMillis();

                    int checked = 0;
                    int deleted = 0;

                    for (File f : logs) {
                        if (isCachedCompletedLog(f)) {
                            continue;
                        }
                        if (isActiveLogSession(f)) {
                            continue;
                        }
                        if (shouldSkipMonitorScan(connFailMonitorScanCache, f, now)) {
                            continue;
                        }
                        checked++;

                        //  - ( 3 -)
                        long idleTime = now - f.lastModified();
                        if (idleTime < 3 * 60 * 1000) {
                            rememberMonitorScan(connFailMonitorScanCache, f, f.lastModified() + (3 * 60 * 1000L));
                            continue;
                        }
                        if (hasCompletedSessionTail(f)) {
                            markCompletedLog(f);
                            clearMonitorScan(connFailMonitorScanCache, f);
                            continue;
                        }

                        //   cmdSet
                        long fileSize = f.length();
                        double perCommandKB = 0.15;
                        int commandCount = 1; // default = 1

//   cmdSet   [5]10.167.1.1_NODEX_N-OPTICAL_2025-11-03.txt
                        Matcher matcher = DAILY_LOG_FILE_PATTERN.matcher(f.getName());
                        String cmdSet = "";
                        if (matcher.find()) {
                            cmdSet = matcher.group(4);
                        }

                        int cachedCommandCount = BotGetLog_TrueCorp.getCachedCommandCount(cmdSet);
                        if (cachedCommandCount > 0) {
                            commandCount = cachedCommandCount;
                        } else {
                            try {
                                File excelFile = new File(fileInput.getUserInterface_Input());
                                try ( Workbook workbook = WorkbookFactory.create(excelFile)) {
                                    Sheet sheet = workbook.getSheet("cmdSet");
                                    for (int j = 0; j < sheet.getRow(0).getLastCellNum(); j++) {
                                        String header = BotGetLog_TrueCorp.getCellValue(sheet.getRow(0).getCell(j));
                                        if (cmdSet.equalsIgnoreCase(header)) {
                                            int count = 0;
                                            Iterator<Row> rows = sheet.rowIterator();
                                            while (rows.hasNext()) {
                                                Row r = rows.next();
                                                if (r.getCell(j) != null) {
                                                    String val = BotGetLog_TrueCorp.getCellValue(r.getCell(j));
                                                    if (val != null && !val.trim().isEmpty()) {
                                                        count++;
                                                    }
                                                }
                                            }
                                            commandCount = Math.max(1, count);
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("[MONITOR]  Error reading Excel for " + cmdSet + ": " + e.getMessage());
                            }
                        }

//   (0.15 KB )
                        double sizeLimitKB = perCommandKB * commandCount;

                        if (fileSize == 0L) {
                            // Keep 0KB files for command completion monitor to re-run.
                            rememberMonitorScan(connFailMonitorScanCache, f, Long.MAX_VALUE);
                            continue;
                        }

                        if (fileSize < sizeLimitKB * 1024) {
                            deleted++;
                            String timestamp = LocalDateTime.now().format(timeFmt);
                            try ( FileWriter fw = new FileWriter(connFailLog, true)) {
                                fw.write(String.format("[AUTO]%s,%s,[File too small (%.2f KB < %.2f KB; %d cmds) - auto deleted as connection fail]\n",
                                        timestamp, f.getName(), fileSize / 1024.0, sizeLimitKB, commandCount));
                            }

                            if (f.delete()) {
                                System.out.printf("[MONITOR]  Deleted small log: %s (%.2f KB < %.2f KB; %d cmds)%n",
                                        f.getName(), fileSize / 1024.0, sizeLimitKB, commandCount);
                            } else {
                                System.out.println("[MONITOR]  Failed to delete: " + f.getAbsolutePath());
                            }
                            clearMonitorScan(connFailMonitorScanCache, f);
                        } else {
                            rememberMonitorScan(connFailMonitorScanCache, f, Long.MAX_VALUE);
                        }

                    }

                    //  
                    String summary = String.format("[MONITOR]  %s - Checked:%d | SmallFilesDeleted:%d",
                            LocalDateTime.now().format(timeFmt), checked, deleted);
                    System.out.println(summary);

                    try ( FileWriter fw = new FileWriter(fileInput.getLogWork() + "\\MonitorLog.txt", true)) {
                        fw.write(summary + "\n");
                    }

                } catch (InterruptedException e) {
                    System.out.println("[MONITOR]  ConnectionFail Monitor stopped.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (!shouldBackgroundMonitorsRun()) {
                        break;
                    }
                    System.out.println("[MONITOR]  ConnectionFail Monitor error: " + e.getMessage());
                }
            }
        });

        connFailMonitorThread.setDaemon(true);
        connFailMonitorThread.start();
    }
//  Utility function - Random ports from each slot

    private static List<String> pickRandomFromEachSlot(Map<String, List<String>> slotMap, int totalNeeded) {
        List<String> result = new ArrayList<>();
        Random rand = new Random();

        while (result.size() < totalNeeded && !slotMap.isEmpty()) {
            for (Iterator<Map.Entry<String, List<String>>> it = slotMap.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, List<String>> entry = it.next();
                List<String> ports = entry.getValue();
                if (ports.isEmpty()) {
                    it.remove();
                    continue;
                }
                String port = ports.remove(rand.nextInt(ports.size()));
                result.add(port);
                if (result.size() >= totalNeeded) {
                    break;
                }
            }
        }
        return result;
    }
//  - slot 

    //  - slot 
//   2   card/slot  round-robin
    private static List<String> pickSequentialFromEachSlot(Map<String, List<String>> slotMap, int totalNeeded) {
        List<String> result = new ArrayList<>();

        //  step 1:  card-slot
        Map<String, List<List<String>>> groupsByCardSlot = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : slotMap.entrySet()) {
            List<String> ports = entry.getValue();
            if (ports.size() < 2) {
                continue; //  slot -
            }
            //  port  slot
            ports.sort(Comparator.comparingInt(p
                    -> Integer.parseInt(p.replaceAll(".*?/\\d+/(\\d+).*", "$1"))
            ));

            //  block  2
            List<List<String>> blocks = new ArrayList<>();
            List<String> current = new ArrayList<>();
            for (int i = 0; i < ports.size(); i++) {
                if (current.isEmpty()) {
                    current.add(ports.get(i));
                } else {
                    int prev = Integer.parseInt(current.get(current.size() - 1)
                            .replaceAll(".*?/\\d+/(\\d+).*", "$1"));
                    int next = Integer.parseInt(ports.get(i)
                            .replaceAll(".*?/\\d+/(\\d+).*", "$1"));
                    if (next == prev + 1) {
                        current.add(ports.get(i));
                    } else {
                        if (current.size() >= 2) {
                            blocks.add(new ArrayList<>(current));
                        }
                        current.clear();
                        current.add(ports.get(i));
                    }
                }
            }
            if (current.size() >= 2) {
                blocks.add(new ArrayList<>(current));
            }

            if (!blocks.isEmpty()) {
                groupsByCardSlot.put(entry.getKey(), blocks);
            }
        }

        //  step 2:  block  round-robin
        boolean added = true;
        while (result.size() < totalNeeded && added) {
            added = false;
            for (Iterator<Map.Entry<String, List<List<String>>>> it = groupsByCardSlot.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, List<List<String>>> entry = it.next();
                List<List<String>> blocks = entry.getValue();
                if (blocks.isEmpty()) {
                    it.remove();
                    continue;
                }
                List<String> block = blocks.remove(0);
                for (String p : block) {
                    if (result.size() >= totalNeeded) {
                        break;
                    }
                    result.add(p);
                    added = true;
                }
                if (result.size() >= totalNeeded) {
                    break;
                }
            }
        }

        //  step 3:  quota   block -
        if (result.size() < totalNeeded) {
            for (List<List<String>> blocks : groupsByCardSlot.values()) {
                for (List<String> b : blocks) {
                    for (String p : b) {
                        if (result.size() >= totalNeeded) {
                            break;
                        }
                        if (!result.contains(p)) {
                            result.add(p);
                        }
                    }
                    if (result.size() >= totalNeeded) {
                        break;
                    }
                }
            }
        }

        return result;
    }
//   block  1  card-slot

    private static Map<String, List<List<String>>> makeBlocks(Map<String, List<String>> portMap) {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : portMap.entrySet()) {
            List<String> ports = entry.getValue();
            ports.sort(Comparator.comparingInt(p
                    -> Integer.parseInt(p.replaceAll(".*?/\\d+/(\\d+).*", "$1"))
            ));

            List<List<String>> blocks = new ArrayList<>();
            List<String> current = new ArrayList<>();

            for (String p : ports) {
                if (current.isEmpty()) {
                    current.add(p);
                } else {
                    int prev = Integer.parseInt(current.get(current.size() - 1)
                            .replaceAll(".*?/\\d+/(\\d+).*", "$1"));
                    int next = Integer.parseInt(p.replaceAll(".*?/\\d+/(\\d+).*", "$1"));
                    if (next == prev + 1) {
                        current.add(p);
                    } else {
                        blocks.add(new ArrayList<>(current));
                        current.clear();
                        current.add(p);
                    }
                }
            }
            if (!current.isEmpty()) {
                blocks.add(current);
            }
            if (!blocks.isEmpty()) {
                result.put(entry.getKey(), blocks);
            }
        }

        return result;
    }

    private static boolean sendUndoBatch(Telnet_Multi self, String Loopback, String Device, String cmdSet, int Num_row,
            List<String> ports, List<String> afterUndoPorts) {
        StringBuilder sb = new StringBuilder();
        sb.append("system-view\n");
        for (String p : ports) {
            String intf = p.replace("(10G)", "")
                    .replaceAll("(?i)(?:10)?GE", "GigabitEthernet ")
                    .trim();
            sb.append("interface ").append(intf).append("\n");
            sb.append("undo description\nquit\n");
            afterUndoPorts.add(p);
        }
        sb.append("commit\nquit\n");
        System.out.println("[BATCH-UNDO] Sending batch of " + ports.size() + " ports...");
        return self.executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, sb.toString());
    }

    private static boolean sendReserveBatch(Telnet_Multi self, String Loopback, String Device, String cmdSet, int Num_row,
            List<String> cmds) {
        StringBuilder sb = new StringBuilder();
        sb.append("system-view\n");
        for (String c : cmds) {
            sb.append(c);
        }
        sb.append("commit\nquit\n");
        System.out.println("[BATCH-RESERVE] Sending " + cmds.size() + " ports...");
        return self.executeCommandWithReconnect(Loopback, Device, cmdSet, Num_row, sb.toString());
    }

}

