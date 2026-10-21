package com.java.botgetlog.truecorp;

import com.java.tools.linkoptical.CpuMemoryExporter;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Command-driven replay: preserve ordinary Huawei and fail closed on bad fallbacks. */
public final class HuaweiMemoryFallbackRegression {
    private static final String NODE = "MEMORY-TEST";
    private static final String PROMPT = "HRP_M<" + NODE + ">";
    private static final String SET = "HW-LLDP-Link_OPTIC";
    private static final String ERROR = "Error: Unrecognized command found at '^' position.\n";
    private static final String PHYSICAL = "Slot: 9   CPU: 0\n"
            + "Total Physical Available Memory: 15813813 Kbytes\n"
            + "Total Physical Memory Used: 7177829 Kbytes\n"
            + "Physical Memory Using Percentage: 45%\nState: Unoverload\n";
    private static final String LEGACY = "System Total Memory Is: 1024000 Kbytes\n"
            + "Total Memory Used Is: 256000 Kbytes\nMemory Using Percentage Is: 25%\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        System.setProperty("botgetlog.command.promptSettle.ms", "100");
        check(Telnet_Multi.shouldFallbackHuaweiMemory("display memory-usage", ERROR));
        check(!Telnet_Multi.shouldFallbackHuaweiMemory("display memory-usage", LEGACY));
        check(!Telnet_Multi.shouldFallbackHuaweiMemory("display memory", ERROR));
        check(!Telnet_Multi.shouldFallbackHuaweiMemory("display memory-usage slot 9", ERROR));
        check(!Telnet_Multi.shouldFallbackHuaweiMemory("display version", ERROR));
        check(!Telnet_Multi.shouldFallbackHuaweiMemory("display memory-usage", "Error: Permission denied.\n"));
        check(!Telnet_Multi.shouldFallbackHuaweiMemory("display memory-usage", "Description: " + ERROR));
        check(!Telnet_Multi.shouldFallbackHuaweiMemory("display memory-usage", null));

        CpuMemoryExporter.CpuMemorySnapshot memory = CpuMemoryExporter.parseSnapshot("", "10.0.0.1",
                PROMPT + "display memory\n" + PHYSICAL + PROMPT);
        check(NODE.equals(memory.siteCode));
        equal(45.0, memory.memoryUsedPercent);
        equal(15813813.0 / 1024, memory.memoryTotalMb);
        equal(7177829.0 / 1024, memory.memoryUsedMb);
        equal((15813813.0 - 7177829) / 1024, memory.memoryFreeMb);
        equal(55.0, memory.memoryFreePercent);
        memory = CpuMemoryExporter.parseSnapshot("", "10.0.0.1",
                "<OLD-HW>display cpu-usage\nSystem cpu use rate is : 3%\n"
                + "<OLD-HW>display memory-usage\n" + LEGACY);
        equal(3.0, memory.cpuCurrentPercent);
        equal(25.0, memory.memoryUsedPercent);
        equal(1000.0, memory.memoryTotalMb);
        check(CpuMemoryExporter.hasValidHuaweiMemoryData(PHYSICAL));
        check(CpuMemoryExporter.hasValidHuaweiMemoryData(LEGACY.replace(" Is", "")));
        check(!CpuMemoryExporter.hasValidHuaweiMemoryData(PHYSICAL.replace("45%", "145%")));
        check(!CpuMemoryExporter.hasValidHuaweiMemoryData(PHYSICAL.replace("7177829", "99999999")));
        check(!CpuMemoryExporter.hasValidHuaweiMemoryData("Physical Memory Using Percentage: 45%\n"));

        Path dir = Files.createTempDirectory("huawei-memory-regression-");
        try {
            cache("CMDSET_FIRST_COMMAND_CACHE", "screen-length 0 temporary");
            cache("CMDSET_LAST_COMMAND_CACHE", "quit");
            cache("CMDSET_COMMAND_CACHE", Arrays.asList(SET, "screen-length 0 temporary",
                    "display cpu-usage", "display memory-usage", "display version", "quit"));
            File log = dir.resolve("[24058]10.85.159.134_" + NODE + "_" + SET + "_"
                    + java.time.LocalDate.now() + ".txt").toFile();
            String head = PROMPT + "screen-length 0 temporary\n" + PROMPT
                    + "display cpu-usage\nSystem cpu use rate is : 3%\n";
            String tail = PROMPT + "display version\nHuawei YunShan OS\n" + PROMPT + "quit\n";
            String rejection = PROMPT + "display memory-usage\ndisplay memory-usage\n   ^\n" + ERROR;
            String fallback = PROMPT + "\n" + PROMPT + "display memory\ndisplay memory\n" + PHYSICAL;
            String good = head + rejection + fallback + tail;
            accepts(log, good, true);
            accepts(log, good.replace("HRP_M", "HRP_S"), true);
            accepts(log, head + rejection + fallback.replace("HRP_M", "HRP_S") + tail, true);
            accepts(log, head + PROMPT + "display memory-usage\n" + LEGACY + tail, true);
            accepts(log, head + rejection + tail, false);
            accepts(log, head + rejection + fallback.replace(PHYSICAL, "") + tail, false);
            accepts(log, head + rejection + fallback.replace(PHYSICAL, ERROR) + tail, false);
            accepts(log, head + rejection + fallback.replace(PHYSICAL, PHYSICAL + ERROR) + tail, false);
            accepts(log, head + rejection + fallback.replace(NODE, "OTHER-NODE") + tail, false);
            accepts(log, head + rejection + fallback.replace("display memory\n", "display cpu-usage\n") + tail, false);
            accepts(log, head + rejection + fallback.replace("45%", "145%") + tail, false);
            accepts(log, head + rejection + fallback.replace("7177829", "99999999") + tail, false);
            accepts(log, head + rejection + fallback, false); // EOF before fallback prompt
            accepts(log, head + ERROR + rejection + fallback + tail, false);
            accepts(log, good + ERROR, false);
            accepts(log, head + rejection + PROMPT + "display version\nVersion\n" + fallback + tail, false);
            accepts(log, head + rejection + fallback + PROMPT + "display memory-usage\n" + ERROR + tail, false);
            accepts(log, head + rejection + fallback + tail.replace(PROMPT, "<OTHER-NODE>"), false);

            // Drive the real normal collector and live collector by commands, not
            // by a pre-concatenated stream that could leak a future response early.
            replayCollector(dir, log, ERROR, PHYSICAL, true, 2);
            replayCollector(dir, log, LEGACY, PHYSICAL, true, 1);
            replayCollector(dir, log, ERROR, ERROR, false, 2);
            replayCollector(dir, log, ERROR, "", false, 2);
            replayLive(ERROR, PHYSICAL, true, 2);
            replayLive(LEGACY, PHYSICAL, true, 1);
            replayLive(ERROR, ERROR, false, 2);
            replayLive(ERROR, "", false, 2);

            // The SSH reader must return the same evidence used by the fallback.
            Method fixture = CompletedExitEofRegression.class.getDeclaredMethod("fixture",
                    Path.class, File.class, String.class, String.class);
            fixture.setAccessible(true);
            Telnet_Multi collector = (Telnet_Multi) fixture.invoke(null, dir, log, "",
                    PROMPT + "display memory-usage\n" + ERROR + PROMPT);
            Method ssh = Telnet_Multi.class.getDeclaredMethod("readStreamToFileSsh", BufferedOutputStream.class,
                    List.class, long.class, long.class, long.class, String.class, String.class, boolean.class);
            ssh.setAccessible(true);
            Object result = ssh.invoke(collector, new BufferedOutputStream(new ByteArrayOutputStream()),
                    Arrays.asList(PROMPT), System.currentTimeMillis(), 2000L, 1000L, "[memory-test]", NODE, false);
            check(Telnet_Multi.shouldFallbackHuaweiMemory("display memory-usage", (String) get(result, "responseTail")));
            collector.disconnect();
            System.out.println("PASS HuaweiMemoryFallbackRegression " + checks + " checks");
        } finally {
            Map<?, ?> writers = (Map<?, ?>) getStatic(Telnet_Multi.class, "TELNET_LOG_WRITERS");
            for (Object writer : writers.values()) ((Writer) writer).close();
            writers.clear();
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); }
                    catch (IOException e) { throw new UncheckedIOException(e); } });
            }
        }
    }

    private static void accepts(File log, String text, boolean expected) throws Exception {
        Files.write(log.toPath(), text.getBytes(StandardCharsets.UTF_8));
        boolean valid = BotGetLog_TrueCorp.isLogCompleteForCmdSet(log, SET)
                && Telnet_Multi.hasCompleteCollectionTranscript(log, SET);
        check(valid == expected);
    }

    private static void replayCollector(Path dir, File log, String original, String fallback,
            boolean expected, int commands) throws Exception {
        Method fixture = CompletedExitEofRegression.class.getDeclaredMethod("fixture",
                Path.class, File.class, String.class, String.class);
        fixture.setAccessible(true);
        Telnet_Multi collector = (Telnet_Multi) fixture.invoke(null, dir, log,
                PROMPT + "screen-length 0 temporary\n", "");
        Exchange exchange = new Exchange(original, fallback);
        set(collector, "in", exchange);
        set(collector, "out", new PrintStream(exchange.sender(), true, "UTF-8"));
        set(collector, "s", PROMPT);
        Method execute = Telnet_Multi.class.getDeclaredMethod("executeCommandWithReconnect",
                String.class, String.class, String.class, int.class, String.class, boolean.class);
        execute.setAccessible(true);
        check(expected == (Boolean) execute.invoke(collector, "10.85.159.134", NODE, SET, 24058,
                "display memory-usage", false));
        check(exchange.sent.size() == commands);
        check(collector.hasSessionFailureRecorded() != expected);
        String saved = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        check(saved.contains(original));
        if (commands == 2) check(saved.contains("display memory\n"));
        collector.disconnect();
    }

    private static void replayLive(String original, String fallback, boolean expected, int commands) throws Exception {
        Class<?> type = Class.forName("com.java.botgetlog.truecorp.Telnet_Multi$CredentialProbe");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object probe = constructor.newInstance();
        Exchange exchange = new Exchange(original, fallback);
        exchange.echoPrompt = false;
        set(probe, "in", exchange); set(probe, "out", new PrintStream(exchange.sender(), true, "UTF-8"));
        set(probe, "lastPromptToken", PROMPT);
        Method execute = type.getDeclaredMethod("executeCommands", List.class, boolean.class); execute.setAccessible(true);
        boolean success;
        try {
            String text = (String) execute.invoke(probe, Arrays.asList("display memory-usage"), false);
            success = !text.contains("[TIMEOUT");
            if (expected && !text.contains(original)) throw new AssertionError("Live response=" + text + " sent=" + exchange.sent);
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IOException)) throw e;
            success = false;
        } finally { ((AutoCloseable) probe).close(); }
        if (success != expected || exchange.sent.size() != commands) throw new AssertionError("Live expected=" + expected + " actual=" + success + " commands=" + exchange.sent);
        check(success == expected); check(exchange.sent.size() == commands);
    }

    private static final class Exchange extends InputStream {
        final String original, fallback;
        boolean echoPrompt = true;
        final List<String> sent = new ArrayList<>();
        ByteArrayInputStream response = new ByteArrayInputStream(new byte[0]);
        Exchange(String original, String fallback) { this.original = original; this.fallback = fallback; }
        OutputStream sender() {
            return new ByteArrayOutputStream() {
                @Override public void write(int value) {
                    if (value == '\n' || value == '\r') {
                        String command = new String(toByteArray(), StandardCharsets.UTF_8).trim(); reset();
                        if (command.isEmpty()) return;
                        sent.add(command);
                        String body = "display memory-usage".equals(command) ? original : fallback;
                        response = new ByteArrayInputStream(((echoPrompt ? PROMPT : "") + command + "\n" + body + PROMPT + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                    } else super.write(value);
                }
                @Override public void write(byte[] bytes, int offset, int length) {
                    for (int i = offset; i < offset + length; i++) write(bytes[i]);
                }
            };
        }
        @Override public int available() { return response.available(); }
        @Override public int read() { return response.read(); }
        @Override public int read(byte[] bytes, int offset, int length) { return response.read(bytes, offset, length); }
    }
    @SuppressWarnings("unchecked") private static void cache(String name, Object value) throws Exception {
        ((Map<String, Object>) getStatic(BotGetLog_TrueCorp.class, name)).put(SET.toLowerCase(Locale.ROOT), value);
    }
    private static Object getStatic(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    private static Object get(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static void equal(double expected, Double actual) {
        check(actual != null && Math.abs(expected - actual) < 0.0001);
    }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Check " + (checks + 1));
        checks++;
    }
}
