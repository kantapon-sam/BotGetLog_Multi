package com.java.launcher;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** A child tool with independently saturated stdout/stderr and no network I/O. */
public final class LauncherOutputChild {
    private static final int BYTES_PER_STREAM = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        Path probeDir = Paths.get(args[1]).toAbsolutePath();
        Files.write(probeDir.resolve("child-pid.txt"),
                ManagementFactory.getRuntimeMXBean().getName().split("@")[0].getBytes(StandardCharsets.UTF_8));
        StringBuilder metadata = new StringBuilder("{\"cwd\":").append(json(Paths.get("").toAbsolutePath().normalize().toString()))
                .append(",\"launcherProperty\":").append(json(System.getProperty("bottool.invokedByLauncher", "")))
                .append(",\"args\":[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) metadata.append(',');
            metadata.append(json(args[i]));
        }
        Files.write(probeDir.resolve("child-metadata.json"), metadata.append("]}").toString().getBytes(StandardCharsets.UTF_8));
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("cwd", Paths.get("").toAbsolutePath().normalize().toString());
        properties.setProperty("launcherProperty", System.getProperty("bottool.invokedByLauncher", ""));
        properties.setProperty("argumentCount", Integer.toString(args.length));
        for (int i = 0; i < args.length; i++) properties.setProperty("argument." + i, args[i]);
        try (java.io.OutputStream output = Files.newOutputStream(probeDir.resolve("child.properties"))) {
            properties.store(output, "Synthetic launcher regression metadata");
        }
        Files.write(probeDir.resolve("child-ready.txt"), new byte[0]);
        Thread watchdog = new Thread(() -> {
            long stopAt = System.nanoTime() + 30_000_000_000L;
            while (System.nanoTime() < stopAt && !Files.exists(probeDir.resolve("stop-child.txt"))) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
            try {
                Files.write(probeDir.resolve("child-stopped.txt"), new byte[0]);
            } catch (java.io.IOException ignored) { }
            Runtime.getRuntime().halt(98);
        }, "probe-owned-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!Files.exists(probeDir.resolve("parent-disposed.txt"))) {
            if (System.nanoTime() > deadline) throw new AssertionError("Parent did not dispose");
            Thread.sleep(10);
        }
        Thread.sleep(300); // In exit mode, write only after the launcher has ended.
        Thread stdout = writer(System.out, (byte) 'O', "stdout");
        Thread stderr = writer(System.err, (byte) 'E', "stderr");
        stdout.start();
        stderr.start();
        stdout.join();
        stderr.join();
        if (System.out.checkError() || System.err.checkError()) {
            Files.write(probeDir.resolve("output-error.txt"), "broken output stream".getBytes(StandardCharsets.UTF_8));
            System.exit(3);
        }
        Files.write(probeDir.resolve("complete.json"),
                ("{\"stdoutBytes\":" + BYTES_PER_STREAM + ",\"stderrBytes\":" + BYTES_PER_STREAM + "}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(probeDir.resolve("child-finished.txt"), new byte[0]);
    }

    private static Thread writer(PrintStream stream, byte value, String name) {
        return new Thread(() -> {
            byte[] block = new byte[4096];
            Arrays.fill(block, value);
            for (int count = 0; count < BYTES_PER_STREAM; count += block.length) {
                stream.write(block, 0, block.length);
            }
            stream.flush();
        }, "probe-" + name);
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
