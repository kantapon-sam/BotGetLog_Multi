package com.java.myapp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class PathFile {

    private static final String USER_INPUT_FILE_NAME = "UserInterface_Input.xlsx";
    private static final String DEFAULT_INPUT_RELATIVE_PATH = "defaults\\" + USER_INPUT_FILE_NAME;
    private static final String LEGACY_LOG_WORK_RELATIVE_PATH = "JAR\\log";

    private String CurrentFolder;
    private String UserInterface_Input;
    private String Node_Connection_failed;
    private String Log;
    private File FileRow;
    private File FolderCurrent;
    private File[] FileBot;
    private String LogWork;
    private String row;

    public PathFile() {
        try {
            CurrentFolder = AppMetadata.getAppDirectory().getCanonicalPath();
            FolderCurrent = new File(CurrentFolder);
            FileBot = FolderCurrent.listFiles();
            UserInterface_Input = ensureUserInputWorkbook(FolderCurrent).getCanonicalPath();
            Log = FolderCurrent.getCanonicalPath() + "\\_output\\Total_Log\\";
            FileRow = new File(CurrentFolder);
            File logWorkDir = AppMetadata.getBotWorkLogDirectory().getCanonicalFile();
            migrateLegacyWorkLogs(FolderCurrent, logWorkDir);
            LogWork = logWorkDir.getCanonicalPath() + "\\";
            new File(Log).mkdirs();
            logWorkDir.mkdirs();

        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public String getCurrentFolder() {
        return CurrentFolder;
    }

    public String getUserInterface_Input() {
        return UserInterface_Input;
    }

    public String getLog() {
        return Log;
    }

    public File getFileRow() {
        return FileRow;
    }

    public File getFolderCurrent() {
        return FolderCurrent;
    }

    public File[] getFileBot() {
        return FileBot;
    }

    public String getLogWork() {
        return LogWork;
    }

    private static File ensureUserInputWorkbook(File appDir) throws IOException {
        File userWorkbook = new File(appDir, USER_INPUT_FILE_NAME);
        if (userWorkbook.isFile()) {
            return userWorkbook;
        }

        File bundledDefault = new File(appDir, DEFAULT_INPUT_RELATIVE_PATH);
        if (bundledDefault.isFile()) {
            Path targetPath = userWorkbook.toPath();
            Files.copy(bundledDefault.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            return userWorkbook;
        }

        return userWorkbook;
    }

    private static void migrateLegacyWorkLogs(File appDir, File targetDir) throws IOException {
        if (appDir == null || targetDir == null) {
            return;
        }

        File legacyDir = new File(appDir, LEGACY_LOG_WORK_RELATIVE_PATH).getCanonicalFile();
        if (!legacyDir.isDirectory()) {
            return;
        }

        if (legacyDir.equals(targetDir.getCanonicalFile())) {
            return;
        }

        targetDir.mkdirs();
        File[] legacyChildren = legacyDir.listFiles();
        if (legacyChildren == null) {
            return;
        }

        for (File legacyChild : legacyChildren) {
            File migratedChild = new File(targetDir, legacyChild.getName());
            if (migratedChild.exists()) {
                continue;
            }
            Files.move(legacyChild.toPath(), migratedChild.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
