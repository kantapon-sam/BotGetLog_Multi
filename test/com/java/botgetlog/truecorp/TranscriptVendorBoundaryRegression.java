package com.java.botgetlog.truecorp;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;

public final class TranscriptVendorBoundaryRegression {
    private static int checks;
    private static Method validate;
    public static void main(String[] args) throws Exception {
        validate = Telnet_Multi.class.getDeclaredMethod("hasCompleteCollectionTranscript", File.class, String.class, String[].class);
        validate.setAccessible(true);
        Path dir = Files.createTempDirectory("vendor-boundary-");
        try {
            String[] zte = {"ZTE-LLDP-Link_OPTIC", "terminal length 0", "show processor", "show lldp neighbor brief", "show lacp internal", "quit"};
            String complete = "CN-TEST#terminal length 0\nCN-TEST#show processor\nCPU data\nCN-TEST#show lldp neighbor brief\nCN-TEST#show lacp internal\nCN-TEST#quit\nConnection closed by foreign host.\n";
            verify(dir, zte, complete, true);
            verify(dir, zte, complete.replace("CPU data\n", ""), false);
            verify(dir, zte, complete.replace("CN-TEST#show lacp internal\n", ""), false);
            verify(dir, zte, complete.replace("CN-TEST#quit", "OTHER#quit"), false);
            verify(dir, zte, complete.replace("CN-TEST#quit\nConnection closed by foreign host.\n", ""), false);
            String[] juniper = {"J-LLDP-Link_OPTIC", "set cli screen-length 0", "show interfaces diagnostics optics *", "quit"};
            String j = "{master}\nuser@clls@CN-TEST> set cli screen-length 0\nScreen length set to 0\nuser@clls@CN-TEST> show interfaces diagnostics optics *\nuser@clls@CN-TEST> ...tics optics *\nPhysical interface: et-0/0/0\nLaser rx power: -3 dBm\nuser@clls@CN-TEST> quit Connection closed by foreign host.\n";
            verify(dir, juniper, j, true);
            verify(dir, juniper, j.replace("quit Connection", "quitConnection"), true);
            verify(dir, juniper, j.replace("user@clls@", ""), true);
            verify(dir, juniper, j.replace("Physical interface: et-0/0/0\nLaser rx power: -3 dBm\n", ""), false);
            verify(dir, juniper, j.replace("user@clls@CN-TEST> ...tics optics *", "user@OTHER> ...tics optics *"), false);
            verify(dir, juniper, j.replace("...tics optics *", "...different command"), false);
            verify(dir, juniper, j.replace("quit Connection closed by foreign host.", "quit BAD TEXT"), false);
            verify(dir, juniper, j.replace("user@clls@CN-TEST> show interfaces diagnostics optics *\n", ""), false);
            System.out.println("PASS TranscriptVendorBoundaryRegression checks=" + checks);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch(IOException e) { throw new UncheckedIOException(e); } });
            }
        }
    }
    private static void verify(Path dir, String[] cmd, String text, boolean expected) throws Exception {
        File file = dir.resolve("[1]10.0.0.1_CN-TEST_" + cmd[0] + "_2026-10-03.txt").toFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); checks++;
        if ((Boolean) validate.invoke(null, file, cmd[0], cmd) != expected) throw new AssertionError("case " + checks);
    }
}
