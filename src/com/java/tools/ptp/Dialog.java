package com.java.tools.ptp;

import java.io.File;
import java.awt.GraphicsEnvironment;
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
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("File " + name + " Error !!!");
            System.exit(0);
        }
        JOptionPane.showMessageDialog(null,
                "File " + name + "\nError !!!",
                "Error!",
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }

    public static void FileError(File file) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("File " + file.getName() + " Error !!!");
            System.exit(0);
        }
        JOptionPane.showMessageDialog(null,
                "File " + file.getName() + "\nError !!!",
                "Error!",
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }

    public static void Success(String output_Optical, int TotalFile) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Write Success");
            System.out.println(output_Optical);
            System.out.println(TotalFile + " node");
            return;
        }
        java.awt.Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(null,
                "Write Success" + "\n"
                + output_Optical + "\n"
                + TotalFile + " node",
                "PTP",
                JOptionPane.PLAIN_MESSAGE);
    }
}

