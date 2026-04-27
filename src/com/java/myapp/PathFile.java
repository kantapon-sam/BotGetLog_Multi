package com.java.myapp;

import java.io.File;
import java.io.IOException;

public class PathFile {

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

            CurrentFolder = new File(".").getCanonicalPath();
            FolderCurrent = new File(CurrentFolder);
            FileBot = FolderCurrent.listFiles();
            UserInterface_Input = new File(".").getCanonicalPath() + "\\UserInterface_Input.xlsx";
            Log = new File(".").getCanonicalPath() + "\\_output\\Total_Log\\";
            FileRow = new File(CurrentFolder);
            LogWork = new File(".").getCanonicalPath() + "\\JAR\\log\\";
            new File(Log).mkdirs();
            new File(LogWork).mkdirs();

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

}
