package com.java.botgetlog.dtac;

import javax.swing.*;
import java.io.IOException;

/**
 * Popup dialog ง่าย ๆ สำหรับแจ้ง error / confirm ฯลฯ
 */
public class Dialog {

    private final Object[] options = {"Result", "Download", "Exit"};
    private final Object[] dup = {"OK", "Cancel"};
    private int choice;

    public static void setLAF() {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception e) {
            // ใช้ default ถ้าตั้งค่าไม่ได้
        }
    }

    public void showError(String message) {
        JOptionPane.showMessageDialog(null,
                message,
                "Error",
                JOptionPane.ERROR_MESSAGE);
    }

    public void showInfo(String message) {
        JOptionPane.showMessageDialog(null,
                message,
                "Information",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * ใช้ตอนเจอ Loopback IP ซ้ำ (ถ้าพี่อยากใช้)
     */
    public void Duplicate(String loopbackIP) {
        choice = JOptionPane.showOptionDialog(
                null,
                "Continue running the bot or not?\nIP Loopback Duplicates\n" + loopbackIP,
                "IP Loopback Duplicates",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                dup,
                dup[1]);

        if (choice != 0) {
            try {
                Process p1 = Runtime.getRuntime().exec("taskkill /F /IM cmd.exe");
                Thread.sleep(500);
            } catch (IOException | InterruptedException ex) {
                System.out.println(ex.toString());
            }
            System.exit(0);
        }
    }
}
