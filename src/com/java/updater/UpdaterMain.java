package com.java.updater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class UpdaterMain {

    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String UPDATE_LOG_NAME = "update.log";
    private static final String UPDATE_LOG_RELATIVE_DIR = "_output\\System_Log";
    private static final Set<String> PRESERVED_TOP_LEVEL = new HashSet<String>(
            Arrays.asList("UserInterface_Input.xlsx", "_output"));
    private static final Set<String> APP_MANAGED_TOP_LEVEL = new HashSet<String>(
            Arrays.asList("defaults", "lib", "updater"));
    private static final Set<String> APP_MANAGED_FILES = new HashSet<String>(
            Arrays.asList("BotGetLog_Multi.jar", "Bot Tool Launcher.jar", "Link_Optical.jar", "ARP.jar", "PTP.jar", "README.TXT"));

    public static void main(String[] args) {
        File targetDir = null;
        try {
            Map<String, String> options = parseArgs(args);
            File zipFile = requireFile(options, "--zip");
            targetDir = requireDirectoryPath(options, "--target");
            File launchJar = requireFilePath(options, "--launch");
            String javaCommand = requiredValue(options, "--java");
            String cleanupPath = optionalValue(options, "--cleanup");

            if (cleanupPath != null && !cleanupPath.isEmpty()) {
                new File(cleanupPath).deleteOnExit();
            }

            log(targetDir, "Updater started for " + launchJar.getAbsolutePath());
            waitForFileToUnlock(launchJar, 30);
            Path stagingDir = Files.createTempDirectory("botgetlog-update-staging-");
            unzip(zipFile.toPath(), stagingDir);
            syncTree(stagingDir, targetDir.toPath());
            Files.deleteIfExists(zipFile.toPath());
            deleteDirectory(stagingDir);
            relaunch(javaCommand, launchJar, targetDir);
            log(targetDir, "Update installed successfully. Launching the new version now.");
        } catch (Exception e) {
            log(targetDir, "Update failed: " + e.getMessage());
            UpdatePromptDialog.showError("Update Error",
                    "Update failed.\n" + e.getMessage()
                    + "\n\nSee _output\\System_Log\\update.log for details.");
            System.exit(1);
        }
    }

    private static void relaunch(String javaCommand, File launchJar, File targetDir) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add(javaCommand);
        command.add("-jar");
        command.add(launchJar.getAbsolutePath());
        new ProcessBuilder(command)
                .directory(targetDir)
                .start();
    }

    private static void waitForFileToUnlock(File file, int attempts) throws InterruptedException, IOException {
        IOException lastError = null;
        for (int i = 0; i < attempts; i++) {
            try {
                Path probe = file.toPath().resolveSibling(file.getName() + ".lockcheck");
                Files.copy(file.toPath(), probe, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(probe);
                return;
            } catch (IOException e) {
                lastError = e;
                Thread.sleep(1000L);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        try (InputStream input = Files.newInputStream(zipFile);
                ZipInputStream zipInput = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    throw new IOException("Blocked zip entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zipInput, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zipInput.closeEntry();
            }
        }
    }

    private static void syncTree(Path sourceRoot, Path targetRoot) throws IOException {
        Files.walk(targetRoot)
                .sorted(Comparator.reverseOrder())
                .forEach(target -> {
                    try {
                        Path relative = targetRoot.relativize(target);
                        if (relative.toString().isEmpty()) {
                            return;
                        }
                        if (isPreserved(relative)) {
                            return;
                        }
                        if (!isAppManaged(relative)) {
                            return;
                        }
                        Path source = sourceRoot.resolve(relative);
                        if (!Files.exists(source)) {
                            Files.deleteIfExists(target);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        Files.walk(sourceRoot).forEach(source -> {
            try {
                Path relative = sourceRoot.relativize(source);
                if (relative.toString().isEmpty()) {
                    return;
                }
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void deleteDirectory(Path dir) throws IOException {
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new HashMap<String, String>();
        if (args == null) {
            return values;
        }
        for (int i = 0; i < args.length - 1; i += 2) {
            values.put(args[i], args[i + 1]);
        }
        return values;
    }

    private static String requiredValue(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing argument " + key);
        }
        return value.trim();
    }

    private static File requireFile(Map<String, String> options, String key) {
        File file = new File(requiredValue(options, key));
        if (!file.isFile()) {
            throw new IllegalArgumentException("File not found for " + key + ": " + file.getAbsolutePath());
        }
        return file;
    }

    private static File requireFilePath(Map<String, String> options, String key) {
        return new File(requiredValue(options, key));
    }

    private static File requireDirectoryPath(Map<String, String> options, String key) {
        File dir = new File(requiredValue(options, key));
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Directory not found for " + key + ": " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static String optionalValue(Map<String, String> options, String key) {
        String value = options.get(key);
        return value == null ? "" : value.trim();
    }

    private static boolean isPreserved(Path relative) {
        Path first = relative.getNameCount() > 0 ? relative.getName(0) : relative;
        return first != null && PRESERVED_TOP_LEVEL.contains(first.toString());
    }

    private static boolean isAppManaged(Path relative) {
        Path first = relative.getNameCount() > 0 ? relative.getName(0) : relative;
        if (first == null) {
            return false;
        }

        String name = first.toString();
        return APP_MANAGED_TOP_LEVEL.contains(name) || APP_MANAGED_FILES.contains(name);
    }

    private static void log(File targetDir, String message) {
        if (targetDir == null) {
            return;
        }
        String timestamp = LocalDateTime.now().format(LOG_TIME_FORMAT);
        File logDir = new File(targetDir, UPDATE_LOG_RELATIVE_DIR);
        logDir.mkdirs();
        File logFile = new File(logDir, UPDATE_LOG_NAME);
        try {
            Files.write(logFile.toPath(),
                    Arrays.asList("[" + timestamp + "] " + message),
                    java.nio.charset.StandardCharsets.UTF_8,
                    Files.exists(logFile.toPath())
                            ? new java.nio.file.OpenOption[]{
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND
                            }
                            : new java.nio.file.OpenOption[]{
                                java.nio.file.StandardOpenOption.CREATE
                            });
        } catch (IOException ignored) {
        }
    }
}

