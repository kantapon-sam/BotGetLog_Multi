package com.java.shared;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A finished ZTE capture with usable identity/LLDP, but known device service timeouts.
 * This is deliberately separate from the collector's strict completeness validator.
 */
public final class ZtePartialLog {
    private static final Pattern NAME = Pattern.compile(
            "^\\[\\d+](\\d{1,3}(?:\\.\\d{1,3}){3})_(.+)_ZTE-LLDP-Link_OPTIC_\\d{4}-\\d{2}-\\d{2}\\.txt$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT = Pattern.compile("^([^\\s#>]+)#\\s*(.*?)\\s*$");
    private static final Pattern ERROR = Pattern.compile(
            "(?i)(?:%error|error:|invalid input|bad command|unrecognized command|unknown command|syntax error|command not found|invalid command|authentication failed|permission denied)");
    private static final Pattern TIMEOUT = Pattern.compile(
            "(?i)^%Error 141365: The current operation timeout\\. Please try again later\\.\\(The JID=0x[0-9a-f]*$");
    private static final List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "terminal length 0", "show processor", "show lldp neighbor brief", "show version",
            "show opt brief", "show interface", "show lacp internal", "quit"));
    private static final Set<String> ALLOWED = new LinkedHashSet<String>(Arrays.asList("show opt brief", "show interface"));
    private static final Result NONE = new Result(Collections.<String>emptySet(), Collections.<String>emptyList());

    private ZtePartialLog() { }

    public static Result inspect(File file) {
        if (file == null || !file.isFile() || !NAME.matcher(file.getName()).matches()) return NONE;
        try {
            long size = file.length(), modified = file.lastModified();
            // Fail closed on unexpectedly large/unreadable/changing captures.
            if (size <= 0 || size > 16L * 1024 * 1024) return NONE;
            Result result = inspect(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8), file.getName());
            return file.isFile() && size == file.length() && modified == file.lastModified() ? result : NONE;
        } catch (IOException | RuntimeException ex) { return NONE; }
    }

    public static Result inspect(List<String> lines, String fileName) {
        Matcher name = NAME.matcher(new File(fileName).getName());
        if (!name.matches() || lines == null) return NONE;
        String expectedNode = name.group(2);
        List<List<String>> bodies = new ArrayList<List<String>>();
        List<String> normalized = new ArrayList<String>();
        List<String> section = null;
        Set<String> missing = new LinkedHashSet<String>();
        int index = -1;
        boolean closed = false, pendingTimeoutTail = false;
        for (String raw : lines) {
            String line = raw.replace("\u001b[0m", "").trim();
            normalized.add(raw);
            Matcher prompt = PROMPT.matcher(line);
            if (prompt.matches()) {
                if (pendingTimeoutTail || !expectedNode.equalsIgnoreCase(prompt.group(1))
                        || ++index >= COMMANDS.size() || !COMMANDS.get(index).equalsIgnoreCase(prompt.group(2))) return NONE;
                section = new ArrayList<String>(); bodies.add(section);
                continue;
            }
            if (section == null) { if (!line.isEmpty()) return NONE; else continue; }
            if (pendingTimeoutTail) {
                if (!line.matches("(?i)^[0-9a-f]+ service does not respond\\)$")) return NONE;
                pendingTimeoutTail = false;
                section.add(raw); continue;
            }
            if (ERROR.matcher(line).find()) {
                if (!ALLOWED.contains(COMMANDS.get(index)) || !TIMEOUT.matcher(line).matches()) return NONE;
                missing.add(COMMANDS.get(index)); pendingTimeoutTail = true;
            }
            if (index == COMMANDS.size() - 1) {
                if (line.equals("Connection closed by foreign host.")) closed = true;
                else if (!line.isEmpty() && !line.startsWith("Script done, file is /")) return NONE;
            }
            section.add(raw);
        }
        if (pendingTimeoutTail || index != COMMANDS.size() - 1 || !closed || missing.isEmpty()) return NONE;
        String cpu = String.join("\n", bodies.get(1));
        String lldp = String.join("\n", bodies.get(2));
        String version = String.join("\n", bodies.get(3));
        if (!Pattern.compile("(?i)(CPU|processor)").matcher(cpu).find()
                || !Pattern.compile("\\d").matcher(cpu).find()
                || !Pattern.compile("(?im)^\\s*Total neighbors:\\s*\\d+\\s*$").matcher(lldp).find()
                || !lldp.contains("Local Interface") || !lldp.contains("System Name")
                || !version.contains("ZTE ZXCTN Software, Version:")
                || bodies.get(6).isEmpty()) return NONE;
        // Strip failed sections for export; echoed commands remain as parser boundaries.
        List<String> safe = new ArrayList<String>();
        String command = "";
        for (String line : normalized) {
            Matcher prompt = PROMPT.matcher(line.trim());
            if (prompt.matches()) { command = prompt.group(2).toLowerCase(java.util.Locale.ROOT); safe.add(line); }
            else if (!missing.contains(command)) safe.add(line);
        }
        return new Result(missing, safe);
    }

    public static final class Result {
        private final Set<String> missing;
        private final List<String> safeLines;
        private Result(Set<String> missing, List<String> safeLines) {
            this.missing = Collections.unmodifiableSet(new LinkedHashSet<String>(missing));
            this.safeLines = Collections.unmodifiableList(new ArrayList<String>(safeLines));
        }
        public boolean isPartial() { return !missing.isEmpty(); }
        public Set<String> missingCommands() { return missing; }
        public List<String> safeLines() { return safeLines; }
        public String detail() { return "Complete (Partial); unavailable: " + String.join(", ", missing)
                + "; device service timeout 141365; eligible for daily retry"; }
    }
}
