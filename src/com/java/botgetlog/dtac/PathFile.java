package com.java.botgetlog.dtac;

import com.java.shared.AppMetadata;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class PathFile {

    private static final String USER_INPUT_FILE_NAME = "UserInterface_Input.xlsx";
    private static final String DEFAULT_INPUT_RELATIVE_PATH = "defaults\\" + USER_INPUT_FILE_NAME;

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

            // โฟลเดอร์ปัจจุบัน (path ที่รันโปรแกรม / jar อยู่)
            CurrentFolder = AppMetadata.getAppDirectory().getCanonicalPath();

            FolderCurrent = new File(CurrentFolder);
            FileBot = FolderCurrent.listFiles();

            // -------------------------------
            // ไฟล์ input
            // -------------------------------
            UserInterface_Input = ensureUserInputWorkbook(FolderCurrent).getCanonicalPath();

            // -------------------------------
            // Log → _output\Total_Log\
            // -------------------------------
            Log = CurrentFolder + "\\_output\\Total_Log\\";

            // -------------------------------
            // LogWork → JAR\log\
            // -------------------------------
            File logWorkDir = AppMetadata.getBotWorkLogDirectory().getCanonicalFile();
            LogWork = logWorkDir.getCanonicalPath() + "\\";

            // -------------------------------
            // สร้างโฟลเดอร์ที่จำเป็น
            // -------------------------------
            new File(CurrentFolder + "\\_output").mkdirs();
            new File(Log).mkdirs();
            logWorkDir.mkdirs();

            FileRow = new File(CurrentFolder);

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

}
