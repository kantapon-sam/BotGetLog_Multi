package com.java.botgetlog.truecorp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Junos role lines and CLLS session-close output are part of a valid capture. */
final class JuniperLogValidation {
    private static final Pattern ROLE = Pattern.compile("\\{(?:master|backup)(?::\\d+)?\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT = Pattern.compile("^(?:[^\\s@>#]+@)*([A-Za-z0-9_.:-]+)>\\s*(.*)$");
    private static final Pattern CLOSED = Pattern.compile("^Connection closed by foreign host\\.?$", Pattern.CASE_INSENSITIVE);

    private JuniperLogValidation() { }

    static boolean isJuniper(String cmdSet) {
        return cmdSet != null && cmdSet.trim().toUpperCase(Locale.ROOT).startsWith("J-");
    }

    static boolean isClosingCommand(String command) {
        return command != null && command.trim().matches("(?i)quit|exit|logout");
    }

    static boolean hasValidHead(File file, String device) {
        if (file == null || !file.isFile()) return false;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < 20; i++) {
                String raw = reader.readLine();
                if (raw == null) break;
                String line = clean(raw);
                if (line.isEmpty() || ROLE.matcher(line).matches()) continue;
                Matcher prompt = PROMPT.matcher(line);
                return prompt.matches() && sameDevice(device, prompt.group(1))
                        && "set cli screen-length 0".equalsIgnoreCase(prompt.group(2).trim());
            }
        } catch (IOException ignored) { }
        return false;
    }

    static boolean hasCompletedClose(File file, String device, String command) {
        if (file == null || !file.isFile() || !isClosingCommand(command)) return false;
        try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[(int) Math.min(reader.length(), 65536L)];
            reader.seek(reader.length() - bytes.length);
            reader.readFully(bytes);
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                Matcher prompt = PROMPT.matcher(clean(lines[i]));
                if (!prompt.matches() || !sameDevice(device, prompt.group(1))) continue;
                String output = prompt.group(2).trim();
                String cmd = command.trim();
                if (output.length() < cmd.length() || !output.regionMatches(true, 0, cmd, 0, cmd.length())) continue;
                String remainder = output.substring(cmd.length()).trim();
                // Some CLLS sessions join the command echo and close message without a space.
                if (CLOSED.matcher(remainder).matches()) return true;
                if (!remainder.isEmpty()) continue;
                for (int j = i + 1; j < Math.min(lines.length, i + 8); j++) {
                    String next = clean(lines[j]);
                    if (next.isEmpty()) continue;
                    if (CLOSED.matcher(next).matches()) return true;
                    break;
                }
            }
        } catch (IOException ignored) { }
        return false;
    }

    private static boolean sameDevice(String expected, String actual) {
        String normalized = expected == null ? "" : expected.trim().replace('_', '-');
        return !normalized.isEmpty() && normalized.equalsIgnoreCase(actual.replace('_', '-'));
    }

    private static String clean(String line) {
        return line.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "").replace("\uFEFF", "").trim();
    }
}
