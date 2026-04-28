package com.java.botgetlog;

import javax.swing.JOptionPane;
import javax.swing.UIManager;

public class Dialog {

    private final Object[] options = {"Result", "Download", "Exit"};
    final Object[] dup = {"OK", "Cancel"};
    private int choice;

    public static void setLAF() {

        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception e) {
            System.err.println("Failed to set LookAndFeel");

        }
    }

    public void NoFile(String Str) {
        JOptionPane.showMessageDialog(null,
                "Invalid file " + Str,
                "Warning",
                JOptionPane.WARNING_MESSAGE);
    }

    public void Success() {
        java.awt.Toolkit.getDefaultToolkit().beep();
    }

    public void Error(String message) {
        if ("".equals(message)) {
            message = "Please Fill In Your Information.\nSheet _setting";
        }
        JOptionPane.showMessageDialog(null,
                message,
                "Error",
                JOptionPane.ERROR_MESSAGE);
        System.exit(0);
    }

    public void ShowSuccess(String node) {
        Success();
        JOptionPane.showMessageDialog(null,
                node+"\n",
                "Success",
                JOptionPane.PLAIN_MESSAGE);
    
        
    }

    public void Duplicates(String LoopbackIP) {
        Success();
        choice = JOptionPane.showOptionDialog(null,
                "Continue running the bot or not?\nIP Loopback Duplicates\n" + LoopbackIP,
                "IP Loopback Duplicates", JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, dup, dup[1]);
        if (choice != 0) {
            BotGetLog_Multi.requestShutdown("Duplicate loopback prompt canceled", 0);
        }
    }
}

