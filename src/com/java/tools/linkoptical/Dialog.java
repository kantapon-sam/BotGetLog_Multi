package com.java.tools.linkoptical;

import java.io.File;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

public class Dialog {

    public static void setLAF() {

        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception ex) {
            System.err.println("Failed to set LookAndFeel");

        }
    }

    public static void FileError(String name) {
        JOptionPane.showMessageDialog(null,
                "File " + name + "\nError !!!",
                "Error!",
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }

    public static void FileError(File file) {
        JOptionPane.showMessageDialog(null,
                "File " + file.getName() + "\nError !!!",
                "Error!",
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }

    public static void Success(String output_Optical, int TotalFile) {
        java.awt.Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(null,
                "Write Success" + "\n"
                + output_Optical + "\n"
                + TotalFile + " node",
                "Link Optical",
                JOptionPane.PLAIN_MESSAGE);
    }
}

