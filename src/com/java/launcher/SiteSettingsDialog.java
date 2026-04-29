package com.java.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

public final class SiteSettingsDialog {

    private SiteSettingsDialog() {
    }

    public static Result showDialog(Component parent, String siteId, String siteLabel, SiteCredential currentCredential) {
        JTextField usernameField = new JTextField(24);
        JPasswordField passwordField = new JPasswordField(24);
        JCheckBox autoLoginCheck = new JCheckBox("Enable Auto Login");
        autoLoginCheck.setSelected(true);

        if (currentCredential != null) {
            usernameField.setText(currentCredential.getUsername());
            passwordField.setText(currentCredential.getPassword());
            autoLoginCheck.setSelected(currentCredential.isAutoLoginEnabled());
        }

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel intro = new JLabel("<html><b>" + escapeHtml(siteLabel) + "</b><br/>Credentials are kept locally on this PC.</html>");
        content.add(intro, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(new JLabel("Username"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(usernameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        form.add(new JLabel("Password"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        form.add(passwordField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.weightx = 1.0;
        form.add(autoLoginCheck, gbc);
        content.add(form, BorderLayout.CENTER);

        Object[] options = {"Save", "Clear Saved Login", "Cancel"};
        JOptionPane optionPane = new JOptionPane(
                content,
                JOptionPane.PLAIN_MESSAGE,
                JOptionPane.YES_NO_CANCEL_OPTION,
                null,
                options,
                options[0]
        );
        JDialog dialog = optionPane.createDialog(parent, "Site Settings");
        dialog.setModal(true);
        dialog.setVisible(true);

        Object selected = optionPane.getValue();
        if (selected == null || options[2].equals(selected)) {
            return Result.cancelled();
        }
        if (options[1].equals(selected)) {
            return Result.clearRequested(siteId);
        }

        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (username.isEmpty() || password.trim().isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Username and password are required before saving.",
                    "Missing information",
                    JOptionPane.WARNING_MESSAGE);
            return showDialog(parent, siteId, siteLabel, currentCredential);
        }

        SiteCredential credential = new SiteCredential(siteId, username, password, autoLoginCheck.isSelected());
        return Result.saved(credential);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static final class Result {

        private final boolean cancelled;
        private final boolean clearRequested;
        private final SiteCredential credential;
        private final String siteId;

        private Result(boolean cancelled, boolean clearRequested, SiteCredential credential, String siteId) {
            this.cancelled = cancelled;
            this.clearRequested = clearRequested;
            this.credential = credential;
            this.siteId = siteId;
        }

        public static Result cancelled() {
            return new Result(true, false, null, "");
        }

        public static Result clearRequested(String siteId) {
            return new Result(false, true, null, siteId);
        }

        public static Result saved(SiteCredential credential) {
            return new Result(false, false, credential, credential.getSiteId());
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isClearRequested() {
            return clearRequested;
        }

        public SiteCredential getCredential() {
            return credential;
        }

        public String getSiteId() {
            return siteId;
        }
    }
}
