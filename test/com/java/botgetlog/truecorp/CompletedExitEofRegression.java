package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import sun.misc.Unsafe;

/** Offline replay: a final quit EOF must not lose a completed collection. */
public final class CompletedExitEofRegression {
    private static final String DEVICE = "AN-ICONS-2_BKK18004S01";
    private static final String IP = "10.85.159.134";
    private static final String CMDSET = "HW-LLDP-Link_OPTIC";
    private static final String PROMPT = "<" + DEVICE + ">";
    private static final String HEAD = PROMPT + "screen-length 0 temporary\n"
            + PROMPT + "display cpu-usage\nSystem cpu use rate is : 2%\n"
            + PROMPT + "display memory-usage\nMemory Using Percentage Is: 20%\n"
            + PROMPT + "display lldp neighbor\nLLDP neighbor information\n"
            + PROMPT + "display version\nVRP software\n"
            + PROMPT + "display interface\nInterface data complete\n";
    private static final String CLOSE = PROMPT + "quit\ncript: write error: Interrupted system call\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("completed-exit-eof-");
        try {
            cache("CMDSET_FIRST_COMMAND_CACHE", "screen-length 0 temporary");
            cache("CMDSET_LAST_COMMAND_CACHE", "quit");
            commandCache(CMDSET, "screen-length 0 temporary", "display cpu-usage", "display memory-usage",
                    "display lldp neighbor", "display version", "display interface", "quit");
            File log = dir.resolve("[24058]" + IP + "_AN-ICONS-2-BKK18004S01_" + CMDSET + "_2026-09-30.txt").toFile();
            write(log, HEAD + CLOSE);
            equal(true, accepts(log, "quit", true, "stream ended before prompt"));
            equal(true, accepts(log, "quit", true, "SSH stream ended before prompt"));
            equal(false, accepts(log, "quit", false, "stream ended before prompt"));
            equal(false, accepts(log, "display interface", true, "stream ended before prompt"));
            equal(false, accepts(log, "quit", true, "Connection reset"));
            equal(false, accepts(log, "quit", true, "prompt wait exceeded 60s"));
            equal(false, Telnet_Multi.isCompleteLogAfterExitClose(log, "HW-Config_PORT_Reserved", "quit", true, "stream ended before prompt"));
            write(log, HEAD); // final command never reached
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));
            write(log, CLOSE); // missing collection header
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));
            write(log, HEAD + "Error: Unrecognized command found at '^' position.\n" + CLOSE);
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));
            write(log, (HEAD + CLOSE).replace(DEVICE, "OTHER-NODE"));
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));
            write(log, PROMPT + "screen-length 0 temporary\n" + CLOSE);
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));
            write(log, (HEAD + CLOSE).replace(PROMPT + "display memory-usage\nMemory Using Percentage Is: 20%\n", ""));
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));
            write(log, (HEAD + CLOSE).replace("Interface data complete\n", ""));
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));
            write(log, (HEAD + CLOSE).replace(PROMPT + "display version", "<OTHER-NODE>display version"));
            equal(false, accepts(log, "quit", true, "stream ended before prompt"));

            // Run the actual command/stream handling with memory streams, no device connections.
            String diagnostic = "cript: write error: Interrupted system call\n";
            replay(dir, log, HEAD + PROMPT + "quit\n", diagnostic, true, true, CMDSET, DEVICE, "quit");
            replay(dir, log, HEAD + PROMPT + "quit\n", diagnostic, false, false, CMDSET, DEVICE, "quit");
            replay(dir, log, "", diagnostic, true, false, CMDSET, DEVICE, "quit");
            replay(dir, log, HEAD + "Error: Unrecognized command found at '^' position.\n" + CLOSE, diagnostic, true, false, CMDSET, DEVICE, "quit");
            replay(dir, log, HEAD, diagnostic, true, false, CMDSET, DEVICE, "quit");
            // Even a normal exit response cannot repair missing data in the middle.
            replay(dir, log, HEAD.replace("Interface data complete\n", "") + PROMPT + "quit\n",
                    "Enter IP address [press q/Q to quit]:\n", true, false, CMDSET, DEVICE, "quit");
            equal(false, BotGetLog_TrueCorp.isLogCompleteForCmdSet(log, CMDSET));
            equal(true, new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).contains("[BOT-COLLECTION-INCOMPLETE]"));
            promptReplay(dir, log, PROMPT + "display interface\nInterface data\n" + PROMPT, true);
            promptReplay(dir, log, PROMPT + "display interface\npartial output\n", false);
            promptReplay(dir, log, PROMPT + "display interface\npartial output\n#", false);
            promptReplay(dir, log, PROMPT + "display interface\npartial output\n<OTHER-NODE>", false);
            promptReplay(dir, log, PROMPT + "display interface\npartial output\nDescriptionEnding#", false);
            sshPromptReplay(dir, log, PROMPT + "display interface\nInterface data\n" + PROMPT, true);
            sshPromptReplay(dir, log, PROMPT + "display interface\npartial output\n<OTHER-NODE>", false);
            sshPromptReplay(dir, log, PROMPT + "display interface\npartial output\nDescriptionEnding#", false);
            cache("CMDSET_FIRST_COMMAND_CACHE", "N-LLDP-Link_OPTIC", "environment no more");
            cache("CMDSET_LAST_COMMAND_CACHE", "N-LLDP-Link_OPTIC", "logout");
            commandCache("N-LLDP-Link_OPTIC", "environment no more", "show port", "logout");
            File nokiaLog = dir.resolve("[24058]" + IP + "_CPE-MHR7168_N-LLDP-Link_OPTIC_2026-09-30.txt").toFile();
            replay(dir, nokiaLog, "A:CPE-MHR7168#environment no more\nA:CPE-MHR7168#show port\nPorts\n1/1/26 Up\n"
                    + "A:CPE-MHR7168#show port 1/1/26 ethernet lldp remote-info\nNo neighbors\nA:CPE-MHR7168#show port 1/1/26\nPort data\n",
                    "A:CPE-MHR7168#logout\n", true, true, "N-LLDP-Link_OPTIC", "CPE-MHR7168", "logout");
            commandCache("N-LLDP-Link_OPTIC", "environment no more", "show system cpu", "show system memory-pools",
                    "show system lldp neighbor", "show chassis", "show version", "show port", "show lag description", "logout");
            for (String path : args) {
                File saved = new File(path);
                boolean nokia = saved.getName().contains("_N-LLDP-Link_OPTIC_");
                String set = nokia ? "N-LLDP-Link_OPTIC" : CMDSET;
                String command = nokia ? "logout" : "quit";
                String detail = nokia ? "logout" : "stream ended before prompt";
                equal(true, Telnet_Multi.isCompleteLogAfterExitClose(saved, set, command, true, detail));
                equal(false, Telnet_Multi.isCompleteLogAfterExitClose(saved, set, command, false, detail));
                equal(false, Telnet_Multi.isCompleteLogAfterExitClose(saved, set, "show port", true, detail));
                // Truncating the real transcript before the exit must still require retry.
                String text = new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8);
                int boundary = text.lastIndexOf(nokia ? "#logout" : ">quit");
                if (boundary < 0) throw new AssertionError("Missing fixture exit: " + saved);
                File truncated = dir.resolve(saved.getName()).toFile();
                write(truncated, text.substring(0, boundary));
                equal(false, Telnet_Multi.isCompleteLogAfterExitClose(truncated, set, command, true, detail));
                if (nokia) {
                    int portStart = text.indexOf("show port 1/1/26 ethernet lldp remote-info");
                    int following = text.indexOf("A:", text.indexOf('\n', portStart));
                    if (portStart < 0 || following < 0) throw new AssertionError("Missing port fixture");
                    int lineStart = text.lastIndexOf('\n', portStart) + 1;
                    write(truncated, text.substring(0, lineStart) + text.substring(following));
                    equal(false, Telnet_Multi.isCompleteLogAfterExitClose(truncated, set, command, true, detail));
                }
                // Keep the exit, but remove a middle command or its entire response.
                String middle = nokia ? "show version" : "display version";
                int middleStart = text.indexOf(middle);
                int nextPrompt = text.indexOf(nokia ? "A:" : "<", text.indexOf('\n', middleStart));
                if (middleStart < 0 || nextPrompt < 0) throw new AssertionError("Missing middle fixture command");
                int promptStart = text.lastIndexOf('\n', middleStart) + 1;
                write(truncated, text.substring(0, promptStart) + text.substring(nextPrompt));
                equal(false, Telnet_Multi.isCompleteLogAfterExitClose(truncated, set, command, true, detail));
            }
            System.out.println("PASS CompletedExitEofRegression " + checks + " checks");
        } finally {
            Field writersField = Telnet_Multi.class.getDeclaredField("TELNET_LOG_WRITERS"); writersField.setAccessible(true);
            Map<?, ?> writers = (Map<?, ?>) writersField.get(null);
            for (Object writer : writers.values()) ((Writer) writer).close();
            writers.clear();
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException e) { throw new UncheckedIOException(e); } });
            }
        }
    }

    private static void replay(Path dir, File log, String initial, String response, boolean last, boolean expected,
            String cmdSet, String device, String command) throws Exception {
        Telnet_Multi collector = fixture(dir, log, initial, response);
        Method execute = Telnet_Multi.class.getDeclaredMethod("executeCommandWithReconnect", String.class, String.class, String.class, int.class, String.class, boolean.class);
        execute.setAccessible(true);
        equal(expected, execute.invoke(collector, IP, device, cmdSet, 24058, command, last));
        equal(!expected, collector.hasSessionFailureRecorded());
        Field warning = Telnet_Multi.class.getDeclaredField("completedExitWarning"); warning.setAccessible(true);
        equal(expected, ((String) warning.get(collector)).contains("log verified complete"));
        collector.disconnect();
    }

    private static void promptReplay(Path dir, File log, String response, boolean expected) throws Exception {
        Telnet_Multi collector = fixture(dir, log, PROMPT + "screen-length 0 temporary\n", response);
        Method execute = Telnet_Multi.class.getDeclaredMethod("executeCommandWithReconnect", String.class, String.class, String.class, int.class, String.class, boolean.class);
        execute.setAccessible(true);
        equal(expected, execute.invoke(collector, IP, DEVICE, CMDSET, 24058, "display interface", false));
        equal(!expected, collector.hasSessionFailureRecorded());
        equal(!expected, collector.hasRetryableNetworkFailureRecorded());
        collector.disconnect();
    }

    private static Telnet_Multi fixture(Path dir, File log, String initial, String response) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Telnet_Multi collector = (Telnet_Multi) unsafe.allocateInstance(Telnet_Multi.class);
        PathFile paths = (PathFile) unsafe.allocateInstance(PathFile.class);
        field(paths, "Log", dir.toString());
        field(paths, "LogWork", dir.toString());
        field(collector, "FileInput", paths);
        field(collector, "formattedDateTimeLOG", "fixture");
        field(collector, "formattedDateTime", "2026-09-30");
        field(collector, "preparedLogSessionKey", log.getAbsolutePath());
        field(collector, "completedExitWarning", "");
        field(collector, "runtimeDeviceName", "");
        field(collector, "in", new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        field(collector, "out", new PrintStream(new ByteArrayOutputStream()));
        write(log, initial);
        return collector;
    }

    private static void sshPromptReplay(Path dir, File log, String response, boolean expected) throws Exception {
        Telnet_Multi collector = fixture(dir, log, "", response);
        Method read = Telnet_Multi.class.getDeclaredMethod("readStreamToFileSsh", BufferedOutputStream.class,
                List.class, long.class, long.class, long.class, String.class, String.class, boolean.class);
        read.setAccessible(true);
        System.setProperty("botgetlog.command.promptSettle.ms", "100");
        Object result;
        try (BufferedOutputStream saved = new BufferedOutputStream(new ByteArrayOutputStream())) {
            result = read.invoke(collector, saved, Arrays.asList(PROMPT), System.currentTimeMillis(), 1000L, 250L,
                    "[fixture]", DEVICE, false);
        } finally { System.clearProperty("botgetlog.command.promptSettle.ms"); }
        Field detected = result.getClass().getDeclaredField("promptDetected"); detected.setAccessible(true);
        equal(expected, detected.get(result));
        collector.disconnect();
    }

    @SuppressWarnings("unchecked")
    private static void cache(String name, String value) throws Exception {
        cache(name, CMDSET, value);
    }
    @SuppressWarnings("unchecked")
    private static void cache(String name, String cmdSet, String value) throws Exception {
        Field f = BotGetLog_TrueCorp.class.getDeclaredField(name); f.setAccessible(true);
        ((Map<String, String>) f.get(null)).put(cmdSet.toLowerCase(Locale.ROOT), value);
    }
    @SuppressWarnings("unchecked")
    private static void commandCache(String cmdSet, String... commands) throws Exception {
        Field f = BotGetLog_TrueCorp.class.getDeclaredField("CMDSET_COMMAND_CACHE"); f.setAccessible(true);
        List<String> values = new ArrayList<>(); values.add(cmdSet); values.addAll(Arrays.asList(commands));
        ((Map<String, List<String>>) f.get(null)).put(cmdSet.toLowerCase(Locale.ROOT), values);
    }
    private static void field(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static boolean accepts(File log, String command, boolean last, String detail) {
        return Telnet_Multi.isCompleteLogAfterExitClose(log, CMDSET, command, last, detail);
    }
    private static void write(File log, String text) throws Exception { Files.write(log.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Check " + checks + ": expected " + expected + ", got " + actual);
    }
}
