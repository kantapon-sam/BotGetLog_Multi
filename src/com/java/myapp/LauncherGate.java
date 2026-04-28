package com.java.myapp;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

public final class LauncherGate {

    public static final String INVOKED_BY_LAUNCHER_PROPERTY = "bottool.invokedByLauncher";
    public static final String REQUESTED_TOOL_PROPERTY = "bottool.requestedTool";
    private static final String LAUNCHER_JAR_NAME = "Bot Tool Launcher.jar";

    private LauncherGate() {
    }

    public static boolean redirectToLauncherIfNeeded(String toolName) {
        if (isInvokedByLauncher() || !isRunningFromJar()) {
            return false;
        }

        setLookAndFeel();

        File appDir = getAppDirectory();
        File launcherJar = new File(appDir, LAUNCHER_JAR_NAME);
        if (!launcherJar.isFile()) {
            JOptionPane.showMessageDialog(
                    null,
                    "Please open this tool from " + LAUNCHER_JAR_NAME + "\nLauncher not found at:\n" + launcherJar.getAbsolutePath(),
                    "Open via Launcher",
                    JOptionPane.WARNING_MESSAGE
            );
            return true;
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    getJavaLauncher(),
                    "-D" + REQUESTED_TOOL_PROPERTY + "=" + toolName,
                    "-jar",
                    launcherJar.getAbsolutePath()
            );
            processBuilder.directory(appDir);
            processBuilder.start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    null,
                    "Please open this tool from " + LAUNCHER_JAR_NAME + "\nUnable to start launcher:\n" + ex.getMessage(),
                    "Open via Launcher",
                    JOptionPane.ERROR_MESSAGE
            );
        }
        return true;
    }

    public static boolean isInvokedByLauncher() {
        return "true".equalsIgnoreCase(System.getProperty(INVOKED_BY_LAUNCHER_PROPERTY, ""));
    }

    private static boolean isRunningFromJar() {
        File location = getRunningLocation();
        return location.isFile() && location.getName().toLowerCase().endsWith(".jar");
    }

    private static File getRunningLocation() {
        try {
            CodeSource codeSource = LauncherGate.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return new File(".").getCanonicalFile();
            }
            URL location = codeSource.getLocation();
            return new File(location.toURI()).getCanonicalFile();
        } catch (IOException | URISyntaxException ex) {
            return new File(".").getAbsoluteFile();
        }
    }

    private static File getAppDirectory() {
        File location = getRunningLocation();
        if (location.isFile()) {
            File parent = location.getParentFile();
            return parent != null ? parent : new File(".").getAbsoluteFile();
        }
        return location;
    }

    private static String getJavaLauncher() {
        String javaHome = System.getProperty("java.home", "");
        File javaw = new File(javaHome, "bin\\javaw.exe");
        if (javaw.isFile()) {
            return javaw.getAbsolutePath();
        }

        File java = new File(javaHome, "bin\\java.exe");
        if (java.isFile()) {
            return java.getAbsolutePath();
        }

        return "java";
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception ignored) {
        }
    }
}
