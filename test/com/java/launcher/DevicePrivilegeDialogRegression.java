package com.java.launcher;

import com.java.shared.DeviceEnableSettings;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public class DevicePrivilegeDialogRegression {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Map<String, Object> status = new DeviceEnableSettings(Files.createTempDirectory("zte-ui-")).status();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Constructor<?> ctor = DevicePrivilegeDialog.class.getDeclaredConstructor(); ctor.setAccessible(true); Object controller = ctor.newInstance();
                Method content = DevicePrivilegeDialog.class.getDeclaredMethod("content", Map.class); content.setAccessible(true);
                JPanel panel = (JPanel) content.invoke(controller, status);
                JButton add = find(panel, "Add password");
                if (countPasswords(panel) != 0 || find(panel, "Clear saved passwords") == null) throw new AssertionError("New installation must show empty settings and clear action");
                for (int i = 0; i < 5; i++) add.doClick();
                if (add.isEnabled() || countPasswords(panel) != 5) throw new AssertionError("Five-slot limit");
                find(panel, "Remove").doClick();
                if (!add.isEnabled() || countPasswords(panel) != 4) throw new AssertionError("Remove slot");
                add.doClick();
                panel.setSize(960, 800); layout(panel);
                BufferedImage image = new BufferedImage(960, 800, BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); panel.printAll(g); g.dispose();
                Path output = Paths.get(args[0]); Files.createDirectories(output.getParent()); ImageIO.write(image, "png", output.toFile());
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("PASS Windows ZTE settings: five-slot limit, remove/add, masked fields, layout rendered without opening a desktop window.");
    }
    static JButton find(Container root, String text) {
        for (Component child : root.getComponents()) { if (child instanceof JButton && text.equals(((JButton) child).getText())) return (JButton) child;
            if (child instanceof Container) { JButton found = find((Container) child, text); if (found != null) return found; } }
        return null;
    }
    static int countPasswords(Container root) {
        int count = 0; for (Component child : root.getComponents()) {
            if (child instanceof JPasswordField) { if (((JPasswordField) child).getEchoChar() == 0) throw new AssertionError("Password exposed"); count++; }
            else if (child instanceof Container) count += countPasswords((Container) child);
        } return count;
    }
    static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container) layout((Container) child); }
}
