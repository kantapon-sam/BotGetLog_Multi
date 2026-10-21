package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** HRP role prefixes must not alter node identity or weaken completion checks. */
public final class HuaweiHrpPromptRegression {
    private static int checks;
    private static final String NODE = "CPE-HUAWEI-1";
    private static final String SET = "HW-LLDP-Link_OPTIC";
    private static final String[] COMMANDS = {SET, "screen-length 0 temporary",
        "display lldp neighbor", "display version", "quit"};

    private static Object call(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(null, args);
    }
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Check " + checks + ": " + expected + " != " + actual);
    }
    private static boolean accepts(String text, String node) throws Exception {
        return (Boolean) call(Telnet_Multi.class, "matchesCollectionPrompt",
                new Class<?>[]{StringBuilder.class, String[].class, String[].class, String.class, boolean.class},
                new StringBuilder(text.toLowerCase(Locale.ROOT)), new String[]{"<" + node + ">"},
                new String[]{"<" + node.toLowerCase(Locale.ROOT) + ">"}, node, false);
    }
    private static String transcript(String prefix) {
        String p = prefix + "<" + NODE + ">";
        return p + COMMANDS[1] + "\n" + p + COMMANDS[2] + "\nNeighbor data\n"
                + p + COMMANDS[3] + "\nHuawei YunShan OS\n" + p + COMMANDS[4] + "\n";
    }
    private static void logCheck(Path file, String text, boolean expected) throws Exception {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        equal(expected, call(Telnet_Multi.class, "hasCompleteCollectionTranscript",
                new Class<?>[]{File.class, String.class, String[].class}, file.toFile(), SET, COMMANDS));
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("huawei-hrp-");
        try {
            Path file = dir.resolve("[1]192.0.2.1_" + NODE + "_" + SET + "_2026-10-21.txt");
            for (String prefix : new String[]{"", "HRP_M", "HRP_S", "hrp_m", "hrp_s"}) {
                String p = prefix + "<" + NODE + ">";
                equal(NODE, Telnet_Multi.extractNodeNameFromPromptToken(p));
                equal(true, accepts("display version\r\nHuawei YunShan OS\r\n" + p, NODE));
                equal(false, accepts(p, "OTHER-NODE"));
                equal(false, accepts(p + "display version", NODE));
                equal(false, accepts("Description: " + p, NODE));
                equal("HW", Telnet_Multi.detectVendorFromPrompt(p, "ZTE", false));
                equal(p, call(Telnet_Multi.class, "extractPromptCandidateFromLine",
                        new Class<?>[]{String.class, String.class, String.class}, p + "display version", "HW", NODE));
                logCheck(file, transcript(prefix), true);
                for (Class<?> type : new Class<?>[]{Telnet_Multi.class, BotGetLog_TrueCorp.class}) {
                    equal(true, call(type, "isValidLogHead", new Class<?>[]{File.class, String.class, String.class}, file.toFile(), NODE, SET));
                    equal(false, call(type, "isValidLogHead", new Class<?>[]{File.class, String.class, String.class}, file.toFile(), "OTHER-NODE", SET));
                }
                logCheck(file, transcript(prefix).replace("Huawei YunShan OS\n", ""), false);
                logCheck(file, transcript(prefix).replace(p + "display version", prefix + "<OTHER-NODE>display version"), false);
                logCheck(file, transcript(prefix).replace(p + "quit\n", ""), false);
                logCheck(file, transcript(prefix).replace(p + "display lldp neighbor\n", ""), false);
                logCheck(file, transcript(prefix).replace("Neighbor data", "Description:HRP_M<OTHER-NODE>"), true);
                equal(true, call(BotGetLog_TrueCorp.class, "matchesPromptPlusCommandLine",
                        new Class<?>[]{String.class, String.class}, p + "quit", "quit"));
            }
            // A role change in the same authenticated session still identifies the same node.
            Class<?> live = Class.forName("com.java.botgetlog.truecorp.Telnet_Multi$CredentialProbe");
            equal(true, call(live, "matchesEstablishedPrompt", new Class<?>[]{String.class, String.class},
                    "HRP_S<" + NODE + ">", "HRP_M<" + NODE + ">"));
            equal(false, call(live, "matchesEstablishedPrompt", new Class<?>[]{String.class, String.class},
                    "HRP_S<OTHER-NODE>", "HRP_M<" + NODE + ">"));
            logCheck(file, transcript("HRP_M").replace("HRP_M<" + NODE + ">display version", "HRP_S<" + NODE + ">display version"), true);
            for (String text : new String[]{"HRP_X<" + NODE + ">", "XHRP_M<" + NODE + ">",
                    "HRP_M <" + NODE + ">", "HRP_M<" + NODE, "HRP_M", "HRP_M" + NODE + "#"}) {
                equal(text, Telnet_Multi.stripHuaweiHrpPrefix(text));
                equal(false, accepts(text, NODE));
            }
            equal("HRP-M-NODE", Telnet_Multi.extractNodeNameFromPromptToken("<HRP_M_NODE>"));
            equal("CPE-NOKIA", Telnet_Multi.extractNodeNameFromPromptToken("*B:CPE-NOKIA#"));
            equal("CPE-ZTE", Telnet_Multi.extractNodeNameFromPromptToken("CPE-ZTE#"));
            equal("JUNIPER_re0", Telnet_Multi.extractNodeNameFromPromptToken("user@JUNIPER_re0>"));
            equal("HW", Telnet_Multi.detectVendorFromPrompt("HRP_S[~" + NODE + "]", "ZTE", false));
            equal("<" + NODE + ">", Telnet_Multi.stripHuaweiHrpPrefix("HRP_M<" + NODE + ">"));
            for (String name : new String[]{"CMDSET_FIRST_COMMAND_CACHE", "CMDSET_LAST_COMMAND_CACHE", "CMDSET_COMMAND_CACHE"}) {
                Field f = BotGetLog_TrueCorp.class.getDeclaredField(name); f.setAccessible(true);
                Map map = (Map) f.get(null);
                map.put(SET.toLowerCase(Locale.ROOT), name.equals("CMDSET_FIRST_COMMAND_CACHE") ? COMMANDS[1]
                        : name.equals("CMDSET_LAST_COMMAND_CACHE") ? "quit" : Arrays.asList(COMMANDS));
            }
            Files.write(file, transcript("HRP_M").getBytes(StandardCharsets.UTF_8));
            equal(true, BotGetLog_TrueCorp.isLogCompleteForCmdSet(file.toFile(), SET));
            equal(true, Telnet_Multi.isCompleteLogAfterExitClose(file.toFile(), SET, "quit", true, "stream ended before prompt"));
            Files.write(file, transcript("HRP_M").replace("Huawei YunShan OS", "Error: Unrecognized command found at '^' position.").getBytes(StandardCharsets.UTF_8));
            equal(false, BotGetLog_TrueCorp.isLogCompleteForCmdSet(file.toFile(), SET));
            // Saved live responses remain private; replay optional files without embedding them.
            for (String arg : args) {
                String raw = new String(Files.readAllBytes(Paths.get(arg)), StandardCharsets.UTF_8);
                equal(true, accepts(raw.substring(Math.max(0, raw.length() - 4096)), System.getProperty("botgetlog.test.expectedNode", NODE)));
            }
            System.out.println("PASS HuaweiHrpPromptRegression checks=" + checks);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException e) { throw new UncheckedIOException(e); } });
            }
        }
    }
}
