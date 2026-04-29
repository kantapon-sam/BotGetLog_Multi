package com.java.launcher;

import com.java.analytics.UsageAnalytics;
import com.java.shared.AppMetadata;
import com.java.updater.AutoUpdateManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

public class BotToolLauncher {

    private static final String BOT_JAR_NAME = "BotGetLog_Multi.jar";
    private static final String LINK_OPTICAL_JAR_NAME = "Link_Optical.jar";
    private static final String ARP_JAR_NAME = "ARP.jar";
    private static final String PTP_JAR_NAME = "PTP.jar";
    private static final String OUTPUT_DIR = "_output";
    private static final String LEGACY_LOG_DIR = "JAR\\log";
    private static final String FALLBACK_VERSION = "1.0.9";

    private JFrame frame;
    private JTextArea textArea;
    private JButton launchBotButton;
    private JButton linkOpticalButton;
    private JButton arpButton;
    private JButton ptpButton;
    private JButton resetButton;
    private JButton exitButton;

    private void show() {
        setLookAndFeel();
        ensureFrame();
        showRedirectNoticeIfAny();
        frame.setVisible(true);
        frame.toFront();
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception ignored) {
        }
    }

    private void ensureFrame() {
        if (frame != null) {
            return;
        }

        textArea = new JTextArea(24, 92);
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setBackground(Color.BLACK);
        textArea.setForeground(new Color(230, 230, 230));
        textArea.setCaretColor(Color.WHITE);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        textArea.setText(buildLauncherText());

        launchBotButton = new JButton("BotGetLog");
        linkOpticalButton = new JButton("Link Optical");
        arpButton = new JButton("ARP");
        ptpButton = new JButton("PTP");
        resetButton = new JButton("Reset");
        exitButton = new JButton("Exit");

        launchBotButton.addActionListener(e -> launchBotJar());
        linkOpticalButton.addActionListener(e -> launchLinkOpticalJar());
        arpButton.addActionListener(e -> launchArpJar());
        ptpButton.addActionListener(e -> launchPtpJar());
        resetButton.addActionListener(e -> resetGeneratedFiles());
        exitButton.addActionListener(e -> frame.dispose());

        JPanel actionListPanel = new JPanel();
        actionListPanel.setLayout(new BoxLayout(actionListPanel, BoxLayout.Y_AXIS));
        actionListPanel.setBackground(new Color(235, 235, 235));
        actionListPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel actionTitle = new JLabel("Tool Menu");
        actionTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        actionTitle.setAlignmentX(JLabel.LEFT_ALIGNMENT);

        JPanel actionButtonsPanel = new JPanel(new GridLayout(0, 1, 0, 8));
        actionButtonsPanel.setOpaque(false);
        actionButtonsPanel.add(launchBotButton);
        actionButtonsPanel.add(linkOpticalButton);
        actionButtonsPanel.add(arpButton);
        actionButtonsPanel.add(ptpButton);
        actionButtonsPanel.add(resetButton);
        actionButtonsPanel.add(exitButton);

        actionListPanel.add(actionTitle);
        actionListPanel.add(Box.createVerticalStrut(10));
        actionListPanel.add(actionButtonsPanel);

        frame = new JFrame("Bot Tool Launcher - Console");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(textArea), BorderLayout.CENTER);
        frame.add(actionListPanel, BorderLayout.EAST);
        frame.setPreferredSize(new Dimension(980, 640));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private String buildLauncherText() {
        String lineBreak = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("[INFO] Bot Tool Launcher initialized").append(lineBreak);
        text.append("[INFO] Current version: ").append(getCurrentVersion()).append(lineBreak);
        text.append("[INFO] Select a menu from the buttons below.").append(lineBreak);
        text.append(lineBreak);
        text.append("1. BotGetLog").append(lineBreak);
        text.append("   Run the main bot directly without extra menu.").append(lineBreak);
        text.append(lineBreak);
        text.append("2. Link Optical").append(lineBreak);
        text.append("   Open the built-in Link_Optical tool from this project.").append(lineBreak);
        text.append(lineBreak);
        text.append("3. ARP").append(lineBreak);
        text.append("   Open the built-in ARP tool from this project.").append(lineBreak);
        text.append(lineBreak);
        text.append("4. PTP").append(lineBreak);
        text.append("   Open the built-in PTP tool from this project.").append(lineBreak);
        text.append(lineBreak);
        text.append("5. Reset").append(lineBreak);
        text.append("   Delete generated log/output files in this bot folder.").append(lineBreak);
        text.append(lineBreak);
        text.append("6. Exit").append(lineBreak);
        text.append("   Close this launcher.").append(lineBreak);
        text.append(lineBreak);
        text.append("[PATH] ").append(getAppDirectory().getAbsolutePath()).append(lineBreak);
        return text.toString();
    }

    private static String getCurrentVersion() {
        Package appPackage = BotToolLauncher.class.getPackage();
        if (appPackage != null) {
            String implementationVersion = appPackage.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.trim().isEmpty()) {
                return implementationVersion.trim();
            }
        }
        return FALLBACK_VERSION;
    }

    private void launchBotJar() {
        launchBotButton.setEnabled(false);
        launchProgram(findBotJar(), BOT_JAR_NAME, launchBotButton);
    }

    private void launchLinkOpticalJar() {
        linkOpticalButton.setEnabled(false);
        launchProgram(findLinkOpticalJar(), LINK_OPTICAL_JAR_NAME, linkOpticalButton);
    }

    private void launchArpJar() {
        arpButton.setEnabled(false);
        launchProgram(findArpJar(), ARP_JAR_NAME, arpButton);
    }

    private void launchPtpJar() {
        ptpButton.setEnabled(false);
        launchProgram(findPtpJar(), PTP_JAR_NAME, ptpButton);
    }

    private void launchProgram(File jarFile, String displayName, JButton sourceButton) {
        if (jarFile == null || !jarFile.isFile()) {
            if (sourceButton != null) {
                sourceButton.setEnabled(true);
            }
            JOptionPane.showMessageDialog(
                    frame,
                    "Cannot find " + displayName,
                    "Program file not found",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        appendLine("[RUN] Starting " + jarFile.getName());
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    getJavaLauncher(),
                    "-D" + LauncherGate.INVOKED_BY_LAUNCHER_PROPERTY + "=true",
                    "-jar",
                    jarFile.getAbsolutePath()
            );
            File workingDir = jarFile.getParentFile();
            if (workingDir != null) {
                processBuilder.directory(workingDir);
            }
            processBuilder.start();
            frame.dispose();
        } catch (IOException ex) {
            if (sourceButton != null) {
                sourceButton.setEnabled(true);
            }
            appendLine("[ERROR] Launch failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(
                    frame,
                    "Unable to start " + displayName + "\n" + ex.getMessage(),
                    "Launch failed",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void showRedirectNoticeIfAny() {
        String requestedTool = System.getProperty(LauncherGate.REQUESTED_TOOL_PROPERTY, "").trim();
        if (requestedTool.isEmpty()) {
            return;
        }
        appendLine("[INFO] Direct launch of " + requestedTool + " is disabled.");
        appendLine("[INFO] Please start " + requestedTool + " from the Tool Menu on the right.");
    }

    private void resetGeneratedFiles() {
        Object[] options = {"Yes", "No"};
        int answer = JOptionPane.showOptionDialog(
                frame,
                "Delete generated log/output files now?",
                "Confirm Reset",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[1]
        );

        if (answer != JOptionPane.YES_OPTION) {
            appendLine("[RESET] Cancelled by user.");
            return;
        }

        File appDir = getAppDirectory();
        File outputDir = new File(appDir, OUTPUT_DIR);
        File legacyLogDir = new File(appDir, LEGACY_LOG_DIR);

        int deletedCount = 0;
        deletedCount += deleteContents(outputDir);
        deletedCount += deleteRecursively(legacyLogDir);

        outputDir.mkdirs();
        new File(outputDir, "Total_Log").mkdirs();
        AppMetadata.getBotWorkLogDirectory().mkdirs();
        AppMetadata.getUpdateLogDirectory().mkdirs();

        appendLine("[RESET] Completed. Deleted items: " + deletedCount);
        JOptionPane.showMessageDialog(
                frame,
                "Reset complete\nDeleted items: " + deletedCount,
                "Reset finished",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void appendLine(String text) {
        if (textArea == null) {
            return;
        }
        textArea.append(text + System.lineSeparator());
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    private static int deleteContents(File target) {
        if (target == null || !target.exists()) {
            return 0;
        }

        int deletedCount = 0;
        File[] children = target.listFiles();
        if (children == null) {
            return 0;
        }

        for (File child : children) {
            deletedCount += deleteRecursively(child);
        }
        return deletedCount;
    }

    private static int deleteRecursively(File target) {
        int deletedCount = 0;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletedCount += deleteRecursively(child);
                }
            }
        }

        if (target.delete()) {
            deletedCount++;
        }
        return deletedCount;
    }

    private static String getJavaLauncher() {
        String javaHome = System.getProperty("java.home", "");
        File javaw = new File(javaHome, "bin\\javaw.exe");
        if (javaw.isFile()) {
            return javaw.getAbsolutePath();
        }

        File java = new File(javaHome, "bin\\java.exe");
        if (java.isFile()) {
            return java.getAbsolutePath();
        }

        return "java";
    }

    private static File findBotJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, BOT_JAR_NAME),
            new File(appDir, "dist\\" + BOT_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[0];
    }

    private static File findLinkOpticalJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, LINK_OPTICAL_JAR_NAME),
            new File(appDir, "dist\\" + LINK_OPTICAL_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    private static File findArpJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, ARP_JAR_NAME),
            new File(appDir, "dist\\" + ARP_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    private static File findPtpJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, PTP_JAR_NAME),
            new File(appDir, "dist\\" + PTP_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    private static File getAppDirectory() {
        try {
            CodeSource codeSource = BotToolLauncher.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return new File(".").getCanonicalFile();
            }

            URL location = codeSource.getLocation();
            File file = new File(location.toURI()).getCanonicalFile();
            if (file.isFile()) {
                File parent = file.getParentFile();
                return parent != null ? parent : new File(".").getCanonicalFile();
            }
            String normalized = file.getAbsolutePath().replace('/', '\\').toLowerCase();
            if (normalized.endsWith("\\build\\classes")) {
                File buildDir = file.getParentFile();
                File projectDir = buildDir != null ? buildDir.getParentFile() : null;
                if (projectDir != null && projectDir.isDirectory()) {
                    return projectDir.getCanonicalFile();
                }
            }
            return file;
        } catch (IOException | URISyntaxException ex) {
            return new File(".").getAbsoluteFile();
        }
    }

    public static void main(String[] args) {
        if (!AppMetadata.isRunningFromIde() && AutoUpdateManager.checkForUpdatesAtStartup()) {
            return;
        }
        UsageAnalytics.trackLaunchAsync("BotToolLauncher");
        SwingUtilities.invokeLater(() -> new BotToolLauncher().show());
    }
}

