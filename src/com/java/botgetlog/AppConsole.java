package com.java.botgetlog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
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

    public static void close() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.setVisible(false);
                frame.dispose();
                frame = null;
                textArea = null;
            }
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

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        frame = new JFrame("BotGetLog Multi - Console");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
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

