package com.java.myapp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;

public final class AutoUpdateManager {

    private static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/YOUR_REPOSITORY/main/update/version.json";
    private static final String MANIFEST_URL_PROPERTY = "botgetlog.update.manifestUrl";
    private static final Pattern JSON_TEXT_PATTERN =
            Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    private AutoUpdateManager() {
    }

    public static boolean checkForUpdatesAtStartup() {
        try {
            String manifestUrl = getManifestUrl();
            if (manifestUrl.contains("YOUR_GITHUB_USERNAME") || manifestUrl.contains("YOUR_REPOSITORY")) {
                System.out.println("[UPDATE] Manifest URL is still using the template placeholder. Update check skipped.");
                return false;
            }

            UpdateManifest manifest = fetchManifest(manifestUrl);
            if (manifest == null || !manifest.isValid()) {
                System.out.println("[UPDATE] No valid update manifest found.");
                return false;
            }

            String currentVersion = AppMetadata.getCurrentVersion();
            if (compareVersions(manifest.getVersion(), currentVersion) <= 0) {
                System.out.printf("[UPDATE] Already on the latest version (%s).%n", currentVersion);
                return false;
            }

            String message = buildPromptMessage(currentVersion, manifest);
            int choice = JOptionPane.showConfirmDialog(
                    null,
                    message,
                    "Update Available",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.INFORMATION_MESSAGE
            );

            if (choice != JOptionPane.YES_OPTION) {
                System.out.println("[UPDATE] User postponed the update.");
                return false;
            }

            File downloadedZip = downloadUpdatePackage(manifest);
            launchUpdater(downloadedZip);
            return true;
        } catch (Exception e) {
            System.out.println("[UPDATE] Update flow skipped: " + e.getMessage());
            return false;
        }
    }

    static UpdateManifest fetchManifest(String manifestUrl) throws IOException {
        HttpURLConnection connection = openConnection(manifestUrl);
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            String json = readFullyAsUtf8(input);
            UpdateManifest manifest = new UpdateManifest(
                    unescapeJson(extractJsonString(json, "version")),
                    unescapeJson(extractJsonString(json, "url")),
                    unescapeJson(extractJsonString(json, "sha256")),
                    unescapeJson(extractJsonString(json, "notes"))
            );
            return manifest.isValid() ? manifest : null;
        }
    }

    private static String getManifestUrl() {
        String configured = System.getProperty(MANIFEST_URL_PROPERTY, DEFAULT_MANIFEST_URL);
        return configured == null ? DEFAULT_MANIFEST_URL : configured.trim();
    }

    private static String buildPromptMessage(String currentVersion, UpdateManifest manifest) {
        StringBuilder message = new StringBuilder();
        message.append("Current version: ").append(currentVersion).append('\n');
        message.append("Latest version: ").append(manifest.getVersion()).append('\n');
        if (!manifest.getNotes().isEmpty()) {
            message.append('\n').append("Release notes:").append('\n').append(manifest.getNotes()).append('\n');
        }
        message.append('\n').append("Update now?");
        return message.toString();
    }

    private static File downloadUpdatePackage(UpdateManifest manifest) throws Exception {
        HttpURLConnection connection = openConnection(manifest.getDownloadUrl());
        File tempZip = File.createTempFile("botgetlog-update-", ".zip");
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
                FileOutputStream output = new FileOutputStream(tempZip)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        }

        if (!manifest.getSha256().isEmpty()) {
            String actualHash = sha256(tempZip);
            if (!manifest.getSha256().equalsIgnoreCase(actualHash)) {
                Files.deleteIfExists(tempZip.toPath());
                throw new IOException("SHA-256 mismatch. Expected " + manifest.getSha256() + " but got " + actualHash);
            }
        }

        return tempZip;
    }

    private static void launchUpdater(File downloadedZip) throws IOException {
        File appDir = AppMetadata.getAppDirectory();
        File updaterJar = new File(appDir, "updater\\BotGetLog_Updater.jar");
        if (!updaterJar.isFile()) {
            throw new IOException("Updater jar not found: " + updaterJar.getAbsolutePath());
        }
        File tempUpdaterJar = File.createTempFile("botgetlog-updater-", ".jar");
        Files.copy(updaterJar.toPath(), tempUpdaterJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tempUpdaterJar.deleteOnExit();

        File runningLocation = AppMetadata.getRunningLocation();
        List<String> command = new ArrayList<String>();
        command.add(AppMetadata.getJavaExecutable());
        command.add("-jar");
        command.add(tempUpdaterJar.getAbsolutePath());
        command.add("--zip");
        command.add(downloadedZip.getAbsolutePath());
        command.add("--target");
        command.add(appDir.getAbsolutePath());
        command.add("--launch");
        command.add(runningLocation.getAbsolutePath());
        command.add("--java");
        command.add(AppMetadata.getJavaExecutable());

        new ProcessBuilder(command)
                .directory(appDir)
                .start();

        System.out.println("[UPDATE] Updater launched. Closing current app for replacement.");
        System.exit(0);
    }

    private static HttpURLConnection openConnection(String urlValue) throws IOException {
        URL url = new URL(urlValue);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("User-Agent", AppMetadata.getAppName() + "-auto-updater");
        return connection;
    }

    private static String readFullyAsUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile(String.format(JSON_TEXT_PATTERN.pattern(), Pattern.quote(key)));
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String unescapeJson(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    static int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left);
        String[] rightParts = normalizeVersion(right);
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int leftValue = i < leftParts.length ? parsePart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parsePart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return leftValue - rightValue;
            }
        }
        return 0;
    }

    private static String[] normalizeVersion(String version) {
        String safe = version == null ? "" : version.trim();
        if (safe.isEmpty()) {
            return new String[]{"0"};
        }
        safe = safe.replaceFirst("^[vV]", "");
        return safe.split("\\.");
    }

    private static int parsePart(String value) {
        String digitsOnly = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digitsOnly.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digitsOnly);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}


