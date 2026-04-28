package com.java.tools.arp;

import java.io.File;
import java.io.IOException;

public class PathFile {

    private String Optical;
    private String GetLog;
    private File file;
    private File[] fileFolder;

    public PathFile() {
        try {
            GetLog = new File(".").getCanonicalPath() + "\\_output\\Total_Log";
            file = new File(GetLog);
            fileFolder = file.listFiles();
            Optical = new File(".").getCanonicalPath() + "\\_output\\ARP";
            new File(GetLog).mkdirs();
            new File(Optical).mkdirs();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

    }

    public String getOptical() {
        return Optical;
    }

    public String getGetLog() {
        return GetLog;
    }

    public File getFile() {
        return file;
    }

    public File[] getFileFolder() {
        return fileFolder;
    }

}

