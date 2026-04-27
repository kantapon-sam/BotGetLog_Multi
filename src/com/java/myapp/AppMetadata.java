package com.java.myapp;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;

public final class AppMetadata {

    private static final String APP_NAME = "BotGetLog_Multi";
    private static final String FALLBACK_VERSION = "0.0.0";

    private AppMetadata() {
    }

    public static String getAppName() {
        return APP_NAME;
    }

    public static String getCurrentVersion() {
        Package appPackage = BotGetLog_Multi.class.getPackage();
        if (appPackage != null) {
            String implementationVersion = appPackage.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.trim().isEmpty()) {
                return implementationVersion.trim();
            }
        }
        return FALLBACK_VERSION;
    }

    public static File getRunningLocation() {
        try {
            CodeSource codeSource = BotGetLog_Multi.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return new File(".").getCanonicalFile();
            }
            URL location = codeSource.getLocation();
            return new File(location.toURI()).getCanonicalFile();
        } catch (URISyntaxException | java.io.IOException e) {
            return new File(".").getAbsoluteFile();
        }
    }

    public static File getAppDirectory() {
        File location = getRunningLocation();
        if (location.isFile()) {
            File parent = location.getParentFile();
            return parent != null ? parent : new File(".").getAbsoluteFile();
        }
        return location;
    }

    public static String getJavaExecutable() {
        String javaHome = System.getProperty("java.home", "");
        File javaBin = new File(javaHome, "bin\\java.exe");
        if (javaBin.isFile()) {
            return javaBin.getAbsolutePath();
        }
        File javaFallback = new File(javaHome, "bin\\java");
        if (javaFallback.isFile()) {
            return javaFallback.getAbsolutePath();
        }
        return "java";
    }
}
