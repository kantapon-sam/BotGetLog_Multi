package com.java.botgetlog.dtac;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;

public final class AppConsole {

    private static final int MAX_CHARS = 250000;
    private static final Object LOCK = new Object();
    private static final long MIN_VISIBLE_MS = 1200L;
    private static JFrame frame;
    private static JTextArea textArea;
    private static boolean installed;
    private static long shownAtMs;

    private AppConsole() {
    }

    public static void install() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
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
        return new PrintStream(new ConsoleOutputStream(original), true);
    }

    public static void show() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        runOnEdtAndWait(() -> {
            ensureFrame();
            if (!frame.isVisible()) {
                frame.setVisible(true);
                shownAtMs = System.currentTimeMillis();
            }
            frame.toFront();
        });
    }

    public static void close() {
        close(false);
    }

    public static void closeNow() {
        close(true);
    }

    private static void close(boolean immediate) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        long delayMs = 0L;
        if (!immediate) {
            synchronized (LOCK) {
                if (shownAtMs > 0L) {
                    long elapsed = System.currentTimeMillis() - shownAtMs;
                    delayMs = Math.max(0L, MIN_VISIBLE_MS - elapsed);
                }
            }
        }

        Runnable closeTask = () -> {
            if (frame != null) {
                frame.setVisible(false);
                frame.dispose();
                frame = null;
                textArea = null;
            }
            synchronized (LOCK) {
                shownAtMs = 0L;
            }
        };

        if (delayMs <= 0L) {
            SwingUtilities.invokeLater(closeTask);
            return;
        }

        final long closeDelayMs = delayMs;
        new Thread(() -> {
            try {
                Thread.sleep(closeDelayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            SwingUtilities.invokeLater(closeTask);
        }, "dtac-app-console-close").start();
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

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(new Color(18, 31, 49));
        toolbar.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JLabel title = new JLabel("BotGetLog [DTAC] Console");
        title.setForeground(new Color(110, 231, 255));
        title.setFont(new Font("Segoe UI", Font.BOLD, 13));

        toolbar.add(title, BorderLayout.WEST);

        frame = new JFrame("BotGetLog [DTAC] - Console");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(toolbar, BorderLayout.NORTH);
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private static void append(String value) {
        if (value == null || value.isEmpty() || GraphicsEnvironment.isHeadless()) {
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

    private static void runOnEdtAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ex) {
            throw new IllegalStateException("Unable to show DTAC app console", ex.getCause());
        }
    }

    private static final class ConsoleOutputStream extends OutputStream {

        private final OutputStream original;

        ConsoleOutputStream(OutputStream original) {
            this.original = original;
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
            append(new String(b, off, len));
        }

        @Override
        public void flush() throws IOException {
            if (original != null) {
                original.flush();
            }
        }
    }
}
