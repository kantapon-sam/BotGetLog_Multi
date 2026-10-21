package com.java.botgetlog.dtac;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercise the actual DTAC SSH command loop with responses released only on send. */
public final class HuaweiMemoryFallbackRegression {
    private static final String ERROR = "Error: Unrecognized command found at '^' position.\n";
    private static final String MEMORY = "Total Physical Available Memory: 15813813 Kbytes\n"
            + "Total Physical Memory Used: 7177829 Kbytes\nPhysical Memory Using Percentage: 45%\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        run("HW", ERROR, MEMORY, true, true);
        run("HW", MEMORY, MEMORY, true, false);
        run("HW", ERROR, ERROR, false, true);
        run("HW", ERROR, "", false, true);
        run("HW", ERROR, MEMORY.replace("45%", "145%"), false, true);
        run("HW", ERROR, MEMORY.replace("7177829", "99999999"), false, true);
        run("HW", ERROR, MEMORY + ERROR, false, true);
        run("HW", "Error: Permission denied.\n", MEMORY, true, false);
        run("ZTE", "gei-1/1/0/1 offline\n", "", true, false);
        System.out.println("PASS DTAC HuaweiMemoryFallbackRegression " + checks + " checks");
    }

    private static void run(String vendor, String original, String fallback, boolean complete,
            boolean shouldFallback) throws Exception {
        String prompt = "HW".equals(vendor) ? "<MEMORY-TEST>" : "MEMORY-TEST#";
        String first = "HW".equals(vendor) ? "screen-length 0 temporary" : "terminal length 0";
        String command = "HW".equals(vendor) ? "display memory-usage" : "show opt brief";
        Exchange exchange = new Exchange(prompt, command, original, fallback);
        // Empty credentials initialize local state and return before any network connection.
        SSH_Multi collector = new SSH_Multi("", "", "", "", "", "", vendor + "-LLDP-Link_OPTIC", "MEMORY-TEST", 1, "", "");
        StringWriter saved = new StringWriter();
        collector.collectCommands(exchange, exchange.sender(), () -> false, saved,
                new StringBuilder(), new byte[8192], Arrays.asList("", first, command, "quit"),
                vendor, vendor + "-LLDP-Link_OPTIC", "MEMORY-TEST", "synthetic", 1500L, 500L, 10L);
        List<String> expected = new ArrayList<>(Arrays.asList(first, command));
        if (shouldFallback) expected.add("display memory");
        if (complete) expected.add("quit");
        check(expected.equals(exchange.sent), "Sent " + exchange.sent + " expected " + expected);
        check(saved.toString().contains(original), "Raw response lost");
        if (shouldFallback) check(saved.toString().contains("display memory\n"), "Fallback header lost");
        Path log = Files.createTempFile("[1]10.0.0.1_MEMORY-TEST_" + vendor + "-LLDP-Link_OPTIC_", ".txt");
        try {
            Files.write(log, saved.toString().getBytes(StandardCharsets.UTF_8));
            Method valid = BotGetLog_DTAC.class.getDeclaredMethod("containsPromptPlusFirstAndLastCommand", File.class, List.class, List.class);
            valid.setAccessible(true);
            check(complete == (Boolean) valid.invoke(null, log.toFile(), Arrays.asList(first), Arrays.asList("quit")), "Completion mismatch");
        } finally { Files.delete(log); }
    }

    private static final class Exchange extends InputStream {
        final String prompt, originalCommand, original, fallback;
        final List<String> sent = new ArrayList<>();
        ByteArrayInputStream response = new ByteArrayInputStream(new byte[0]);
        Exchange(String prompt, String originalCommand, String original, String fallback) {
            this.prompt = prompt; this.originalCommand = originalCommand; this.original = original; this.fallback = fallback;
        }
        OutputStream sender() {
            return new ByteArrayOutputStream() {
                @Override public void write(int value) {
                    if (value == '\n' || value == '\r') {
                        String command = new String(toByteArray(), StandardCharsets.UTF_8).trim(); reset();
                        if (command.isEmpty()) return;
                        sent.add(command);
                        String body = originalCommand.equals(command) ? original : "display memory".equals(command) ? fallback : "";
                        response = new ByteArrayInputStream((prompt + command + "\n" + body + prompt + "\n").getBytes(StandardCharsets.UTF_8));
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
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
