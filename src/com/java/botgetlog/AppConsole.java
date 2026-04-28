package com.java.botgetlog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;

public final class AppConsole {

    private static final int MAX_CHARS = 250000;
    private static final Object LOCK = new Object();
    private static JFrame frame;
    private static JTextArea textArea;
    private static boolean installed;

    private AppConsole() {
    }

    public static void install() {
        synchronized (LOCK) {
            if (installed) {
                show();
                return;
            }
            installed = true;
        }

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(createPrintStream(originalOut, false));
        System.setErr(createPrintStream(originalErr, true));
        show();
    }

    public static PrintStream createPrintStream(OutputStream original, boolean errorStream) {
        return new PrintStream(new ConsoleOutputStream(original, errorStream), true);
    }

    public static void show() {
        SwingUtilities.invokeLater(() -> {
            ensureFrame();
            frame.setVisible(true);
            frame.toFront();
        });
    }

    private static void ensureFrame() {
        if (frame != null) {
            return;
        }

        textArea = new JTextArea(28, 110);
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setBackground(Color.BLACK);
        textArea.setForeground(new Color(230, 230, 230));
        textArea.setCaretColor(Color.WHITE);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        DefaultCaret caret = (DefaultCaret) textArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JButton copyButton = new JButton("Copy");
        styleActionButton(copyButton, new Color(31, 111, 235), Color.WHITE);
        copyButton.addActionListener(e -> {
            StringSelection selection = new StringSelection(textArea.getText());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        });

        JButton clearButton = new JButton("Clear");
        styleActionButton(clearButton, new Color(48, 63, 84), Color.WHITE);
        clearButton.addActionListener(e -> textArea.setText(""));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        actions.setBackground(new Color(235, 235, 235));
        actions.add(copyButton);
        actions.add(clearButton);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        frame = new JFrame("BotGetLog Multi - Console");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(actions, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private static void styleActionButton(JButton button, Color background, Color foreground) {
        button.setFocusPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setBackground(background);
        button.setForeground(foreground);
        button.setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
    }

    private static void append(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            ensureFrame();
            textArea.append(value);
            int extra = textArea.getDocument().getLength() - MAX_CHARS;
            if (extra > 0) {
                try {
                    textArea.getDocument().remove(0, extra);
                } catch (javax.swing.text.BadLocationException ignored) {
                }
            }
        });
    }

    private static final class ConsoleOutputStream extends OutputStream {

        private final OutputStream original;
        private final boolean errorStream;

        ConsoleOutputStream(OutputStream original, boolean errorStream) {
            this.original = original;
            this.errorStream = errorStream;
        }

        @Override
        public void write(int b) throws IOException {
            byte[] one = new byte[]{(byte) b};
            write(one, 0, one.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (original != null) {
                original.write(b, off, len);
            }
            String text = new String(b, off, len);
            append(text);
        }

        @Override
        public void flush() throws IOException {
            if (original != null) {
                original.flush();
            }
        }
    }
}

