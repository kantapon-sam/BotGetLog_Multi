package com.java.botgetlog.truecorp;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline regression for description text mistaken for a device prompt. */
public final class TranscriptDescriptionPromptRegression {
    private static int checks;
    private static Method validate;
    private static final String[] HW = {"HW-LLDP-Link_OPTIC", "screen-length 0 temporary",
        "display cpu-usage", "display memory-usage", "display lldp neighbor",
        "display version", "display interface", "quit"};
    private static final String[] NOKIA = {"N-LLDP-Link_OPTIC", "environment no more",
        "show system cpu", "show system memory-pools", "show system lldp neighbor",
        "show chassis", "show version", "show port", "show lag description", "logout"};

    public static void main(String[] args) throws Exception {
        validate = Telnet_Multi.class.getDeclaredMethod("hasCompleteCollectionTranscript",
                File.class, String.class, String[].class);
        validate.setAccessible(true);
        Path dir = Files.createTempDirectory("transcript-description-");
        try {
            for (String[] commands : new String[][]{HW, NOKIA}) {
                boolean nokia = commands == NOKIA;
                String node = "CPE-TEST_1";
                String prompt = nokia ? "*B:" + node + "#" : "<" + node + ">";
                File file = dir.resolve("[1]10.0.0.1_CPE-TEST-1_" + commands[0] + "_2026-10-01.txt").toFile();
                StringBuilder text = new StringBuilder();
                for (int i = 1; i < commands.length; i++) {
                    text.append(prompt).append(commands[i]).append('\n');
                    if (i < commands.length - 1) text.append("Response ").append(i).append('\n');
                    if (commands[i].equals("show port")) text.append("1/1/c1/1 Up\n");
                    if (i == commands.length - 2) {
                        text.append("Description:To_SKA1038_CX600-X16#2_Eth-Trunk1.9\n")
                            .append("177_L2LINK#1\nTie#61,62;01/04/04-CORE-ODF#11,12]\n")
                            .append("RN_Nokia_7950_#4_100GE_1/1/c12_L3_10.0.0.1)\n")
                            .append("DescriptionEnding#\n");
                        if (nokia) text.append(prompt).append("show port 1/1/c1/1 ethernet lldp remote-info\nNo remote peers found\n")
                            .append(prompt).append("show port 1/1/c1/1\nPort data\n");
                    }
                }
                String complete = text.toString();
                verify(file, commands, complete, true);
                verify(file, commands, complete.replace("#1", "#display interface"), true);
                verify(file, commands, complete.replace(prompt + commands[2],
                        prompt.replace(node, "OTHER-NODE") + commands[2]), false);
                verify(file, commands, complete.replace(prompt + commands[2] + "\nResponse 2\n", ""), false);
                verify(file, commands, complete.replace("Response 2\n", ""), false);
                verify(file, commands, complete.replace(prompt + commands[commands.length - 1] + "\n", ""), false);
                // Output descriptions can be the entire response and must not disappear.
                verify(file, commands, complete.replace("Response 2", "Description:To_ME60E#3_Trunk"), true);
                if (nokia) {
                    verify(file, commands, complete.replace(prompt + "show port 1/1/c1/1\nPort data\n", ""), false);
                    verify(file, commands, complete.replace(prompt + "show port 1/1/c1/1 ethernet lldp remote-info\nNo remote peers found\n", ""), false);
                    verify(file, commands, complete.replace("*B:", "a:"), true);
                }
            }
            // Saved production logs stay local and are never added to the repository.
            for (String arg : args) {
                File file = new File(arg);
                String[] commands = file.getName().contains("_N-LLDP") ? NOKIA : HW;
                String original = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                verify(dir.resolve(file.getName()).toFile(), commands, original, true);
                String middle = commands[2];
                int at = original.indexOf(middle);
                if (at < 0) throw new AssertionError("Missing fixture command: " + file);
                // Remove the command echo only, leaving its data in the prior response.
                verify(dir.resolve(file.getName()).toFile(), commands,
                        original.substring(0, at) + original.substring(at + middle.length()), false);
            }
            System.out.println("PASS TranscriptDescriptionPromptRegression " + checks + " checks");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
        }
    }

    private static void verify(File file, String[] commands, String text, boolean expected) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        boolean actual = (Boolean) validate.invoke(null, file, commands[0], commands);
        checks++;
        if (actual != expected) throw new AssertionError("Check " + checks + ": " + file + " expected " + expected + " got " + actual);
    }
}
