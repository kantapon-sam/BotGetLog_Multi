package com.java.shared;

import com.java.botgetlog.AppConsole;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.File;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

public final class ToolDialogHelper {

    private ToolDialogHelper() {
    }

    public static void setWindowsLookAndFeel() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception ex) {
            System.err.println("Failed to set LookAndFeel");
        }
    }

    public static void showFileError(String name) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("File " + name + " Error !!!");
            System.exit(0);
        }

        JOptionPane.showMessageDialog(null,
                "File " + name + "\nError !!!",
                "Error!",
                JOptionPane.ERROR_MESSAGE);
        AppConsole.close();
        System.exit(0);
    }

    public static void showFileError(File file) {
        if (file == null) {
            showFileError("");
            return;
        }
        showFileError(file.getName());
    }

    public static void showSuccess(String toolName, String summary, int totalFile) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Write Success");
            System.out.println(summary);
            System.out.println(totalFile + " node");
            return;
        }

        Toolkit.getDefaultToolkit().beep();
        JOptionPane.showMessageDialog(null,
                "Write Success" + "\n"
                + summary + "\n"
                + totalFile + " node",
                toolName,
                JOptionPane.PLAIN_MESSAGE);
        AppConsole.close();
    }

    public static void showInfo(String toolName, String message) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println(message);
            return;
        }

        JOptionPane.showMessageDialog(null,
                message,
                toolName,
                JOptionPane.INFORMATION_MESSAGE);
        AppConsole.close();
    }
}
