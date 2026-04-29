package com.java.launcher;

import com.java.shared.AppMetadata;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;

public final class AutoLoginLauncher {

    private AutoLoginLauncher() {
    }

    public static void launch(Component parent, String siteId, String siteLabel, String targetUrl) {
        String javaLauncher = findJavaLauncher();

        List<String> command = new ArrayList<String>();
        command.add(javaLauncher);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(resolveHelperClasspath());
        command.add("com.java.launcher.AutoLoginHelperMain");
        command.add(siteId);
        command.add(siteLabel);
        command.add(targetUrl);

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            File workDir = AppMetadata.getAppDirectory();
            if (workDir != null && workDir.isDirectory()) {
                builder.directory(workDir);
            }
            builder.start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Unable to start Auto Login helper.\n" + ex.getMessage(),
                    "Auto Login",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private static String resolveHelperClasspath() {
        File runningLocation = AppMetadata.getRunningLocation();
        if (runningLocation.isFile()) {
            return runningLocation.getAbsolutePath();
        }
        return new File(AppMetadata.getAppDirectory(), "build\\classes").getAbsolutePath();
    }

    private static String findJavaLauncher() {
        String launcher = resolveJavaLauncher(System.getProperty("java.home", ""));
        if (launcher != null) {
            return launcher;
        }
        launcher = resolveJavaLauncher(System.getenv("JAVA_HOME"));
        if (launcher != null) {
            return launcher;
        }
        launcher = resolveJavaLauncher(System.getenv("JAVA8_HOME"));
        if (launcher != null) {
            return launcher;
        }
        return "java";
    }

    private static String resolveJavaLauncher(String javaHome) {
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return null;
        }
        File home = new File(javaHome);
        File javaw = new File(home, "bin\\javaw.exe");
        if (javaw.isFile()) {
            return javaw.getAbsolutePath();
        }
        File java = new File(home, "bin\\java.exe");
        if (java.isFile()) {
            return java.getAbsolutePath();
        }
        File jreJavaw = new File(home, "jre\\bin\\javaw.exe");
        if (jreJavaw.isFile()) {
            return jreJavaw.getAbsolutePath();
        }
        File jreJava = new File(home, "jre\\bin\\java.exe");
        if (jreJava.isFile()) {
            return jreJava.getAbsolutePath();
        }
        return null;
    }
}
