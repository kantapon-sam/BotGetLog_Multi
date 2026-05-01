package com.java.tools.linkoptical;

import com.java.shared.ToolDialogHelper;
import java.io.File;

public class Dialog {

    public static void setLAF() {
        ToolDialogHelper.setWindowsLookAndFeel();
    }

    public static void FileError(String name) {
        ToolDialogHelper.showFileError(name);
    }

    public static void FileError(File file) {
        ToolDialogHelper.showFileError(file);
    }

    public static void Success(String output_Optical, int TotalFile) {
        ToolDialogHelper.showSuccess("Link Optical", output_Optical, TotalFile);
    }

    public static void Info(String message) {
        ToolDialogHelper.showInfo("Link Optical", message);
    }
}

