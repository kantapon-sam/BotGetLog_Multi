package com.java.myapp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;

final class UpdatePromptDialog {

    private static final Color BG = new Color(10, 19, 34);
    private static final Color PANEL = new Color(15, 27, 45);
    private static final Color BORDER = new Color(55, 90, 155);
    private static final Color ACCENT = new Color(56, 189, 248);
    private static final Color SUCCESS = new Color(16, 185, 129);
    private static final Color TEXT = Color.WHITE;
    private static final Color MUTED = new Color(181, 195, 215);
    private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 20);
    private static final Font LABEL_FONT = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font BODY_FONT = new Font("Segoe UI", Font.PLAIN, 12);

    private UpdatePromptDialog() {
    }

    static boolean showUpdatePrompt(String currentVersion, UpdateManifest manifest) {
        final boolean[] accepted = new boolean[]{false};
        JDialog dialog = new JDialog((Frame) null, "Update Ready", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(buildPromptContent(dialog, accepted, currentVersion, manifest));
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return accepted[0];
    }

    static void showError(String title, String message) {
        showMessageDialog(title, message, new Color(239, 68, 68));
    }

    static ProgressHandle showProgress(String title, String message) {
        JDialog dialog = new JDialog((Frame) null, title, false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setUndecorated(false);

        JPanel root = createRootPanel();
        JLabel titleLabel = createLabel(title, TITLE_FONT, TEXT);
        JLabel messageLabel = createLabel(message, BODY_FONT, MUTED);
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setForeground(ACCENT);
        progressBar.setBackground(new Color(32, 49, 78));
        progressBar.setPreferredSize(new Dimension(320, 16));
        progressBar.setBorder(BorderFactory.createEmptyBorder());

        JPanel center = createSectionPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(titleLabel);
        center.add(new JLabel(" "));
        center.add(messageLabel);
        center.add(new JLabel(" "));
        center.add(progressBar);

        root.add(center, BorderLayout.CENTER);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        return new ProgressHandle(dialog, messageLabel);
    }

    private static JPanel buildPromptContent(Window dialog, boolean[] accepted,
            String currentVersion, UpdateManifest manifest) {
        JPanel root = createRootPanel();

        JPanel top = createSectionPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(createLabel("Update Available", TITLE_FONT, TEXT));
        top.add(createLabel("A new version is ready to install.", BODY_FONT, MUTED));

        JPanel versionGrid = new JPanel(new GridLayout(1, 2, 10, 0));
        versionGrid.setOpaque(false);
        versionGrid.add(buildVersionCard("Current", currentVersion, new Color(59, 130, 246)));
        versionGrid.add(buildVersionCard("Latest", manifest.getVersion(), SUCCESS));
        top.add(new JLabel(" "));
        top.add(versionGrid);

        JPanel notesPanel = createSectionPanel();
        notesPanel.setLayout(new BorderLayout(0, 8));
        notesPanel.add(createLabel("Release Notes", LABEL_FONT, TEXT), BorderLayout.NORTH);
        JTextArea notes = new JTextArea(safeNotes(manifest.getNotes()));
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.setFont(BODY_FONT);
        notes.setForeground(MUTED);
        notes.setBackground(PANEL);
        notes.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane scrollPane = new JScrollPane(notes);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER, 1, true));
        scrollPane.setPreferredSize(new Dimension(360, 110));
        notesPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        JButton later = createButton("Not Now", new Color(51, 65, 85));
        JButton install = createButton("Update Now", ACCENT);
        later.addActionListener(e -> dialog.dispose());
        install.addActionListener(e -> {
            accepted[0] = true;
            dialog.dispose();
        });
        actions.add(later);
        actions.add(install);

        root.add(top, BorderLayout.NORTH);
        root.add(notesPanel, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        return root;
    }

    private static JPanel buildVersionCard(String label, String version, Color chipColor) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setOpaque(true);
        card.setBackground(new Color(18, 33, 56));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                new EmptyBorder(10, 12, 10, 12)));
        JLabel labelText = createLabel(label, LABEL_FONT, MUTED);
        JLabel versionText = createLabel("v" + version, new Font("Segoe UI", Font.BOLD, 18), chipColor);
        versionText.setHorizontalAlignment(SwingConstants.LEFT);
        card.add(labelText, BorderLayout.NORTH);
        card.add(versionText, BorderLayout.CENTER);
        return card;
    }

    private static void showMessageDialog(String title, String message, Color accent) {
        JDialog dialog = new JDialog((Frame) null, title, true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = createRootPanel();
        JPanel center = createSectionPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(createLabel(title, TITLE_FONT, accent));
        center.add(new JLabel(" "));

        JTextArea textArea = new JTextArea(message == null ? "" : message);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(BODY_FONT);
        textArea.setForeground(MUTED);
        textArea.setBackground(PANEL);
        textArea.setBorder(new EmptyBorder(4, 4, 4, 4));
        center.add(textArea);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        JButton ok = createButton("OK", accent);
        ok.addActionListener(e -> dialog.dispose());
        actions.add(ok);

        root.add(center, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private static JPanel createRootPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(16, 16, 16, 16));
        return root;
    }

    private static JPanel createSectionPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(true);
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                new EmptyBorder(12, 12, 12, 12)));
        return panel;
    }

    private static JLabel createLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(0f);
        return label;
    }

    private static JButton createButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setForeground(Color.WHITE);
        button.setBackground(color);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setPreferredSize(new Dimension(120, 34));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        return button;
    }

    private static String safeNotes(String notes) {
        String value = notes == null ? "" : notes.trim();
        return value.isEmpty() ? "General maintenance update." : value;
    }

    static final class ProgressHandle {

        private final JDialog dialog;
        private final JLabel messageLabel;

        ProgressHandle(JDialog dialog, JLabel messageLabel) {
            this.dialog = dialog;
            this.messageLabel = messageLabel;
        }

        void updateMessage(final String message) {
            SwingUtilities.invokeLater(() -> messageLabel.setText(message));
        }

        void close() {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }
}
