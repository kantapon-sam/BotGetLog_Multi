package com.java.myapp;

import com.sun.management.OperatingSystemMXBean;
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
import java.awt.MouseInfo;
import java.awt.Paint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
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
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicProgressBarUI;

public class StopProgram extends JFrame implements ActionListener {

    private static final String BOT_VERSION = AppMetadata.getCurrentVersion();
    private static final ZoneId UI_ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy");
    private static final int ETA_SAMPLE_LIMIT = 12;

    private static StopProgram instance;
    private static JProgressBar progressBar;
    private static JLabel progressLabel;
    private static JLabel lblThreadInfo;
    private static JLabel lblNetworkStatus;
    private static JLabel lblStartValue;
    private static JLabel lblEtaValue;
    private static JLabel lblRemainingValue;
    private static JLabel lblAccuracyValue;

    private static ThreadPoolExecutor currentExecutor;
    private static volatile boolean blinking = false;
    private static volatile Thread blinkThread = null;

    private static long startMillis = 0L;
    private static long lastEtaSampleMillis = 0L;
    private static int lastCompletedTasks = 0;
    private static double smoothedThroughputPerSecond = 0.0;
    private static final Deque<Double> throughputSamples = new ArrayDeque<Double>();

    private static int fixedBaseThreads = Telnet_Multi.NORMAL_TELNET_LIMIT;

    private JButton btnClose;
    private final JToggleButton toggleTurbo = new JToggleButton("Turbo Mode OFF");

    public StopProgram() {
        instance = this;

        setTitle("BotGetLog Multithread v" + BOT_VERSION);
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
        setSize(560, 410);
        setLocation(10, 10);
        setVisible(true);

        startThreadMonitor();
        startKeepAwakeThread();
    }

    private JPanel createHeaderPanel() {
        RoundedPanel headerPanel = new RoundedPanel(new BorderLayout(10, 8),
                new Color(11, 24, 42, 228), new Color(65, 121, 214, 130));
        headerPanel.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel titlePanel = transparentPanel();
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("BotGetLog V" + BOT_VERSION);
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(new Color(110, 231, 255));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Automation Node Executor - Updated to 1.0.1");
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

        lblNetworkStatus = new JLabel("Network: checking connection...");
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
            BotGetLog_Multi.setAlarmEnabled(!muteOn);
            if (BotGetLog_Multi.isAlarmEnabled()) {
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

        progressLabel = new JLabel("0 / 0 nodes completed");
        progressLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        progressLabel.setForeground(Color.WHITE);
        progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        lblThreadInfo = new JLabel("Mode: WARMUP | Threads: waiting for start | CPU: --.-%");
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
        progressBar.setPreferredSize(new Dimension(500, 20));
        progressBar.setUI(new RoundedProgressBarUI());

        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBody.add(progressBar);
        progressBody.add(Box.createVerticalStrut(6));

        lblStartValue = createMetricValueLabel(new Color(103, 232, 249));
        lblEtaValue = createMetricValueLabel(new Color(250, 204, 21));
        lblRemainingValue = createMetricValueLabel(new Color(134, 239, 172));
        lblAccuracyValue = createMetricValueLabel(new Color(251, 191, 36));

        progressBody.add(lblStartValue);
        progressBody.add(Box.createVerticalStrut(2));
        progressBody.add(lblEtaValue);
        progressBody.add(Box.createVerticalStrut(2));
        progressBody.add(lblRemainingValue);
        progressBody.add(Box.createVerticalStrut(2));
        progressBody.add(lblAccuracyValue);

        progressCard.add(progressBody, BorderLayout.CENTER);
        contentPanel.add(progressCard, BorderLayout.CENTER);

        setTimelineDefaults();
        return contentPanel;
    }

    private JPanel createButtonPanel() {
        JPanel btnPanel = transparentPanel();
        btnPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 8, 0));

        btnClose = new JButton("Stop Program");
        styleActionButton(btnClose, new Color(190, 48, 48), new Color(139, 32, 32),
                new Color(255, 102, 102));
        btnClose.addActionListener(this);

        styleToggleButton(toggleTurbo, new Color(37, 99, 235), new Color(29, 78, 216),
                new Color(96, 165, 250), new Color(234, 88, 12), new Color(194, 65, 12),
                "Boost concurrent telnet threads");
        toggleTurbo.addItemListener(e -> {
            boolean focusOn = e.getStateChange() == ItemEvent.SELECTED;
            toggleTurbo.setText(focusOn ? "Turbo Mode ON" : "Turbo Mode OFF");
            toggleTurbo.setBackground(focusOn ? new Color(234, 88, 12) : new Color(37, 99, 235));
            toggleTurbo.putClientProperty("hoverColor",
                    focusOn ? new Color(194, 65, 12) : new Color(29, 78, 216));
            toggleTurbo.repaint();

            progressBar.setForeground(focusOn ? new Color(251, 146, 60) : new Color(14, 165, 233));
            BotGetLog_Multi.setFocusMode(focusOn);
            Toolkit.getDefaultToolkit().beep();
        });

        btnPanel.add(btnClose);
        btnPanel.add(toggleTurbo);
        return btnPanel;
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

    private void startThreadMonitor() {
        Thread monitor = new Thread(() -> {
            try {
                OperatingSystemMXBean os =
                        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                CpuSmooth uiCpuSmooth = new CpuSmooth(0.18, 25);

                while (BotGetLog_Multi.shouldBackgroundWorkersRun()) {
                    ThreadPoolExecutor exec = BotGetLog_Multi.getExecutor();
                    if (exec != null && !exec.isShutdown()) {
                        int active = exec.getActiveCount();
                        double avgCpu = uiCpuSmooth.updateFromOs(os, true);

                        boolean turbo = BotGetLog_Multi.isFocusModeActive();
                        int baseThreads = turbo
                                ? Telnet_Multi.getTurboTelnetLimit()
                                : Telnet_Multi.NORMAL_TELNET_LIMIT;

                        if (fixedBaseThreads != baseThreads) {
                            fixedBaseThreads = baseThreads;
                            System.out.println("\n Mode changed -> BaseThreads = " + fixedBaseThreads);
                        }

                        final int currentThreads = exec.getCorePoolSize();
                        final int fActive = active;
                        final double fCpu = avgCpu;
                        final boolean fTurbo = turbo;

                        SwingUtilities.invokeLater(() ->
                                StopProgram.updateThreadStatusFromBot(
                                        fTurbo ? "TURBO" : "NORMAL",
                                        fActive, currentThreads, fCpu));
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                System.err.println(" Thread monitor interrupted.");
                Thread.currentThread().interrupt();
            }
        }, "ui-thread-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private void startKeepAwakeThread() {
        Thread keepAwake = new Thread(() -> {
            try {
                Robot robot = new Robot();
                boolean toggle = false;
                while (BotGetLog_Multi.shouldBackgroundWorkersRun()) {
                    Point mouse = MouseInfo.getPointerInfo().getLocation();
                    int moveX = toggle ? 1 : -1;
                    robot.mouseMove(mouse.x + moveX, mouse.y);
                    toggle = !toggle;
                    System.out.println("[KEEP-AWAKE] Mouse nudged to prevent sleep.");
                    Thread.sleep(10_000);
                }
            } catch (Exception e) {
                System.err.println("[KEEP-AWAKE] Error: " + e.getMessage());
            }
        }, "ui-keep-awake");
        keepAwake.setDaemon(true);
        keepAwake.start();
    }

    private static synchronized void setTimelineDefaults() {
        if (lblStartValue != null) {
            lblStartValue.setText("Start Time: -");
        }
        if (lblEtaValue != null) {
            lblEtaValue.setText("Estimated Finish: calculating...");
        }
        if (lblRemainingValue != null) {
            lblRemainingValue.setText("Remaining: --:--:-- | Finish Date: -");
        }
        if (lblAccuracyValue != null) {
            lblAccuracyValue.setText("ETA Confidence: warming up");
            lblAccuracyValue.setToolTipText("Rolling throughput is warming up");
        }
    }

    private static synchronized void resetEtaTracking(long now, int completedTasks) {
        startMillis = now;
        lastEtaSampleMillis = now;
        lastCompletedTasks = completedTasks;
        smoothedThroughputPerSecond = 0.0;
        throughputSamples.clear();
        refreshTimelineLabels(now, null, "--:--:--", "Collecting finish date...",
                "warming up", "Rolling throughput is warming up");
    }

    public static synchronized void updateTimeEstimate(int totalTasks, int completedTasks, int activeThreads) {
        if (instance == null || totalTasks <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (startMillis == 0L) {
            resetEtaTracking(now, completedTasks);
        }
        if (completedTasks < lastCompletedTasks) {
            resetEtaTracking(now, completedTasks);
        }

        if (completedTasks > lastCompletedTasks) {
            long deltaMs = Math.max(1L, now - lastEtaSampleMillis);
            int deltaTasks = completedTasks - lastCompletedTasks;
            double instantThroughput = (deltaTasks * 1000.0) / deltaMs;
            registerThroughputSample(instantThroughput);
            lastEtaSampleMillis = now;
            lastCompletedTasks = completedTasks;
        }

        long elapsedMs = Math.max(1L, now - startMillis);
        int remainingTasks = Math.max(totalTasks - completedTasks, 0);

        if (completedTasks >= totalTasks) {
            LocalDateTime doneAt = LocalDateTime.now(UI_ZONE);
            refreshTimelineLabels(startMillis, doneAt, "00:00:00",
                    "Completed on " + doneAt.format(DATE_FORMAT),
                    "100.0%", "No remaining work");
            return;
        }

        double lifetimeThroughput = (completedTasks > 0)
                ? (completedTasks * 1000.0) / elapsedMs
                : 0.0;
        double medianThroughput = getMedianThroughput();
        double representativeThroughput = chooseRepresentativeThroughput(
                lifetimeThroughput, medianThroughput, activeThreads, completedTasks);

        long remainingMs;
        if (representativeThroughput > 0.0001) {
            remainingMs = Math.round((remainingTasks / representativeThroughput) * 1000.0);
        } else {
            remainingMs = 0L;
        }

        if (remainingMs <= 0L && completedTasks > 0) {
            remainingMs = Math.max(1_000L, Math.round((remainingTasks / Math.max(lifetimeThroughput, 0.1)) * 1000.0));
        }

        LocalDateTime estimatedEnd = LocalDateTime.now(UI_ZONE).plusNanos(remainingMs * 1_000_000L);
        String remainingFormatted = formatDuration(remainingMs);
        double confidence = calculateConfidence(now, totalTasks, completedTasks, representativeThroughput);
        String confidenceText = String.format("%.1f%%", confidence);
        String confidenceHint = String.format(
                "Based on %d recent samples and %.2f node/sec",
                throughputSamples.size(), Math.max(representativeThroughput, 0.0));

        refreshTimelineLabels(startMillis, estimatedEnd, remainingFormatted,
                "Finish on " + estimatedEnd.format(DATE_FORMAT),
                confidenceText, confidenceHint);
    }

    private static synchronized void registerThroughputSample(double instantThroughput) {
        if (instantThroughput <= 0.0) {
            return;
        }
        throughputSamples.addLast(instantThroughput);
        while (throughputSamples.size() > ETA_SAMPLE_LIMIT) {
            throughputSamples.removeFirst();
        }
        if (smoothedThroughputPerSecond <= 0.0) {
            smoothedThroughputPerSecond = instantThroughput;
        } else {
            smoothedThroughputPerSecond = (smoothedThroughputPerSecond * 0.68)
                    + (instantThroughput * 0.32);
        }
    }

    private static synchronized double getMedianThroughput() {
        if (throughputSamples.isEmpty()) {
            return 0.0;
        }
        List<Double> values = new ArrayList<Double>(throughputSamples);
        Collections.sort(values);
        int middle = values.size() / 2;
        if (values.size() % 2 == 0) {
            return (values.get(middle - 1) + values.get(middle)) / 2.0;
        }
        return values.get(middle);
    }

    private static synchronized double chooseRepresentativeThroughput(double lifetimeThroughput,
            double medianThroughput, int activeThreads, int completedTasks) {
        double activeFactor = Math.max(activeThreads, 1);
        double warmupThroughput = lifetimeThroughput > 0.0
                ? lifetimeThroughput
                : smoothedThroughputPerSecond;
        if (completedTasks < (activeFactor * 2) || throughputSamples.size() < 3) {
            return warmupThroughput;
        }
        double recentThroughput = medianThroughput > 0.0
                ? medianThroughput
                : smoothedThroughputPerSecond;
        return (recentThroughput * 0.72) + (lifetimeThroughput * 0.28);
    }

    private static synchronized double calculateConfidence(long now, int totalTasks,
            int completedTasks, double representativeThroughput) {
        if (completedTasks <= 0 || representativeThroughput <= 0.0) {
            return 12.0;
        }
        double progressRatio = completedTasks / (double) Math.max(totalTasks, 1);
        double progressScore = Math.min(100.0, Math.max(18.0, progressRatio * 100.0));

        double mean = 0.0;
        for (Double sample : throughputSamples) {
            mean += sample.doubleValue();
        }
        mean = throughputSamples.isEmpty() ? 0.0 : mean / throughputSamples.size();

        double stabilityScore = 55.0;
        if (mean > 0.0 && throughputSamples.size() >= 2) {
            double variance = 0.0;
            for (Double sample : throughputSamples) {
                double diff = sample.doubleValue() - mean;
                variance += diff * diff;
            }
            variance /= throughputSamples.size();
            double stdDev = Math.sqrt(variance);
            double coefficient = stdDev / mean;
            stabilityScore = Math.max(20.0, 100.0 - (coefficient * 100.0));
        }

        double sampleScore = Math.min(100.0, throughputSamples.size() * 8.0);
        double inactivityPenalty = lastEtaSampleMillis > 0L
                ? Math.min(25.0, (now - lastEtaSampleMillis) / 1500.0)
                : 0.0;

        double confidence = (stabilityScore * 0.48)
                + (progressScore * 0.32)
                + (sampleScore * 0.20)
                - inactivityPenalty;

        return Math.max(12.0, Math.min(99.0, confidence));
    }

    private static String formatDuration(long remainingMs) {
        long totalSeconds = Math.max(0L, remainingMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static void refreshTimelineLabels(long startMs, LocalDateTime endDateTime,
            String remainingText, String remainingHintText,
            String confidenceText, String confidenceHintText) {
        SwingUtilities.invokeLater(() -> {
            if (lblStartValue != null) {
                lblStartValue.setText("Start Time: " + Instant.ofEpochMilli(startMs)
                        .atZone(UI_ZONE)
                        .toLocalDateTime()
                        .format(DATE_TIME_FORMAT));
            }
            if (lblEtaValue != null) {
                lblEtaValue.setText(endDateTime == null
                        ? "Estimated Finish: calculating..."
                        : "Estimated Finish: " + endDateTime.format(DATE_TIME_FORMAT));
            }
            if (lblRemainingValue != null) {
                lblRemainingValue.setText("Remaining: " + remainingText + " | " + remainingHintText);
                lblRemainingValue.setToolTipText(remainingHintText);
            }
            if (lblAccuracyValue != null) {
                lblAccuracyValue.setText("ETA Confidence: " + confidenceText);
                lblAccuracyValue.setForeground(colorForConfidence(confidenceText));
                lblAccuracyValue.setToolTipText(confidenceHintText);
            }
        });
    }

    private static Color colorForConfidence(String confidenceText) {
        try {
            double value = Double.parseDouble(confidenceText.replace("%", ""));
            if (value >= 85.0) {
                return new Color(74, 222, 128);
            }
            if (value >= 65.0) {
                return new Color(251, 191, 36);
            }
        } catch (NumberFormatException ignored) {
        }
        return new Color(248, 113, 113);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == btnClose) {
            requestStopWithConfirmation();
        }
    }

    private void requestStopWithConfirmation() {
        if (btnClose != null && !btnClose.isEnabled()) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Do you really want to stop BotGetLog?",
                "Confirm Stop",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            if (btnClose != null) {
                btnClose.setText("Stopping...");
                btnClose.setEnabled(false);
            }
            BotGetLog_Multi.requestShutdown("User requested graceful stop", 0);
        }
    }

    public static void attachExecutor(ThreadPoolExecutor exec) {
        currentExecutor = exec;
    }

    public static StopProgram getInstance() {
        return instance;
    }

    public static void updateProgressFromBot(double percent, int done, int total) {
        if (progressBar == null || progressLabel == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue((int) Math.max(0, Math.min(100, Math.round(percent))));
            progressBar.setString(String.format("%.1f%%", percent));
            progressLabel.setText(String.format("%d / %d nodes completed", done, total));
        });
    }

    public static synchronized void updateThreadStatusFromBot(String mode, int active, int total, double cpu) {
        if (lblThreadInfo == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> lblThreadInfo.setText(
                String.format("Mode: %s | Threads: %d active / %d total | CPU: %.1f%%",
                        mode, active, total, cpu)));
    }

    public static void updateNetworkStatus(String text, Color color) {
        if (instance == null) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (lblNetworkStatus == null) {
                return;
            }
            lblNetworkStatus.setText(normalizeNetworkStatus(text));
            lblNetworkStatus.setForeground(color);
        });

        if (Color.RED.equals(color)) {
            if (!blinking) {
                blinking = true;
                blinkThread = new Thread(() -> {
                    try {
                        boolean on = true;
                        Color dimColor = new Color(83, 28, 35);
                        while (blinking) {
                            final Color current = on ? new Color(248, 113, 113) : dimColor;
                            SwingUtilities.invokeLater(() -> {
                                if (lblNetworkStatus != null) {
                                    lblNetworkStatus.setForeground(current);
                                }
                            });
                            on = !on;
                            Thread.sleep(500);
                        }
                        SwingUtilities.invokeLater(() -> {
                            if (lblNetworkStatus != null) {
                                lblNetworkStatus.setForeground(new Color(248, 113, 113));
                            }
                        });
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }, "network-status-blink");
                blinkThread.setDaemon(true);
                blinkThread.start();
            }
        } else {
            blinking = false;
            SwingUtilities.invokeLater(() -> {
                if (lblNetworkStatus != null) {
                    lblNetworkStatus.setForeground(color);
                }
            });
        }
    }

    private static String normalizeNetworkStatus(String text) {
        if (text == null) {
            return "Network: status unavailable";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        if (normalized.startsWith("[OK]")) {
            return "Network: online " + normalized.substring(4).trim();
        }
        if (normalized.startsWith("[X]")) {
            return "Network: disconnected " + normalized.substring(3).trim();
        }
        return normalized;
    }

    private static final class GradientPanel extends JPanel {

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setPaint(new LinearGradientPaint(
                    0, 0, 0, getHeight(),
                    new float[]{0f, 0.45f, 1f},
                    new Color[]{new Color(4, 13, 25), new Color(11, 23, 42), new Color(8, 17, 31)}));
            g2.fillRect(0, 0, getWidth(), getHeight());

            g2.setColor(new Color(30, 64, 175, 48));
            g2.fillOval(-120, -80, 300, 220);
            g2.setColor(new Color(20, 184, 166, 34));
            g2.fillOval(getWidth() - 220, 20, 240, 200);
            g2.dispose();

            super.paintComponent(g);
        }

        @Override
        public boolean isOpaque() {
            return false;
        }
    }

    private static final class RoundedPanel extends JPanel {

        private final Color backgroundColor;
        private final Color borderColor;

        RoundedPanel(BorderLayout layout, Color backgroundColor, Color borderColor) {
            super(layout);
            this.backgroundColor = backgroundColor;
            this.borderColor = borderColor;
            setOpaque(false);
        }

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
            int arc = 26;
            g2.setColor(backgroundColor);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.setColor(borderColor);
            g2.setStroke(new BasicStroke(1.15f));
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
            if (width <= 0 || height <= 0) {
                return;
            }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int x = b.left;
            int y = b.top;
            int arc = height;

            g2.setColor(progressBar.getBackground());
            g2.fillRoundRect(x, y, width, height, arc, arc);

            int amountFull = getAmountFull(b, width, height);
            if (amountFull > 0) {
                Paint fill = new GradientPaint(
                        0, 0, progressBar.getForeground(),
                        width, 0, progressBar.getForeground().brighter());
                g2.setPaint(fill);
                g2.fillRoundRect(x, y, amountFull, height, arc, arc);
            }

            g2.setColor(new Color(255, 255, 255, 40));
            g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc);

            if (progressBar.isStringPainted()) {
                String text = progressBar.getString();
                g2.setFont(progressBar.getFont());
                g2.setColor(Color.WHITE);
                int textWidth = g2.getFontMetrics().stringWidth(text);
                int textX = x + (width - textWidth) / 2;
                int textY = y + ((height - g2.getFontMetrics().getHeight()) / 2)
                        + g2.getFontMetrics().getAscent();
                g2.drawString(text, textX, textY);
            }
            g2.dispose();
        }
    }
}
