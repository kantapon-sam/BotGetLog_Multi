package com.java.botgetlog.dtac;

import com.java.shared.AppMetadata;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicProgressBarUI;

public class StopProgram extends JFrame implements ActionListener {

    private static final String BOT_VERSION = AppMetadata.getCurrentVersion();
    private static final ZoneId UI_ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static StopProgram instance;
    private static JLabel lblNetworkStatus;

    private JProgressBar progressBar;
    private JLabel progressLabel;
    private JLabel lblThreadInfo;
    private JLabel lblResultInfo;
    private JLabel lblFailureInfo;
    private JLabel lblStartValue;
    private JLabel lblEtaValue;
    private JLabel lblRemainingValue;
    private JButton btnStop;
    private final JToggleButton toggleTurbo = new JToggleButton("Turbo Mode OFF");
    private Timer statusTimer;

    private final LocalDateTime startTime = LocalDateTime.now(UI_ZONE);
    private boolean finishedShown = false;

    private static volatile boolean blinking = false;
    private static volatile Thread blinkThread = null;

    public StopProgram() {
        instance = this;

        setTitle("BotGetLog [DTAC] v" + BOT_VERSION + " - SSH Node Executor");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setResizable(false);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestStopWithConfirmation();
            }
        });

        UIManager.put("ToolTip.background", new Color(27, 38, 59));
        UIManager.put("ToolTip.foreground", Color.WHITE);
        UIManager.put("ToolTip.font", new Font("Segoe UI", Font.PLAIN, 12));

        GradientPanel mainPanel = new GradientPanel();
        mainPanel.setLayout(new BorderLayout(0, 8));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        mainPanel.add(createHeaderPanel(), BorderLayout.NORTH);
        mainPanel.add(createContentPanel(), BorderLayout.CENTER);
        mainPanel.add(createButtonPanel(), BorderLayout.SOUTH);

        setContentPane(mainPanel);
        pack();
        setSize(590, 430);
        setLocation(10, 10);
        setVisible(true);

        statusTimer = new Timer(1000, e -> refreshStatus());
        statusTimer.start();
        refreshStatus();
    }

    @Override
    public void dispose() {
        if (statusTimer != null) {
            statusTimer.stop();
            statusTimer = null;
        }
        if (instance == this) {
            instance = null;
        }
        super.dispose();
    }

    public static StopProgram getInstance() {
        return instance;
    }

    public static void closeWindow() {
        SwingUtilities.invokeLater(() -> {
            if (instance != null) {
                instance.dispose();
            }
        });
    }

    private JPanel createHeaderPanel() {
        RoundedPanel headerPanel = new RoundedPanel(new BorderLayout(10, 8),
                new Color(11, 24, 42, 228), new Color(65, 121, 214, 130));
        headerPanel.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel titlePanel = transparentPanel();
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("BotGetLog [DTAC] V" + BOT_VERSION);
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(new Color(110, 231, 255));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("SSH automation with validation, retry, cleanup, and live summary");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        subtitle.setForeground(new Color(183, 201, 226));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        titlePanel.add(title);
        titlePanel.add(Box.createVerticalStrut(2));
        titlePanel.add(subtitle);

        JPanel authorPanel = transparentPanel();
        authorPanel.setLayout(new BoxLayout(authorPanel, BoxLayout.Y_AXIS));

        JLabel author = new JLabel("Kantapon Samthong | 090-904-9751");
        author.setFont(new Font("Segoe UI", Font.BOLD, 12));
        author.setForeground(Color.WHITE);
        author.setHorizontalAlignment(SwingConstants.RIGHT);
        author.setAlignmentX(Component.RIGHT_ALIGNMENT);

        JLabel email = new JLabel("kantapon.samthong@ericsson.com");
        email.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        email.setForeground(new Color(180, 192, 210));
        email.setAlignmentX(Component.RIGHT_ALIGNMENT);

        authorPanel.add(author);
        authorPanel.add(Box.createVerticalStrut(2));
        authorPanel.add(email);

        JPanel topRow = transparentPanel();
        topRow.setLayout(new BorderLayout(10, 0));
        topRow.add(titlePanel, BorderLayout.WEST);
        topRow.add(authorPanel, BorderLayout.EAST);

        RoundedPanel statusPanel = new RoundedPanel(new BorderLayout(8, 0),
                new Color(15, 31, 52, 220), new Color(54, 86, 145, 120));
        statusPanel.setBorder(new EmptyBorder(6, 8, 6, 8));

        lblNetworkStatus = new JLabel("Network: ready");
        lblNetworkStatus.setFont(new Font("Consolas", Font.BOLD, 11));
        lblNetworkStatus.setForeground(new Color(160, 255, 182));

        final JToggleButton toggleAlarm = new JToggleButton("Alarm ON");
        styleToggleButton(toggleAlarm, new Color(16, 185, 129), new Color(4, 120, 87),
                new Color(34, 197, 94), new Color(180, 40, 40), new Color(120, 24, 24),
                "Toggle alarm sound");
        toggleAlarm.addItemListener(e -> {
            boolean muteOn = e.getStateChange() == ItemEvent.SELECTED;
            toggleAlarm.setText(muteOn ? "Alarm OFF" : "Alarm ON");
            toggleAlarm.setBackground(muteOn ? new Color(180, 40, 40) : new Color(16, 185, 129));
            toggleAlarm.putClientProperty("hoverColor",
                    muteOn ? new Color(120, 24, 24) : new Color(4, 120, 87));
            toggleAlarm.repaint();
            BotGetLog_DTAC.setAlarmEnabled(!muteOn);
            if (BotGetLog_DTAC.isAlarmEnabled()) {
                Toolkit.getDefaultToolkit().beep();
            }
        });

        statusPanel.add(lblNetworkStatus, BorderLayout.CENTER);
        statusPanel.add(toggleAlarm, BorderLayout.EAST);

        JPanel wrapper = transparentPanel();
        wrapper.setLayout(new BorderLayout(0, 12));
        wrapper.add(topRow, BorderLayout.NORTH);
        wrapper.add(statusPanel, BorderLayout.SOUTH);

        headerPanel.add(wrapper, BorderLayout.CENTER);
        return headerPanel;
    }

    private JPanel createContentPanel() {
        JPanel contentPanel = transparentPanel();
        contentPanel.setLayout(new BorderLayout(0, 8));

        RoundedPanel progressCard = new RoundedPanel(new BorderLayout(0, 8),
                new Color(12, 23, 39, 220), new Color(76, 111, 172, 110));
        progressCard.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel progressBody = transparentPanel();
        progressBody.setLayout(new BoxLayout(progressBody, BoxLayout.Y_AXIS));

        JLabel progressTitle = new JLabel("Execution Progress");
        progressTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        progressTitle.setForeground(new Color(196, 214, 240));
        progressTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressLabel = new JLabel("0 / 0 tasks completed");
        progressLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        progressLabel.setForeground(Color.WHITE);
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblThreadInfo = new JLabel("Mode: NORMAL | Threads: waiting for start");
        lblThreadInfo.setFont(new Font("Consolas", Font.PLAIN, 10));
        lblThreadInfo.setForeground(new Color(147, 197, 253));
        lblThreadInfo.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressBody.add(progressTitle);
        progressBody.add(Box.createVerticalStrut(4));
        progressBody.add(progressLabel);
        progressBody.add(Box.createVerticalStrut(4));
        progressBody.add(lblThreadInfo);
        progressBody.add(Box.createVerticalStrut(6));

        progressBar = new JProgressBar(0, 100);
        progressBar.setOpaque(false);
        progressBar.setBorder(BorderFactory.createEmptyBorder());
        progressBar.setForeground(new Color(14, 165, 233));
        progressBar.setBackground(new Color(39, 56, 82));
        progressBar.setFont(new Font("Segoe UI", Font.BOLD, 11));
        progressBar.setStringPainted(true);
        progressBar.setString("0.0%");
        progressBar.setPreferredSize(new Dimension(520, 20));
        progressBar.setUI(new RoundedProgressBarUI());
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressBody.add(progressBar);
        progressBody.add(Box.createVerticalStrut(8));

        lblResultInfo = createMetricValueLabel(new Color(134, 239, 172));
        lblFailureInfo = createMetricValueLabel(new Color(251, 191, 36));
        lblStartValue = createMetricValueLabel(new Color(103, 232, 249));
        lblEtaValue = createMetricValueLabel(new Color(250, 204, 21));
        lblRemainingValue = createMetricValueLabel(new Color(196, 214, 240));

        progressBody.add(lblResultInfo);
        progressBody.add(Box.createVerticalStrut(2));
        progressBody.add(lblFailureInfo);
        progressBody.add(Box.createVerticalStrut(2));
        progressBody.add(lblStartValue);
        progressBody.add(Box.createVerticalStrut(2));
        progressBody.add(lblEtaValue);
        progressBody.add(Box.createVerticalStrut(2));
        progressBody.add(lblRemainingValue);

        lblResultInfo.setText("Success: 0 | Failed: 0 | Stopped: 0");
        lblFailureInfo.setText("Fail type: Auth 0 | Network 0 | Incomplete 0 | Vendor 0");
        lblStartValue.setText("Start Time: " + startTime.format(DATE_TIME_FORMAT));
        lblEtaValue.setText("Estimated Finish: calculating...");
        lblRemainingValue.setText("Remaining: --:--:--");

        progressCard.add(progressBody, BorderLayout.CENTER);
        contentPanel.add(progressCard, BorderLayout.CENTER);
        return contentPanel;
    }

    private JPanel createButtonPanel() {
        JPanel btnPanel = transparentPanel();
        btnPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 8, 0));

        btnStop = new JButton("Stop Program");
        styleActionButton(btnStop, new Color(190, 48, 48), new Color(139, 32, 32),
                new Color(255, 102, 102));
        btnStop.addActionListener(this);

        styleToggleButton(toggleTurbo, new Color(37, 99, 235), new Color(29, 78, 216),
                new Color(96, 165, 250), new Color(234, 88, 12), new Color(194, 65, 12),
                "Boost DTAC SSH worker threads");
        toggleTurbo.addItemListener(e -> {
            boolean turboOn = e.getStateChange() == ItemEvent.SELECTED;
            toggleTurbo.setText(turboOn ? "Turbo Mode ON" : "Turbo Mode OFF");
            toggleTurbo.setBackground(turboOn ? new Color(234, 88, 12) : new Color(37, 99, 235));
            toggleTurbo.putClientProperty("hoverColor",
                    turboOn ? new Color(194, 65, 12) : new Color(29, 78, 216));
            progressBar.setForeground(turboOn ? new Color(251, 146, 60) : new Color(14, 165, 233));
            BotGetLog_DTAC.setTurboMode(turboOn);
            Toolkit.getDefaultToolkit().beep();
        });

        btnPanel.add(btnStop);
        btnPanel.add(toggleTurbo);
        return btnPanel;
    }

    private void refreshStatus() {
        int total = BotGetLog_DTAC.getTotalTasks();
        int done = BotGetLog_DTAC.getCompletedTasks();
        int success = BotGetLog_DTAC.getSuccessTaskCount();
        int failed = BotGetLog_DTAC.getFailedTaskCount();
        int stopped = BotGetLog_DTAC.getStoppedTaskCount();

        double percent = total <= 0 ? 0.0 : (done * 100.0 / total);
        progressLabel.setText(String.format("%d / %d tasks completed (%.1f%%)", done, total, percent));
        progressBar.setValue((int) Math.round(percent));
        progressBar.setString(String.format("%.1f%%", percent));

        ExecutorService exec = BotGetLog_DTAC.getExecutor();
        int active = 0;
        int pool = BotGetLog_DTAC.getCurrentThreadPoolSize();
        if (exec instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) exec;
            active = tpe.getActiveCount();
            pool = tpe.getCorePoolSize();
        }

        lblThreadInfo.setText(String.format("Mode: %s | Threads: %d active / %d total",
                BotGetLog_DTAC.isTurboModeActive() ? "TURBO" : "NORMAL", active, pool));
        lblResultInfo.setText(String.format("Success: %d | Failed: %d | Stopped: %d",
                success, failed, stopped));
        lblFailureInfo.setText(String.format(
                "Fail type: Auth %d | Network %d | Incomplete %d | Vendor %d | LogMissing %d | CmdSet %d",
                BotGetLog_DTAC.getAuthFailedTaskCount(),
                BotGetLog_DTAC.getNetworkFailedTaskCount(),
                BotGetLog_DTAC.getCommandIncompleteTaskCount(),
                BotGetLog_DTAC.getVendorMismatchTaskCount(),
                BotGetLog_DTAC.getLogMissingTaskCount(),
                BotGetLog_DTAC.getValidationMissingTaskCount()));

        updateTimeEstimate(total, done);

        if (!finishedShown && total > 0 && done >= total) {
            finishedShown = true;
            String message = failed > 0
                    ? "DTAC jobs finished with " + failed + " failed task(s).\nPlease check Summary_DTAC and Failed_DTAC files."
                    : "All DTAC jobs completed successfully.";
            int type = failed > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE;
            JOptionPane.showMessageDialog(this, message, "Finished", type);
            dispose();
            System.exit(0);
        }
    }

    private void updateTimeEstimate(int total, int done) {
        if (total <= 0 || done <= 0) {
            lblEtaValue.setText("Estimated Finish: calculating...");
            lblRemainingValue.setText("Remaining: --:--:--");
            return;
        }

        long elapsedMs = Duration.between(startTime, LocalDateTime.now(UI_ZONE)).toMillis();
        double avgPerTask = elapsedMs / (double) Math.max(done, 1);
        long remainMs = Math.max(0L, Math.round(avgPerTask * Math.max(total - done, 0)));
        LocalDateTime eta = LocalDateTime.now(UI_ZONE).plusNanos(remainMs * 1_000_000L);

        lblEtaValue.setText("Estimated Finish: " + eta.format(DATE_TIME_FORMAT));
        lblRemainingValue.setText("Remaining: " + formatDuration(remainMs)
                + " | Current Time: " + LocalDateTime.now(UI_ZONE).format(TIME_FORMAT));
    }

    private static String formatDuration(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private static JLabel createMetricValueLabel(Color color) {
        JLabel label = new JLabel("-");
        label.setFont(new Font("Consolas", Font.BOLD, 11));
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JPanel transparentPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    private static void styleActionButton(AbstractButton button, Color baseColor,
            Color hoverColor, Color borderColor) {
        styleButtonBase(button, baseColor, hoverColor, borderColor, new Dimension(150, 34));
    }

    private static void styleToggleButton(AbstractButton button, Color baseColor,
            Color hoverColor, Color borderColor, Color selectedColor,
            Color selectedHoverColor, String tooltip) {
        styleButtonBase(button, baseColor, hoverColor, borderColor, new Dimension(150, 34));
        button.setToolTipText(tooltip);
        button.putClientProperty("selectedColor", selectedColor);
        button.putClientProperty("selectedHoverColor", selectedHoverColor);
    }

    private static void styleButtonBase(AbstractButton button, Color baseColor,
            Color hoverColor, Color borderColor, Dimension size) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 11));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(size);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setUI(new BasicButtonUI());
        button.setBackground(baseColor);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1, true),
                new EmptyBorder(6, 18, 6, 18)));
        button.putClientProperty("normalColor", baseColor);
        button.putClientProperty("hoverColor", hoverColor);
        installHoverEffect(button);
    }

    private static void installHoverEffect(final AbstractButton button) {
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                Color hover = (Color) button.getClientProperty(button.isSelected()
                        ? "selectedHoverColor" : "hoverColor");
                if (hover != null) {
                    button.setBackground(hover);
                    button.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Color normal = (Color) button.getClientProperty(button.isSelected()
                        ? "selectedColor" : "normalColor");
                if (normal != null) {
                    button.setBackground(normal);
                    button.repaint();
                }
            }
        });
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == btnStop) {
            requestStopWithConfirmation();
        }
    }

    private void requestStopWithConfirmation() {
        if (btnStop != null && !btnStop.isEnabled()) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Do you really want to stop BotGetLog [DTAC]?",
                "Confirm Stop",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            if (btnStop != null) {
                btnStop.setText("Stopping now...");
                btnStop.setEnabled(false);
            }
            startForceHaltWatchdog();
            new Thread(() -> BotGetLog_DTAC.requestImmediateShutdown("User pressed Stop Program", 0),
                    "dtac-manual-stop-shutdown").start();
        }
    }

    private static void startForceHaltWatchdog() {
        Thread forceHalt = new Thread(() -> {
            try {
                Thread.sleep(5000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                System.err.println("[STOP] DTAC JVM did not exit within 5 seconds; forcing halt now.");
            } catch (Throwable ignored) {
            }
            Runtime.getRuntime().halt(0);
        }, "dtac-manual-stop-force-halt");
        forceHalt.setDaemon(false);
        forceHalt.start();
    }

    public static void updateNetworkStatus(String text, Color color) {
        if (instance == null) return;
        SwingUtilities.invokeLater(() -> {
            if (lblNetworkStatus == null) return;
            lblNetworkStatus.setText(text);
            lblNetworkStatus.setForeground(color);
        });

        if (Color.RED.equals(color)) {
            if (!blinking) {
                blinking = true;
                blinkThread = new Thread(() -> {
                    try {
                        boolean on = true;
                        Color bg = new Color(25, 25, 30);
                        while (blinking) {
                            final Color c = on ? Color.RED : bg;
                            SwingUtilities.invokeLater(() -> {
                                if (lblNetworkStatus != null) lblNetworkStatus.setForeground(c);
                            });
                            on = !on;
                            Thread.sleep(500L);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }, "dtac-network-blink");
                blinkThread.setDaemon(true);
                blinkThread.start();
            }
        } else {
            blinking = false;
            SwingUtilities.invokeLater(() -> {
                if (lblNetworkStatus != null) lblNetworkStatus.setForeground(color);
            });
        }
    }

    private static final class GradientPanel extends JPanel {
        GradientPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            Paint paint = new LinearGradientPaint(0, 0, getWidth(), getHeight(),
                    new float[]{0.0f, 0.48f, 1.0f},
                    new Color[]{
                        new Color(4, 10, 22),
                        new Color(12, 38, 63),
                        new Color(5, 18, 33)
                    });
            g2.setPaint(paint);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    private static final class RoundedPanel extends JPanel {
        private final Color backgroundColor;
        private final Color borderColor;

        RoundedPanel(java.awt.LayoutManager layout, Color backgroundColor, Color borderColor) {
            super(layout);
            this.backgroundColor = backgroundColor;
            this.borderColor = borderColor;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = 18;
            g2.setColor(backgroundColor);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.setStroke(new BasicStroke(1.2f));
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class RoundedProgressBarUI extends BasicProgressBarUI {
        @Override
        protected void paintDeterminate(Graphics g, JComponent c) {
            Insets b = progressBar.getInsets();
            int width = progressBar.getWidth() - (b.left + b.right);
            int height = progressBar.getHeight() - (b.top + b.bottom);
            int amountFull = getAmountFull(b, width, height);

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(progressBar.getBackground());
            g2.fillRoundRect(b.left, b.top, width, height, height, height);
            if (amountFull > 0) {
                GradientPaint gp = new GradientPaint(0, 0, progressBar.getForeground(),
                        width, 0, new Color(34, 211, 238));
                g2.setPaint(gp);
                g2.fillRoundRect(b.left, b.top, amountFull, height, height, height);
            }
            if (progressBar.isStringPainted()) {
                paintString(g2, b.left, b.top, width, height, amountFull, b);
            }
            g2.dispose();
        }
    }
}
