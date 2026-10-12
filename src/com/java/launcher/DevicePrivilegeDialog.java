package com.java.launcher;

import com.java.shared.AppMetadata;
import com.java.shared.DeviceEnableSettings;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Local settings only: does not contact a device or synchronize with a server. */
public final class DevicePrivilegeDialog {
    private static final Color BACKGROUND = new Color(11, 20, 35), SURFACE = new Color(17, 32, 51),
            FIELD = new Color(10, 23, 40), BORDER = new Color(43, 65, 91), TEXT = new Color(230, 239, 252),
            MUTED = new Color(148, 168, 193), ACCENT = new Color(56, 205, 218);
    private static Font font(int style, int size) { return new Font("Segoe UI", style, size); }
    private static JLabel label(String text, int size, Color color) { JLabel label = new JLabel(text); label.setFont(font(Font.PLAIN, size)); label.setForeground(color); return label; }
    private static JPanel transparent(LayoutManager layout) { JPanel panel = new JPanel(layout); panel.setOpaque(false); return panel; }
    private static final class Card extends JPanel {
        Card(LayoutManager layout) { super(layout); setOpaque(false); setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(SURFACE); g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18); g.setColor(BORDER); g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 18, 18); g.dispose(); super.paintComponent(graphics);
        }
    }
    private static JButton button(String text, boolean primary) {
        JButton button = new JButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(!isEnabled() ? new Color(24, 39, 58) : primary ? (getModel().isRollover() ? new Color(100, 223, 232) : ACCENT) : (getModel().isRollover() ? new Color(36, 57, 80) : FIELD));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                if (!primary) { g.setColor(hasFocus() ? ACCENT : BORDER); g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10); }
                g.dispose(); super.paintComponent(graphics);
            }
        };
        button.setFont(font(Font.BOLD, 13)); button.setForeground(primary ? BACKGROUND : TEXT);
        button.setContentAreaFilled(false); button.setOpaque(false); button.setBorderPainted(false); button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16)); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return button;
    }
    private static JPasswordField passwordField() {
        JPasswordField input = new JPasswordField(21) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(FIELD); g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g.setColor(hasFocus() ? ACCENT : BORDER); g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10); g.dispose(); super.paintComponent(graphics);
                if (getDocument().getLength() == 0 && Boolean.TRUE.equals(getClientProperty("savedPassword"))) {
                    Graphics2D hint = (Graphics2D) graphics.create();
                    hint.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    hint.setColor(MUTED); hint.setFont(font(Font.PLAIN, 15));
                    hint.drawString("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022", getInsets().left, (getHeight()-hint.getFontMetrics().getHeight())/2+hint.getFontMetrics().getAscent()); hint.dispose();
                }
            }
        };
        input.setOpaque(false); input.setFont(font(Font.PLAIN, 15)); input.setForeground(TEXT); input.setCaretColor(ACCENT);
        input.setSelectionColor(new Color(28, 94, 111)); input.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12)); return input;
    }
    private final DeviceEnableSettings store = new DeviceEnableSettings(AppMetadata.getLauncherDataDirectory().toPath());
    private final List<ProfilePanel> panels = new ArrayList<ProfilePanel>();
    private String revision;
    private JDialog dialog;

    private DevicePrivilegeDialog() { }
    public static void showDialog(Frame parent) {
        DevicePrivilegeDialog controller = new DevicePrivilegeDialog();
        try {
            Map<String, Object> status = controller.store.status();
            controller.dialog = new JDialog(parent, "Bot Settings - ZTE Enable Passwords", true);
            controller.dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            controller.dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) { controller.close(); }
                @Override public void windowDeactivated(java.awt.event.WindowEvent event) { for (ProfilePanel panel : controller.panels) for (PasswordRow row : panel.rows) { row.password.setEchoChar('\u2022'); row.show.setText("Show"); } }
            });
            controller.dialog.setContentPane(controller.content(status));
            controller.dialog.pack(); controller.dialog.setMinimumSize(new Dimension(900, 650));
            controller.dialog.setLocationRelativeTo(parent); controller.dialog.setVisible(true);
        } catch (Exception ex) { JOptionPane.showMessageDialog(parent, "Unable to open device privilege settings. Check local file access.", "Settings unavailable", JOptionPane.ERROR_MESSAGE); }
    }
    private void close() {
        if (panels.stream().anyMatch(panel -> panel.dirty) && JOptionPane.showConfirmDialog(dialog, "Discard unsaved changes?", "Close settings", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        dialog.dispose();
    }
    @SuppressWarnings("unchecked")
    private JPanel content(Map<String, Object> status) {
        revision = (String) status.get("revision");
        JPanel root = new JPanel(new BorderLayout(0, 22)); root.setBackground(BACKGROUND);
        root.setPreferredSize(new Dimension(960, 800)); root.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));
        JPanel header = transparent(new BorderLayout(12, 0)); JPanel title = transparent(new GridLayout(0, 1, 0, 7));
        JLabel kicker = label("BOT SETTINGS  /  THIS COMPUTER", 11, ACCENT); kicker.setFont(font(Font.BOLD, 11)); title.add(kicker);
        JLabel heading = label("ZTE Enable passwords", 27, TEXT); heading.setFont(font(Font.BOLD, 27)); title.add(heading);
        title.add(label("Set the order used to enter privileged mode on your ZTE devices.", 13, MUTED));
        header.add(title, BorderLayout.CENTER); root.add(header, BorderLayout.NORTH);
        for (Map<String, Object> profile : (List<Map<String, Object>>) status.get("profiles")) {
            ProfilePanel panel = new ProfilePanel(profile); panels.add(panel); root.add(panel, BorderLayout.CENTER);
        }
        return root;
    }
    private final class PasswordRow {
        final String id;
        final JPasswordField password = passwordField();
        final JButton show = button("Show", false);
        PasswordRow(String id) { this.id = id; password.putClientProperty("savedPassword", !id.isEmpty()); password.setToolTipText(id.isEmpty() ? "Enter new password" : "Password is already set. Use Show to view it, or type to replace it."); }
    }
    private final class ProfilePanel extends JPanel {
        final String vendor;
        final List<PasswordRow> rows = new ArrayList<PasswordRow>();
        final JPanel list = new JPanel();
        final JComboBox<Integer> attempts = new JComboBox<Integer>(new Integer[]{1, 2, 3, 4, 5});
        final JButton add = button("Add password", false);
        final JLabel count = label("", 12, MUTED);
        final JLabel message = label(" ", 12, MUTED);
        boolean dirty;
        ProfilePanel(Map<String, Object> profile) {
            super(new BorderLayout(0, 16)); vendor = (String) profile.get("vendor"); setOpaque(false);
            Card passwords = new Card(new BorderLayout(0, 14));
            JPanel top = transparent(new BorderLayout()); JPanel caption = transparent(new FlowLayout(FlowLayout.LEFT, 0, 6));
            JLabel priority = label("Password priority", 16, TEXT); priority.setFont(font(Font.BOLD, 16)); caption.add(priority); caption.add(Box.createHorizontalStrut(16)); caption.add(count);
            top.add(caption, BorderLayout.WEST); top.add(add, BorderLayout.EAST); passwords.add(top, BorderLayout.NORTH);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS)); list.setOpaque(false);
            JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setOpaque(false); scroll.getViewport().setOpaque(false); scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0)); scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() { thumbColor = BORDER; trackColor = SURFACE; }
                @Override protected JButton createDecreaseButton(int orientation) { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
                @Override protected JButton createIncreaseButton(int orientation) { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
            });
            passwords.add(scroll, BorderLayout.CENTER);
            passwords.add(label("First match wins. Leave saved fields blank to keep their values.", 12, MUTED), BorderLayout.SOUTH);
            add(passwords, BorderLayout.CENTER);
            JPanel bottom = transparent(new BorderLayout(0, 14));
            Card options = new Card(new BorderLayout(18, 0)); JPanel description = transparent(new GridLayout(0, 1, 0, 5));
            JLabel limit = label("Attempt limit", 14, TEXT); limit.setFont(font(Font.BOLD, 14)); description.add(limit);
            description.add(label("Default: try the first 2 passwords, once each per connection.", 12, MUTED)); options.add(description, BorderLayout.CENTER);
            attempts.setFont(font(Font.BOLD, 15)); attempts.setForeground(TEXT); attempts.setBackground(FIELD); attempts.setBorder(BorderFactory.createLineBorder(BORDER)); attempts.setPreferredSize(new Dimension(82,42));
            attempts.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                @Override protected JButton createArrowButton() {
                    JButton b = new JButton() {
                        @Override protected void paintComponent(Graphics graphics) {
                            Graphics2D g = (Graphics2D) graphics.create();
                            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g.setColor(TEXT); int x = getWidth()/2, y = getHeight()/2;
                            g.fillPolygon(new int[]{x-4,x+4,x}, new int[]{y-2,y-2,y+3}, 3); g.dispose();
                        }
                    };
                    b.setContentAreaFilled(false); b.setBorder(BorderFactory.createEmptyBorder(2,9,2,9));
                    b.getAccessibleContext().setAccessibleName("Choose attempt limit"); return b;
                }
            });
            options.add(attempts, BorderLayout.EAST); bottom.add(options, BorderLayout.NORTH);
            JPanel footer = transparent(new BorderLayout(0, 14)); footer.add(message, BorderLayout.NORTH);
            JPanel buttons = transparent(new BorderLayout()); JButton reset = button("Clear saved passwords", false); buttons.add(reset, BorderLayout.WEST);
            JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0)); JButton cancel = button("Cancel", false), save = button("Save changes", true); actions.add(cancel); actions.add(save); buttons.add(actions, BorderLayout.EAST);
            footer.add(buttons, BorderLayout.CENTER); footer.add(label("LOCAL SETTINGS   Changes apply to new connections. Active connections continue unchanged.", 11, MUTED), BorderLayout.SOUTH);
            bottom.add(footer, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH);
            add.addActionListener(event -> { rows.add(new PasswordRow("")); dirty = true; render(); });
            attempts.addActionListener(event -> { dirty = true; message.setText((Integer) attempts.getSelectedItem() > 2 ? "Check device lockout policy before allowing more than 2 attempts." : "Unsaved changes."); });
            save.addActionListener(event -> save(false)); reset.addActionListener(event -> save(true)); cancel.addActionListener(event -> close()); populate(profile);
        }
        @SuppressWarnings("unchecked")
        void populate(Map<String, Object> profile) {
            rows.clear(); for (Map<String, String> entry : (List<Map<String, String>>) profile.get("entries")) rows.add(new PasswordRow(entry.get("id")));
            attempts.setSelectedItem(profile.get("maxAttempts")); dirty = false; message.setText(rows.isEmpty() ? "No passwords saved. Add a password and save before using ZTE Enable." : "Saved passwords are hidden. Maximum 5 slots."); render();
        }
        void render() {
            list.removeAll(); count.setText(rows.size() + " of 5 slots");
            for (PasswordRow row : rows) {
                int index = rows.indexOf(row); JPanel line = transparent(new GridBagLayout());
                line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56)); line.setPreferredSize(new Dimension(820,56));
                GridBagConstraints c = new GridBagConstraints(); c.gridy = 0; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(6,0,6,8);
                JPanel identity = transparent(new GridLayout(0, 1, 0, 4)); JLabel name = label(index == 0 ? "01  Primary" : String.format("%02d  Fallback %d", index+1, index), 13, index == 0 ? ACCENT : TEXT); name.setFont(font(Font.BOLD,13)); identity.add(name);
                identity.add(label(row.id.isEmpty() ? "New password" : "Saved / hidden", 11, MUTED)); identity.setPreferredSize(new Dimension(125,44));
                c.gridx=0; line.add(identity,c); c.gridx=1; c.weightx=1; line.add(row.password,c); c.weightx=0; c.gridx=2; line.add(row.show,c);
                if (row.show.getActionListeners().length == 0) {
                    row.password.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                        public void insertUpdate(javax.swing.event.DocumentEvent event) { dirty = true; message.setText("Unsaved changes."); }
                        public void removeUpdate(javax.swing.event.DocumentEvent event) { dirty = true; message.setText("Unsaved changes."); }
                        public void changedUpdate(javax.swing.event.DocumentEvent event) { dirty = true; }
                    });
                    row.show.addActionListener(event -> {
                        try {
                            boolean reveal = row.password.getEchoChar() != 0;
                            if (reveal && row.password.getPassword().length == 0 && !row.id.isEmpty()) { boolean wasDirty = dirty; row.password.setText(store.reveal(vendor, Integer.parseInt(row.id), revision)); dirty = wasDirty; }
                            row.password.setEchoChar(reveal ? (char) 0 : '\u2022'); row.show.setText(reveal ? "Hide" : "Show");
                        } catch (Exception ex) { message.setText("Unable to show saved password. Close and reopen settings."); }
                    });
                }
                JButton up = button("\u2191", false), down = button("\u2193", false), remove = button("Remove", false);
                up.setToolTipText("Move up"); down.setToolTipText("Move down"); up.getAccessibleContext().setAccessibleName("Move up"); down.getAccessibleContext().setAccessibleName("Move down"); remove.setForeground(new Color(236,153,161));
                up.setEnabled(index > 0); down.setEnabled(index < rows.size() - 1);
                up.addActionListener(event -> { Collections.swap(rows, rows.indexOf(row), rows.indexOf(row)-1); dirty = true; message.setText("Unsaved changes."); render(); });
                down.addActionListener(event -> { Collections.swap(rows, rows.indexOf(row), rows.indexOf(row)+1); dirty = true; message.setText("Unsaved changes."); render(); });
                remove.addActionListener(event -> { rows.remove(row); dirty = true; message.setText("Unsaved changes."); render(); });
                c.gridx=3; line.add(up,c); c.gridx=4; line.add(down,c); c.gridx=5; c.insets=new Insets(6,0,6,0); line.add(remove,c); list.add(line);
            }
            add.setEnabled(rows.size() < 5); list.revalidate(); list.repaint();
        }
        @SuppressWarnings("unchecked")
        void save(boolean reset) {
            if (JOptionPane.showConfirmDialog(dialog, reset || rows.isEmpty() ? "Clear all saved " + vendor + " Enable passwords? New connections will have no Enable passwords to try." : "Save " + vendor + " privilege passwords for this installation?", "Save settings", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            List<Map<String, String>> entries = new ArrayList<Map<String, String>>();
            for (PasswordRow row : rows) { Map<String, String> entry = new HashMap<String, String>(); entry.put("id", row.id); char[] chars = row.password.getPassword(); entry.put("password", new String(chars)); Arrays.fill(chars, '\0'); entries.add(entry); }
            try {
                Map<String, Object> status = store.save(vendor, revision, entries, (Integer) attempts.getSelectedItem(), reset); revision = (String) status.get("revision");
                for (Map<String, Object> profile : (List<Map<String, Object>>) status.get("profiles")) if (vendor.equals(profile.get("vendor"))) populate(profile);
                message.setText(rows.isEmpty() ? "No passwords saved. Add a password and save before using ZTE Enable." : "Saved. New node connections use these settings.");
            } catch (IllegalArgumentException ex) { message.setText(ex.getMessage()); }
            catch (Exception ex) { message.setText("Unable to save. Check local file access; your edits are retained."); }
        }
    }
}
