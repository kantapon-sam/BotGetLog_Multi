package com.java.myapp.linkoptical;

import java.io.File;
import java.io.IOException;

public class PathFile {


    private String GetLog;
    private File file;
    private File[] fileFolder;
private String LLDP;
    public PathFile() {
        try {
            GetLog = new File(".").getCanonicalPath() + "\\_output\\Total_Log";
            file = new File(GetLog);
            fileFolder = file.listFiles();
            LLDP = new File(".").getCanonicalPath() + "\\_output\\LLDP_Neighbor";
            new File(GetLog).mkdirs();
            new File(LLDP).mkdirs();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

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

    public String getLLDP() {
        return LLDP;
    }


}
