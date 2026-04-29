package com.java.shared;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public final class AppMetadata {

    private static final String APP_NAME = "BotGetLog_Multi";
    private static final String FALLBACK_VERSION = "0.0.0";
    private static final String DEFAULT_MAIN_CLASS = "com.java.botgetlog.BotGetLog_Multi";
    private static final String OUTPUT_DIR_NAME = "_output";
    private static final String LAUNCHER_DATA_DIR_NAME = "launcher-data";
    private static final String LAUNCHER_CREDENTIALS_FILE_NAME = "credentials.properties";
    private static final String LAUNCHER_CHROME_PROFILE_DIR_NAME = "chrome-profile";
    private static final String LAUNCHER_AUTO_LOGIN_LOG_FILE_NAME = "auto-login.log";
    private static final String BOT_WORK_LOG_DIR_NAME = "Bot_Work_Log";
    private static final String UPDATE_LOG_DIR_NAME = "System_Log";
    private static final String UPDATE_LOG_FILE_NAME = "update.log";

    private AppMetadata() {
    }

    public static String getAppName() {
        return APP_NAME;
    }

    public static String getCurrentVersion() {
        Package appPackage = AppMetadata.class.getPackage();
        if (appPackage != null) {
            String implementationVersion = appPackage.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.trim().isEmpty()) {
                return implementationVersion.trim();
            }
        }

        String manifestVersion = readVersionFromRunningLocation();
        if (!manifestVersion.isEmpty()) {
            return manifestVersion;
        }

        manifestVersion = readVersionFromFile(new File("manifest.mf"));
        if (!manifestVersion.isEmpty()) {
            return manifestVersion;
        }

        manifestVersion = readVersionFromFile(new File("META-INF\\MANIFEST.MF"));
        if (!manifestVersion.isEmpty()) {
            return manifestVersion;
        }

        return FALLBACK_VERSION;
    }

    public static File getRunningLocation() {
        try {
            CodeSource codeSource = AppMetadata.class.getProtectionDomain().getCodeSource();
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
        if (isRunningFromIde()) {
            File classesDir = location;
            File buildDir = classesDir.getParentFile();
            File projectDir = buildDir != null ? buildDir.getParentFile() : null;
            if (projectDir != null && projectDir.isDirectory()) {
                return projectDir;
            }
        }
        return location;
    }

    public static File getOutputDirectory() {
        return new File(getAppDirectory(), OUTPUT_DIR_NAME);
    }

    public static File getLauncherDataDirectory() {
        return new File(getAppDirectory(), LAUNCHER_DATA_DIR_NAME);
    }

    public static File getLauncherCredentialStoreFile() {
        return new File(getLauncherDataDirectory(), LAUNCHER_CREDENTIALS_FILE_NAME);
    }

    public static File getLauncherChromeProfileDirectory() {
        return new File(getLauncherDataDirectory(), LAUNCHER_CHROME_PROFILE_DIR_NAME);
    }

    public static File getLauncherAutoLoginLogFile() {
        return new File(getLauncherDataDirectory(), LAUNCHER_AUTO_LOGIN_LOG_FILE_NAME);
    }

    public static File getBotWorkLogDirectory() {
        return new File(getOutputDirectory(), BOT_WORK_LOG_DIR_NAME);
    }

    public static File getUpdateLogDirectory() {
        return new File(getOutputDirectory(), UPDATE_LOG_DIR_NAME);
    }

    public static File getUpdateLogFile() {
        return new File(getUpdateLogDirectory(), UPDATE_LOG_FILE_NAME);
    }

    public static boolean isRunningFromJar() {
        File location = getRunningLocation();
        return location.isFile() && location.getName().toLowerCase().endsWith(".jar");
    }

    public static boolean isRunningFromIde() {
        if (isRunningFromJar()) {
            return false;
        }
        File location = getRunningLocation();
        String normalized = location.getAbsolutePath().replace('/', '\\').toLowerCase();
        return normalized.endsWith("\\build\\classes");
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

    public static List<String> buildRestartCommand() {
        List<String> command = new ArrayList<String>();
        command.add(getJavaExecutable());
        command.add("-Dfile.encoding=" + System.getProperty("file.encoding", "UTF-8"));

        File location = getRunningLocation();
        if (isRunningFromJar()) {
            command.add("-jar");
            command.add(location.getAbsolutePath());
            return command;
        }

        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.trim().isEmpty()) {
            command.add("-cp");
            command.add(classPath);
        }
        String configuredMainClass = System.getProperty("botgetlog.restart.mainClass", DEFAULT_MAIN_CLASS).trim();
        if (configuredMainClass.isEmpty()) {
            configuredMainClass = DEFAULT_MAIN_CLASS;
        }
        command.add(configuredMainClass);
        return command;
    }

    private static String readVersionFromRunningLocation() {
        File location = getRunningLocation();
        if (location.isFile() && location.getName().toLowerCase().endsWith(".jar")) {
            try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(location)) {
                Manifest manifest = jarFile.getManifest();
                if (manifest == null) {
                    return "";
                }
                String version = manifest.getMainAttributes().getValue("Implementation-Version");
                return version == null ? "" : version.trim();
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    private static String readVersionFromFile(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (InputStream input = new FileInputStream(file)) {
            return readVersionFromStream(input);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readVersionFromStream(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        Manifest manifest = new Manifest(input);
        Attributes attributes = manifest.getMainAttributes();
        String version = attributes.getValue("Implementation-Version");
        return version == null ? "" : version.trim();
    }
}

