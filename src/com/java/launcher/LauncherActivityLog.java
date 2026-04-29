package com.java.launcher;

import com.java.shared.AppMetadata;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class LauncherActivityLog {

    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private LauncherActivityLog() {
    }

    public static void info(String scope, String message) {
        write("INFO", scope, message, null);
    }

    public static void warn(String scope, String message) {
        write("WARN", scope, message, null);
    }

    public static void error(String scope, String message) {
        write("ERROR", scope, message, null);
    }

    public static void error(String scope, String message, Throwable error) {
        write("ERROR", scope, message, error);
    }

    private static synchronized void write(String level, String scope, String message, Throwable error) {
        File logFile = AppMetadata.getLauncherAutoLoginLogFile();
        File parent = logFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        StringBuilder line = new StringBuilder();
        line.append('[').append(TIMESTAMP_FORMAT.format(new Date())).append("] ");
        line.append('[').append(level == null ? "INFO" : level).append("] ");
        line.append('[').append(scope == null || scope.trim().isEmpty() ? "Launcher" : scope.trim()).append("] ");
        line.append(message == null ? "" : message.trim());
        if (error != null) {
            line.append(" | ").append(error.getClass().getSimpleName());
            if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
                line.append(": ").append(error.getMessage().trim());
            }
        }
        line.append(System.lineSeparator());

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
            writer.write(line.toString());
        } catch (IOException ignored) {
        }
    }
}
