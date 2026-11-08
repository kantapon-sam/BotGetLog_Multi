package com.java.botgetlog.truecorp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Bounded alternative node logins, within the already admitted connection. */
final class CllsFallback {
    static final int MAX_BACKUPS = 5;
    private static final Pattern TERMINAL_CONTROL = Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern CURRENT_GATEWAY_PROMPT = Pattern.compile(
            "(?im)(?:^|\\n)[\\t ]*(?:please[\\t ]+)?enter[\\t ]+ip[\\t ]+address\\b[^\\n]*(?:\\n[\\t ]*)*\\z");

    static final class Credential {
        final int slot;
        final String username, password;
        Credential(int slot, String username, String password) {
            this.slot = slot;
            this.username = username == null ? "" : username.trim();
            this.password = password == null ? "" : password.trim();
        }
        boolean complete() {
            return slot >= 1 && slot <= MAX_BACKUPS && !username.isEmpty() && !password.isEmpty()
                    && !hasLineBreak(username) && !hasLineBreak(password);
        }
    }

    interface Login {
        String authenticate(Credential credential, String rejectedResponse);
    }

    static final class Result {
        final String response;
        final Credential successful;
        final int attempts;
        Result(String response, Credential successful, int attempts) {
            this.response = response;
            this.successful = successful;
            this.attempts = attempts;
        }
    }

    static Result attempt(String response, String primaryUser, String primaryPassword,
            List<Credential> backups, Login login, Predicate<String> verifiedNodePrompt) {
        Set<String> tried = new HashSet<>();
        tried.add(identity(primaryUser, primaryPassword));
        int attempts = 0;
        for (Credential credential : backups) {
            if (attempts >= MAX_BACKUPS || !retryableRejection(response)) break;
            if (!credential.complete() || !tried.add(identity(credential.username, credential.password))) continue;
            response = login.authenticate(credential, response);
            attempts++;
            if (!hardStop(response) && !retryableRejection(response) && verifiedNodePrompt.test(response))
                return new Result(response, credential, attempts);
        }
        return new Result(response, null, attempts);
    }

    static boolean hardStop(String response) {
        String text = lower(response);
        return text.isEmpty() || text.contains("timeout") || text.contains("timed out")
                || text.contains("connection closed") || text.contains("connection reset")
                || text.contains("connection refused") || text.contains("broken pipe")
                || text.contains("[eof]") || currentGatewayPrompt(response)
                || authenticationBlocked(response);
    }

    private static boolean currentGatewayPrompt(String response) {
        // SSH retains its initial SAM menu in the login transcript. Only a
        // current menu prompt means the node connection returned to SAM.
        String text = TERMINAL_CONTROL.matcher(response == null ? "" : response).replaceAll("")
                .replace("\r\n", "\n").replace('\r', '\n');
        return CURRENT_GATEWAY_PROMPT.matcher(text).find();
    }

    static boolean authenticationBlocked(String response) {
        String text = lower(response);
        return text.contains("user was locked") || text.contains("user is locked")
                || text.contains("account locked") || text.contains("account is locked")
                || text.contains("account has been locked") || text.contains("temporarily locked")
                || text.contains("account lockout") || text.contains("login blocked")
                || text.contains("the ip has been blocked") || text.contains("ip blocked")
                || text.contains("too many") || text.contains("maximum login")
                || text.contains("account disabled") || text.contains("account expired")
                || text.contains("password expired") || text.contains("cannot log on it");
    }

    static boolean retryableRejection(String response) {
        if (hardStop(response) && !reconnectableRejection(response)) return false;
        return explicitRejection(response);
    }

    /** A rejected node login may close Telnet and return to SAM. Never infer
     * rejection from a timeout, disconnect alone, or an account/IP lock. */
    static boolean reconnectableRejection(String response) {
        String text = lower(response);
        return explicitRejection(response) && !authenticationBlocked(response)
                && !text.contains("timeout") && !text.contains("timed out")
                && !text.contains("connection refused") && !text.contains("connection reset")
                && !text.contains("broken pipe")
                && (text.contains("connection closed") || text.contains("[eof]")
                    || currentGatewayPrompt(response));
    }

    static boolean explicitRejection(String response) {
        String text = lower(response);
        return text.contains("login incorrect") || text.contains("authentication failed")
                || text.contains("invalid password") || text.contains("access denied")
                || text.contains("username or password error") || text.contains("password error")
                || text.contains("username or password rejected");
    }

    static String successEvent(String ip, String node, Credential credential) {
        if (credential == null || !credential.complete() || "clls".equalsIgnoreCase(credential.username)) return "";
        return "{\"version\":1,\"ip\":" + json(ip) + ",\"node\":" + json(node)
                + ",\"slot\":" + credential.slot + ",\"username\":" + json(credential.username)
                + ",\"timestamp\":" + json(OffsetDateTime.now(ZoneId.of("Asia/Bangkok")).toString()) + "}";
    }

    /** Verified configured-primary CLLS role, or a backup using literal clls. */
    static String cllsSuccessEvent(String ip, String node, String username, int slot) {
        String user = username == null ? "" : username.trim();
        if (user.isEmpty() || hasLineBreak(user) || slot < 0 || slot > MAX_BACKUPS
                || (slot > 0 && !"clls".equalsIgnoreCase(user))
                || ip == null || ip.trim().isEmpty() || node == null || node.trim().isEmpty()
                || hasLineBreak(ip) || hasLineBreak(node)) return "";
        return "{\"version\":1,\"kind\":\"clls-success\",\"accountRole\":"
                + json(slot == 0 ? "clls-primary" : "clls-backup") + ",\"eventId\":" + json(UUID.randomUUID().toString())
                + ",\"ip\":" + json(ip.trim()) + ",\"node\":" + json(node.trim())
                + ",\"slot\":" + slot + ",\"username\":" + json(user)
                + ",\"timestamp\":" + json(OffsetDateTime.now(ZoneId.of("Asia/Bangkok")).toString()) + "}";
    }

    static synchronized void appendEvent(Path file, String event) throws IOException {
        if (event.isEmpty()) return;
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            channel.position(channel.size());
            ByteBuffer bytes = ByteBuffer.wrap((event + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(false);
        }
    }

    private static String identity(String username, String password) {
        String user = username == null ? "" : username.trim(), secret = password == null ? "" : password.trim();
        return user.length() + ":" + user + secret;
    }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static boolean hasLineBreak(String value) { return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0; }
    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : (value == null ? "" : value).toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
