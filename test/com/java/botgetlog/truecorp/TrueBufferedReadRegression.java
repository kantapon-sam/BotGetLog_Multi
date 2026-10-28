package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Offline differential tests against the retained byte-at-a-time reader. */
public final class TrueBufferedReadRegression {
    private static final Class<?> TYPE;
    private static final Constructor<?> CONSTRUCTOR;
    private static final Method READ;
    private static int checks;
    static {
        try {
            TYPE = Class.forName("com.java.botgetlog.truecorp.Telnet_Multi$CredentialProbe");
            CONSTRUCTOR = TYPE.getDeclaredConstructor(); CONSTRUCTOR.setAccessible(true);
            READ = TYPE.getDeclaredMethod("readUntilPromptOnlyLarge", String[].class); READ.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static Field field(String name) throws Exception {
        Field f = TYPE.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(label);
    }
    private static final class ChunkInput extends ByteArrayInputStream {
        final int chunk; long singles, blocks;
        ChunkInput(String data, int chunk) { super(data.getBytes(StandardCharsets.ISO_8859_1)); this.chunk = chunk; }
        @Override public synchronized int available() { return Math.min(super.available(), chunk); }
        @Override public synchronized int read() { singles++; return super.read(); }
        @Override public synchronized int read(byte[] b, int off, int len) {
            blocks++; return super.read(b, off, Math.min(len, chunk));
        }
    }
    private static final class Result {
        final List<String> responses = new ArrayList<String>();
        String writes, prompt, remaining; long singles, blocks, cpu;
    }
    private static Result read(String data, String prompt, int chunk, int count) throws Exception {
        Object probe = CONSTRUCTOR.newInstance();
        ChunkInput input = new ChunkInput(data, chunk);
        ByteArrayOutputStream writes = new ByteArrayOutputStream();
        field("in").set(probe, input);
        field("out").set(probe, new PrintStream(writes, true, "UTF-8"));
        field("lastPromptToken").set(probe, prompt);
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long start = bean.getCurrentThreadCpuTime();
        Result r = new Result();
        for (int i = 0; i < count; i++) r.responses.add((String) READ.invoke(probe, (Object) new String[]{"#", ">", "]"}));
        r.cpu = bean.getCurrentThreadCpuTime() - start;
        r.singles = input.singles; r.blocks = input.blocks;
        r.writes = writes.toString("UTF-8"); r.prompt = (String) field("lastPromptToken").get(probe);
        InputStream remainder = (InputStream) field("in").get(probe);
        StringBuilder tail = new StringBuilder();
        while (remainder.available() > 0) tail.append((char) remainder.read());
        r.remaining = tail.toString(); return r;
    }
    private static Result parity(String data, String prompt, int chunk, int count) throws Exception {
        System.setProperty("true.log.bufferedRead", "false"); Result old = read(data, prompt, chunk, count);
        System.setProperty("true.log.bufferedRead", "true"); Result updated = read(data, prompt, chunk, count);
        equal(old.responses, updated.responses, "response differs: " + prompt + ", chunk=" + chunk);
        equal(old.writes, updated.writes, "pagination spaces differ");
        equal(old.prompt, updated.prompt, "saved prompt differs");
        equal(old.remaining, updated.remaining, "read-ahead bytes lost");
        equal(0L, updated.singles, "new reader used single-byte I/O");
        return updated;
    }
    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
    private static void boundaryCases() throws Exception {
        String[][] prompts = {{"<QA-HW>", "<QA-HW>"}, {"HRP_M<QA-HW>", "HRP_S<QA-HW>"},
            {"B:QA-NOKIA#", "*A:QA-NOKIA#"}, {"user@QA-JUNOS>", "user@QA-JUNOS>"},
            {"QA-ZTE#", "QA-ZTE#"}, {"[QA-HW]", "[QA-HW]"}, {"", "<QA-HW>"}};
        for (String[] pair : prompts) {
            for (int chunk : new int[]{1, 7, 8192}) {
                String body = (pair[0].isEmpty() ? "display interface\r\n" : "display interface\r\nDescription: Tie#\r\n  61,62-CORE-ODF#\r\n")
                        + "Description: <OTHER-NODE> and values [x]\r\nMore ordinary text\r\n"
                        + "raw bytes \u00ff\u0080\u00e9\r\n"
                        + (pair[0].contains("@") ? "\r" + pair[0] + " echo redraw\r\n" : "")
                        + "END-OF-OUTPUT\r\n" + pair[1];
                Result result = parity(body + "\r\nnext command\r\nSECOND\r\n" + pair[1] + "\r\nTRAILING", pair[0], chunk, 2);
                // With an established prompt the description must not stop the read.
                if (!pair[0].isEmpty()) equal(true, result.responses.get(0).contains("END-OF-OUTPUT"), "early prompt");
            }
        }
        // Place both prompt and pager across the 8 KB read boundary.
        for (int padding : new int[]{8185, 8190, 8191, 8192}) {
            parity(repeat("x", padding) + "\r\n<QA-HW>\r\nTAIL", "<QA-HW>", 8192, 1);
        }
        for (String marker : new String[]{"--more--", "MORE?", "---- more ----", "--- more ---", "-- more --",
                "---(more)---", "---(more 50%)---", "---(more 100%)---", "--more (Press Space)--"}) {
            for (int chunk : new int[]{1, 7, 8192}) {
                Result r = parity(repeat("x", 8188) + "\r\n" + marker + "\b\b\r\nSECOND PAGE\r\n<QA-HW>\r\n", "<QA-HW>", chunk, 1);
                equal(" ", r.writes, "exactly one page continuation");
                equal(true, r.responses.get(0).contains("SECOND PAGE"), "second page missing");
            }
        }
        // Ring wrap and eviction must not retain a stale 'more' counter.
        parity("more\r\n" + repeat("x", 17000) + "\r\n----\r\nEND\r\n<QA-HW>", "<QA-HW>", 8192, 1);
        // Keep the legacy 2 MB retention/truncation behavior byte-identical.
        parity(repeat("0123456789abcdefghij\r\n", 96000) + "END\r\n<QA-HW>\r\n", "<QA-HW>", 8192, 1);
    }
    private static void errorCases() throws Exception {
        for (boolean buffered : new boolean[]{false, true}) {
            System.setProperty("true.log.bufferedRead", Boolean.toString(buffered));
            Object probe = CONSTRUCTOR.newInstance();
            equal("", READ.invoke(probe, (Object) new String[]{"#", ">"}), "null stream");
            field("in").set(probe, new InputStream() {
                @Override public int available() { return 1; }
                @Override public int read() throws IOException { throw new IOException("synthetic disconnect"); }
            });
            equal("[TIMEOUT-READ]", READ.invoke(probe, (Object) new String[]{"#", ">"}), "read failure contract");
        }
    }
    private static void benchmark() throws Exception {
        final String text = repeat("GE0/0/1 current state : UP\r\nLine protocol current state : UP\r\nDescription: To-CORE\r\n", 2000)
                + "END\r\n<QA-HW>\r\n";
        List<Result> previous = null;
        for (boolean buffered : new boolean[]{false, true}) {
            System.setProperty("true.log.bufferedRead", Boolean.toString(buffered));
            ExecutorService pool = Executors.newFixedThreadPool(20);
            List<Future<Result>> futures = new ArrayList<Future<Result>>();
            long start = System.nanoTime(); long cpu = 0, singles = 0, blocks = 0;
            List<Result> results = new ArrayList<Result>();
            try {
                for (int i = 0; i < 20; i++) futures.add(pool.submit(() -> read(text, "<QA-HW>", 8192, 1)));
                for (Future<Result> f : futures) {
                    Result r = f.get(120, TimeUnit.SECONDS); results.add(r);
                    cpu += r.cpu; singles += r.singles; blocks += r.blocks;
                }
            } finally { pool.shutdownNow(); }
            if (previous != null) {
                for (int i = 0; i < 20; i++) equal(previous.get(i).responses, results.get(i).responses, "parallel output differs");
                equal(0L, singles, "parallel single-byte reads");
                equal(true, blocks < text.length() * 20L / 100, "block read reduction");
            }
            System.out.printf(Locale.ROOT, "reader=%s nodes=20 bytesPerNode=%d cpuMs=%.2f wallMs=%.2f singleReads=%d blockReads=%d%n",
                    buffered ? "buffered" : "legacy", text.length(), cpu / 1e6, (System.nanoTime() - start) / 1e6, singles, blocks);
            previous = results;
        }
    }
    public static void main(String[] args) throws Exception {
        String saved = System.getProperty("true.log.bufferedRead");
        try {
            boundaryCases(); errorCases(); benchmark();
            System.out.println("TRUE_BUFFERED_READ_OK checks=" + checks);
        } finally {
            if (saved == null) System.clearProperty("true.log.bufferedRead"); else System.setProperty("true.log.bufferedRead", saved);
        }
    }
}
