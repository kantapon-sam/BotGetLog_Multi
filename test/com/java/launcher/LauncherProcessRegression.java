package com.java.launcher;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Runs the actual launchProgram method with a hidden frame and owned child JVMs. */
public final class LauncherProcessRegression {
    private static final int STREAM_BYTES = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        boolean expectBlocked = args.length == 1 && "--expect-blocked".equals(args[0]);
        Path root = Files.createTempDirectory("launcher-output-regression-");
        runCase(root, "dispose", expectBlocked);
        if (!expectBlocked) runCase(root, "exit", false);
        System.out.println((expectBlocked ? "REPRODUCED" : "PASS")
                + " LauncherProcessRegression: actual launchProgram; 1 MiB stdout and stderr; "
                + "arguments, working directory and launcher gate preserved; "
                + (expectBlocked ? "undrained output blocked the child" : "complete logs after frame disposal and parent exit")
                + "; evidence=" + root);
    }

    private static void runCase(Path root, String mode, boolean expectBlocked) throws Exception {
        Path run = Files.createDirectory(root.resolve(mode + " child workspace"));
        Path childJar = run.resolve("NoiseProbe.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, LauncherOutputChild.class.getName());
        String resource = LauncherOutputChild.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(childJar), manifest);
             InputStream bytes = LauncherOutputChild.class.getClassLoader().getResourceAsStream(resource)) {
            require(bytes != null, "Missing child test class");
            jar.putNextEntry(new JarEntry(resource));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = bytes.read(buffer)) != -1) jar.write(buffer, 0, count);
            jar.closeEntry();
        }
        List<String> classpath = new ArrayList<String>();
        for (String item : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            classpath.add(Paths.get(item).toAbsolutePath().toString());
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String javaCommand = Paths.get(System.getProperty("java.home"), "bin", executable).toString();
        Process parent = new ProcessBuilder(javaCommand, "-cp", String.join(File.pathSeparator, classpath),
                LauncherOutputParent.class.getName(), run.toString(), mode)
                .redirectErrorStream(true).redirectOutput(run.resolve("parent.log").toFile()).start();
        try {
            require(await(run.resolve("child-ready.txt"), 12_000), "Child did not initialize: " + run);
            require(await(run.resolve("parent-disposed.txt"), 3_000), "Frame was not disposed: " + run);
            Properties child = new Properties();
            try (InputStream input = Files.newInputStream(run.resolve("child.properties"))) { child.load(input); }
            require(run.equals(Paths.get(child.getProperty("cwd"))), "Working directory changed");
            require("true".equals(child.getProperty("launcherProperty")), "Launcher gate property changed");
            String[] expected = {"--probe-dir", run.toString(), "two words", "trimmed-value"};
            require(Integer.toString(expected.length).equals(child.getProperty("argumentCount")), "Argument count changed");
            for (int i = 0; i < expected.length; i++) {
                require(expected[i].equals(child.getProperty("argument." + i)), "Argument " + i + " changed");
            }
            boolean complete = await(run.resolve("complete.json"), expectBlocked ? 6_000 : 10_000);
            if (expectBlocked) {
                require(!complete && parent.isAlive(), "Expected the disposed live launcher to block child output");
                require(!Files.exists(run.resolve("output-error.txt")), "Failure was not a blocked pipe");
                return;
            }
            require(complete, "High-volume child did not complete: " + run);
            require(!Files.exists(run.resolve("output-error.txt")), "Child reported broken stdout/stderr");
            if ("dispose".equals(mode)) require(parent.isAlive(), "Disposed parent did not stay alive for the test");
            else require(parent.waitFor(3, TimeUnit.SECONDS) && parent.exitValue() == 0, "Parent did not exit");
            List<Path> logs = new ArrayList<Path>();
            try (Stream<Path> files = Files.list(run.resolve("_output/Launcher_Log"))) {
                files.filter(Files::isRegularFile).forEach(logs::add);
            }
            require(logs.size() == 1, "Expected one unique child log");
            byte[] bytes = Files.readAllBytes(logs.get(0));
            int stdout = 0;
            int stderr = 0;
            for (byte value : bytes) {
                if (value == 'O') stdout++;
                if (value == 'E') stderr++;
            }
            require(bytes.length == 2 * STREAM_BYTES && stdout == STREAM_BYTES && stderr == STREAM_BYTES,
                    "Merged log did not preserve every stdout/stderr byte");
        } finally {
            // The child watchdog observes only this unique fixture directory
            // and terminates itself. Never enumerate or terminate user PIDs.
            Files.write(run.resolve("stop-child.txt"), new byte[0]);
            Files.write(run.resolve("release-parent.txt"), new byte[0]);
            if (!parent.waitFor(3, TimeUnit.SECONDS)) {
                parent.destroyForcibly();
                require(parent.waitFor(3, TimeUnit.SECONDS), "Owned parent did not terminate");
            }
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (Files.exists(run.resolve("child-pid.txt"))
                    && !Files.exists(run.resolve("child-stopped.txt"))
                    && !Files.exists(run.resolve("child-finished.txt"))
                    && System.nanoTime() < deadline) Thread.sleep(50);
        }
    }

    private static boolean await(Path marker, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(50);
        return Files.exists(marker);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
