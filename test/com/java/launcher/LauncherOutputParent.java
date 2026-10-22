package com.java.launcher;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Invokes the production private method without constructor network/UI work. */
public final class LauncherOutputParent {
    public static void main(String[] args) throws Exception {
        final Path probeDir = Paths.get(args[0]).toAbsolutePath();
        final boolean exitAfterDispose = "exit".equals(args[1]);
        final Class<?> launcherType = Class.forName("com.java.launcher.BotToolLauncher");
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        final Object launcher = unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, launcherType);
        final Method launch = launcherType.getDeclaredMethod("launchProgram",
                File.class, String.class, JButton.class, String[].class);
        launch.setAccessible(true);
        Files.write(probeDir.resolve("parent-pid.txt"),
                ManagementFactory.getRuntimeMXBean().getName().split("@")[0].getBytes(StandardCharsets.UTF_8));
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Isolated launcher regression probe");
            try {
                frame.setUndecorated(true);
                frame.setSize(1, 1);
                frame.addNotify(); // Hidden throughout; gives dispose a real native peer to close.
                if (!frame.isDisplayable()) throw new AssertionError("Frame was not displayable");
                Field frameField = launcherType.getDeclaredField("frame");
                frameField.setAccessible(true);
                frameField.set(launcher, frame);
                Field textField = launcherType.getDeclaredField("textArea");
                textField.setAccessible(true);
                textField.set(launcher, new JTextArea());
                String[] childArgs = {"--probe-dir", probeDir.toString(), "two words", "  trimmed-value  ", "", null};
                launch.invoke(launcher, probeDir.resolve("NoiseProbe.jar").toFile(),
                        "NoiseProbe.jar", new JButton("Probe"), childArgs);
                if (frame.isDisplayable()) throw new AssertionError("Production method did not dispose the frame");
                Files.write(probeDir.resolve("parent-disposed.txt"), "disposed".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                frame.dispose();
            }
        });
        if (exitAfterDispose) System.exit(0);
        // Keep the ProcessBuilder pipe handles alive after frame disposal, as
        // happens when other launcher activity still owns a thread.
        long deadline = System.nanoTime() + 25_000_000_000L;
        while (System.nanoTime() < deadline && !Files.exists(probeDir.resolve("release-parent.txt"))) {
            Thread.sleep(50);
        }
        System.exit(0);
    }
}
