package com.java.botgetlog.truecorp;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JuniperLogCompletenessRegression {
    private static final String DEVICE = "CN-TEST-01-CORE-1-re0";
    private static final String PROMPT = "operator@clls@CN-TEST-01_CORE-1_re0> ";
    private static final String HEAD = "\n{master}\n" + PROMPT + "set cli screen-length 0\nScreen length set to 0\n";
    private static final String CLOSE = PROMPT + "quit Connection closed by foreign host.\n";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("juniper-complete-");
        try {
            check(directory, "role and joined close", HEAD + CLOSE, true);
            check(directory, "no space before close", HEAD + CLOSE.replace("quit Connection", "quitConnection"), true);
            check(directory, "separate close", HEAD + CLOSE.replace("quit Connection", "quit\nConnection"), true);
            check(directory, "backup role", HEAD.replace("{master}", "{backup:1}") + CLOSE, true);
            check(directory, "no role", HEAD.replace("{master}\n", "") + CLOSE, true);
            check(directory, "lowercase prompt", (HEAD + CLOSE).toLowerCase(), true);
            check(directory, "ANSI and CRLF", (HEAD + CLOSE).replace(PROMPT, "\u001b[32m" + PROMPT + "\u001b[0m").replace("\n", "\r\n"), true);
            check(directory, "hostname prompt only", (HEAD + CLOSE).replace("operator@clls@", ""), true);
            check(directory, "missing first command", "{master}\n" + PROMPT + "show lldp neighbors detail\n" + CLOSE, false);
            check(directory, "unknown preamble", "Unexpected banner\n" + HEAD + CLOSE, false);
            check(directory, "wrong device", (HEAD + CLOSE).replace("CN-TEST-01", "OTHER-NODE"), false);
            check(directory, "wrong routing engine", (HEAD + CLOSE).replace("_re0", "_re1"), false);
            check(directory, "partial device", (HEAD + CLOSE).replace("CN-TEST-01_CORE-1_re0", "CORE-1_re0"), false);
            check(directory, "missing close command", HEAD + "Connection closed by foreign host.\n", false);
            check(directory, "missing close acknowledgement", HEAD + PROMPT + "quit\n", false);
            check(directory, "close command error", HEAD + PROMPT + "quit\nsyntax error\nConnection closed by foreign host.\n", false);
            check(directory, "unrelated closing text", HEAD + PROMPT + "quitOther output Connection closed by foreign host.\n", false);
            check(directory, "different closer", HEAD + CLOSE.replace("CN-TEST-01", "OTHER-NODE"), false);
            check(directory, "data mentions close", HEAD + "description: " + CLOSE, false);
            check(directory, "stale bare quit", HEAD + "quit\nConnection closed by foreign host.\n", false);
            nonJuniper(directory, "N-LLDP-Link_OPTIC", "A:PN-TEST# environment no more\nA:PN-TEST# logout\n", "PN-TEST", "environment no more", "logout");
            nonJuniper(directory, "HW-LLDP-Link_OPTIC", "<CPE-TEST>screen-length 0 temporary\n<CPE-TEST>quit\n", "CPE-TEST", "screen-length 0 temporary", "quit");
            nonJuniper(directory, "ZTE-LLDP-Link_OPTIC", "CPE-TEST#terminal length 0\nCPE-TEST#exit\n", "CPE-TEST", "terminal length 0", "exit");
            System.out.println("PASS JuniperLogCompletenessRegression " + checks + " checks");
        } finally {
            for (File file : directory.toFile().listFiles()) Files.delete(file.toPath());
            Files.delete(directory);
        }
    }

    private static void check(Path directory, String label, String text, boolean expected) throws Exception {
        File log = write(directory, DEVICE, "J-LLDP-Link_OPTIC", text);
        boolean result = complete(log, "J-LLDP-Link_OPTIC", "set cli screen-length 0", "quit");
        assertValue(label, expected, result);
        if (expected) {
            for (Class<?> owner : new Class<?>[]{BotGetLog_TrueCorp.class, Telnet_Multi.class}) {
                Method m = owner.getDeclaredMethod("isValidLogHead", File.class, String.class, String.class);
                m.setAccessible(true);
                assertValue(label + " " + owner.getSimpleName(), true, (Boolean) m.invoke(null, log, DEVICE, "J-LLDP-Link_OPTIC"));
            }
        }
    }

    private static void nonJuniper(Path directory, String set, String text, String device, String first, String last) throws Exception {
        assertValue(set, true, complete(write(directory, device, set, text), set, first, last));
    }

    private static File write(Path directory, String device, String set, String text) throws Exception {
        Path file = directory.resolve("[1]192.0.2.11_" + device + "_" + set + "_2026-09-22.txt");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file.toFile();
    }

    private static boolean complete(File file, String set, String first, String last) throws Exception {
        Method m = BotGetLog_TrueCorp.class.getDeclaredMethod("isLogComplete", File.class, String.class, String.class, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, file, first, last, set);
    }

    private static void assertValue(String label, boolean expected, boolean actual) {
        checks++;
        if (expected != actual) throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
    }
}
