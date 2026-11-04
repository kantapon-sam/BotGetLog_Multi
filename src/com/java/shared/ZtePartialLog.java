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

/** Validates narrowly supported ZTE command errors against a finished capture.
 * Service timeouts are partial; rejected paging setup alone can still be complete.
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
    private static final Pattern PAGING_REJECTION = Pattern.compile(
            "(?i)^%Error 140303: Invalid input detected at '\\^' marker\\.$");
    private static final Pattern UNFINISHED = Pattern.compile(
            "(?i)(\\[BOT-COLLECTION-INCOMPLETE]|\\[TIMEOUT]|--\\s*more\\s*--|more\\?|press\\s+(?:any\\s+key|space)|connection timed out)");
    private static final List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "terminal length 0", "show processor", "show lldp neighbor brief", "show version",
            "show opt brief", "show interface", "show lacp internal", "quit"));
    private static final Set<String> ALLOWED = new LinkedHashSet<String>(Arrays.asList("show opt brief", "show interface"));
    private static final Result NONE = new Result(Collections.<String>emptySet(), Collections.<String>emptyList(), false);

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
        boolean closed = false, pendingTimeoutTail = false, pagingRejected = false;
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
                if (index == 0 && !pagingRejected && PAGING_REJECTION.matcher(line).matches()) {
                    pagingRejected = true;
                } else {
                    if (!ALLOWED.contains(COMMANDS.get(index)) || !TIMEOUT.matcher(line).matches()) return NONE;
                    missing.add(COMMANDS.get(index)); pendingTimeoutTail = true;
                }
            }
            if (index == COMMANDS.size() - 1) {
                if (line.equals("Connection closed by foreign host.")) closed = true;
                else if (!line.isEmpty() && !line.startsWith("Script done, file is /")) return NONE;
            }
            section.add(raw);
        }
        if (pendingTimeoutTail || index != COMMANDS.size() - 1 || !closed
                || (missing.isEmpty() && !pagingRejected)) return NONE;
        String cpu = String.join("\n", bodies.get(1));
        String lldp = String.join("\n", bodies.get(2));
        String version = String.join("\n", bodies.get(3));
        if (!Pattern.compile("(?i)(CPU|processor)").matcher(cpu).find()
                || !Pattern.compile("\\d").matcher(cpu).find()
                || !Pattern.compile("(?im)^\\s*Total neighbors:\\s*\\d+\\s*$").matcher(lldp).find()
                || !lldp.contains("Local Interface") || !lldp.contains("System Name")
                || !version.contains("ZTE ZXCTN Software, Version:")
                || bodies.get(6).isEmpty()) return NONE;
        // Rejecting a display preference loses no data if the existing Space
        // continuation completed every response. Echoed commands alone are not
        // sufficient evidence for this exception.
        if (pagingRejected && !hasCompletePagingFallback(bodies, normalized, missing)) return NONE;
        // Strip failed sections for export; echoed commands remain as parser boundaries.
        List<String> safe = new ArrayList<String>();
        String command = "";
        for (String line : normalized) {
            Matcher prompt = PROMPT.matcher(line.trim());
            if (prompt.matches()) { command = prompt.group(2).toLowerCase(java.util.Locale.ROOT); safe.add(line); }
            else if (!missing.contains(command)) safe.add(line);
        }
        return new Result(missing, safe, pagingRejected);
    }

    private static boolean hasCompletePagingFallback(List<List<String>> bodies, List<String> lines,
            Set<String> missing) {
        List<String> setup = new ArrayList<String>();
        for (String line : bodies.get(0)) if (!line.trim().isEmpty()) setup.add(line.trim());
        if (setup.size() != 3 || !"terminal length 0".equalsIgnoreCase(setup.get(0))
                || !"^".equals(setup.get(1)) || !PAGING_REJECTION.matcher(setup.get(2)).matches()) return false;
        for (String line : lines) if (UNFINISHED.matcher(line).find()) return false;

        String lldp = String.join("\n", bodies.get(2));
        Matcher total = Pattern.compile("(?im)^\\s*Total neighbors:\\s*(\\d+)\\s*$").matcher(lldp);
        if (!total.find()) return false;
        int neighbors;
        try { neighbors = Integer.parseInt(total.group(1)); }
        catch (NumberFormatException ex) { return false; }
        Matcher rows = Pattern.compile("(?m)^\\s*\\S+\\s+(?:NB|NC|NTPMR)\\s+\\S+\\s+\\S+\\s+\\d+\\s+\\S+\\s*$").matcher(lldp);
        int count = 0;
        while (rows.find()) count++;
        if (count != neighbors) return false;

        String optical = String.join("\n", bodies.get(4));
        if (!missing.contains("show opt brief") && (!optical.contains("RxPower(dBm)")
                || !optical.contains("TxPower(dBm)") || !optical.contains("Status")
                || !Pattern.compile("(?m)^\\s*\\S+\\s+\\S+\\s+\\d+nm\\s+\\S+.*$").matcher(optical).find())) return false;
        String interfaces = String.join("\n", bodies.get(5));
        if (!missing.contains("show interface") && (!Pattern.compile(
                "(?im)^\\s*\\S+ is (?:up|down|administratively down), ifindex:\\s*\\d+\\s*$").matcher(interfaces).find()
                || !interfaces.contains("Line protocol is") || !interfaces.contains("BW "))) return false;
        String lacp = String.join("\n", bodies.get(6));
        return lacp.contains("Port[Flags]") && lacp.contains("State");
    }

    public static final class Result {
        private final Set<String> missing;
        private final List<String> safeLines;
        private final boolean pagingRejected;
        private Result(Set<String> missing, List<String> safeLines, boolean pagingRejected) {
            this.missing = Collections.unmodifiableSet(new LinkedHashSet<String>(missing));
            this.safeLines = Collections.unmodifiableList(new ArrayList<String>(safeLines));
            this.pagingRejected = pagingRejected;
        }
        public boolean isPartial() { return !missing.isEmpty(); }
        public boolean isCompleteWithPagingFallback() { return pagingRejected && missing.isEmpty(); }
        public Set<String> missingCommands() { return missing; }
        public List<String> safeLines() { return safeLines; }
        public String detail() { return "Complete (Partial); unavailable: " + String.join(", ", missing)
                + "; device service timeout 141365; eligible for daily retry"; }
    }
}
