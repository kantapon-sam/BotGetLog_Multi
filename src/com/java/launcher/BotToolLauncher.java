package com.java.launcher;

import com.java.shared.AppMetadata;
import com.java.updater.AutoUpdateManager;
import java.awt.Desktop;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.SocketException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

public class BotToolLauncher {

    private static final String BOT_JAR_NAME = "BotGetLog_TrueCorp.jar";
    private static final String BOT_DTAC_JAR_NAME = "BotGetLog_DTAC.jar";
    private static final String LINK_OPTICAL_JAR_NAME = "Link_Optical.jar";
    private static final String ARP_JAR_NAME = "ARP.jar";
    private static final String PTP_JAR_NAME = "PTP.jar";
    private static final String OUTPUT_DIR = "_output";
    // Version 1.1.2: Refresh build metadata, launcher release notes, and package artifacts for
    // version 1.1.2.
    private static final String FALLBACK_VERSION = "1.1.2";
    private static final int WEB_PING_TIMEOUT_MS = 800;
    private static final String JAVA_INITIAL_HEAP = "-Xms256m";
    private static final String JAVA_MAX_HEAP = "-Xmx2048m";
    private static final String JAVA_GC_OPTION = "-XX:+UseG1GC";
    private static final Color PANEL_BACKGROUND = new Color(245, 247, 250);
    private static final Color WEB_PANEL_BACKGROUND = new Color(231, 239, 253);
    private static final Color CARD_BORDER = new Color(205, 214, 225);
    private static final Color TOOL_BUTTON_BACKGROUND = new Color(44, 62, 80);
    private static final Color TOOL_BUTTON_FOREGROUND = Color.WHITE;
    private static final Color TRUE_BUTTON_BACKGROUND = new Color(190, 48, 48);
    private static final Color DTAC_BUTTON_BACKGROUND = new Color(56, 189, 248);
    private static final Color WEB_BUTTON_BACKGROUND = new Color(21, 101, 192);
    private static final Color WEB_BUTTON_FOREGROUND = Color.WHITE;
    private static final Color LOGIN_BUTTON_BACKGROUND = new Color(30, 64, 110);
    private static final Color SECONDARY_BUTTON_BACKGROUND = new Color(234, 236, 240);
    private static final Color SECONDARY_BUTTON_FOREGROUND = new Color(42, 52, 64);
    private static final int SIDE_PANEL_WIDTH = 360;
    private static final String[] VPN_KEYWORDS = {
        "juniper", "pulse", "pangp", "globalprotect", "forti", "forticlient",
        "cisco", "anyconnect", "wireguard", "openvpn", "zscaler", "checkpoint", "vpn"
    };
    private static final List<WebTarget> WEB_TARGETS = Arrays.asList(
            WebTarget.routed("zenicone_nea", "ZenicOne_NEA",
                    "https://10.35.228.158:28001/portal-athena/",
                    "https://10.11.2.77:28001/portal-athena/"),
            WebTarget.routed("zenicone_cw", "ZenicOne_C&W",
                    "https://10.35.227.124:28001/portal-athena/",
                    "https://10.50.179.11:28001/portal-athena/"),
            WebTarget.routed("nce", "NCE",
                    "https://10.35.228.22:31943/",
                    "https://10.50.174.15:31943/"),
            WebTarget.routed("pms", "PMS",
                    "https://10.35.228.115/login",
                    "https://10.50.238.85/login"),
            WebTarget.routed("planwork", "Planwork",
                    "http://10.35.224.243/maximo/",
                    "http://10.50.90.191/maximo/",
                    "https://maximo.truecorp.co.th/maximo"),
            WebTarget.routed("tuar", "TUAR",
                    "http://10.35.224.175/tuar/",
                    "http://10.50.64.186/tuar/"),
            WebTarget.routed("fr_planwork", "FR-Planwork",
                    "http://10.35.227.82/FR-Planwork/",
                    "http://10.50.90.36/FR-Planwork/"),
            WebTarget.routed("corp", "Corp",
                    "http://10.35.227.82/CorpInvNew/",
                    "http://10.50.90.36/CorpInvNew/"),
            WebTarget.shared("cacti_dtac", "Cacti_Dtac",
                    "http://192.168.127.46/"),
            WebTarget.shared("cerberus", "Cerberus",
                    "https://cerberus.dtacnetwork.co.th/",
                    "https://cerberus-dr.dtacnetwork.co.th/"),
            WebTarget.shared("trueconnect", "Trueconnect",
                    "https://trueconnect.ekoapp.com/"),
            WebTarget.shared("proms", "Proms",
                    "http://promsweb.true.th/proms/login",
                    "https://proms.truecorp.co.th/proms/"),
            WebTarget.shared("atts", "ATTS",
                    "https://atts.truecorp.co.th/"),
            WebTarget.shared("itsm", "ITSM",
                    "https://ticketing-eu.managed-services.prod.sdt.ericsson.net/arsys"),
            WebTarget.shared("change_password_clls", "Change password CLLS",
                    "http://10.50.90.170/logincenter/")
    );

    private final CredentialStore credentialStore = new CredentialStore();
    private final LoginRecipeRegistry loginRecipeRegistry = new LoginRecipeRegistry();

    private JFrame frame;
    private JTextArea textArea;
    private JButton launchBotButton;
    private JButton launchDtacButton;
    private JButton linkOpticalButton;
    private JButton arpButton;
    private JButton ptpButton;
    private JButton connectVpnButton;
    private JButton resetButton;
    private JButton exitButton;
    private JButton refreshLinksButton;
    private JPanel webButtonsPanel;
    private JLabel webStatusLabel;
    private String vpnStatusSummary;
    private List<ResolvedWebTarget> lastResolvedTargets = Collections.emptyList();

    private void show() {
        setLookAndFeel();
        ensureFrame();
        showRedirectNoticeIfAny();
        frame.setVisible(true);
        frame.toFront();
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
        } catch (Exception ignored) {
        }
    }

    private void ensureFrame() {
        if (frame != null) {
            return;
        }

        vpnStatusSummary = getVpnStatusSummary();

        textArea = new JTextArea(24, 92);
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setBackground(Color.BLACK);
        textArea.setForeground(new Color(230, 230, 230));
        textArea.setCaretColor(Color.WHITE);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        textArea.setText(buildLauncherText());

        launchBotButton = new LauncherButton("BotGetLog [TRUE]");
        launchDtacButton = new LauncherButton("BotGetLog [DTAC]");
        linkOpticalButton = new LauncherButton("Link Optical");
        arpButton = new LauncherButton("ARP");
        ptpButton = new LauncherButton("PTP");
        connectVpnButton = new LauncherButton("Open Pulse VPN");
        resetButton = new LauncherButton("Reset");
        exitButton = new LauncherButton("Exit");
        refreshLinksButton = new LauncherButton("Refresh Links");

        launchBotButton.addActionListener(e -> launchBotJar());
        launchDtacButton.addActionListener(e -> launchDtacJar());
        linkOpticalButton.addActionListener(e -> launchLinkOpticalJar());
        arpButton.addActionListener(e -> launchArpJar());
        ptpButton.addActionListener(e -> launchPtpJar());
        connectVpnButton.addActionListener(e -> connectPulseVpn());
        resetButton.addActionListener(e -> resetGeneratedFiles());
        exitButton.addActionListener(e -> frame.dispose());
        refreshLinksButton.addActionListener(e -> refreshWebLinksAsync());
        styleToolButton(launchBotButton, TRUE_BUTTON_BACKGROUND);
        styleToolButton(launchDtacButton, DTAC_BUTTON_BACKGROUND);
        styleToolButton(linkOpticalButton);
        styleToolButton(arpButton);
        styleToolButton(ptpButton);
        styleToolButton(connectVpnButton);
        styleSecondaryButton(resetButton);
        styleSecondaryButton(exitButton);
        styleSecondaryButton(refreshLinksButton);

        JPanel actionListPanel = new JPanel();
        actionListPanel.setLayout(new BoxLayout(actionListPanel, BoxLayout.Y_AXIS));
        actionListPanel.setBackground(PANEL_BACKGROUND);
        actionListPanel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JPanel actionButtonsPanel = new JPanel(new GridLayout(0, 1, 0, 8));
        actionButtonsPanel.setOpaque(false);
        actionButtonsPanel.add(launchBotButton);
        actionButtonsPanel.add(launchDtacButton);
        actionButtonsPanel.add(linkOpticalButton);
        actionButtonsPanel.add(arpButton);
        actionButtonsPanel.add(ptpButton);
        actionButtonsPanel.add(connectVpnButton);
        actionButtonsPanel.add(resetButton);
        actionButtonsPanel.add(exitButton);

        actionListPanel.add(createToolSectionPanel(actionButtonsPanel));
        actionListPanel.add(Box.createVerticalStrut(16));
        actionListPanel.add(createWebLinksPanel());
        actionListPanel.add(Box.createVerticalGlue());

        JScrollPane sideScrollPane = new JScrollPane(
                actionListPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        sideScrollPane.setBorder(BorderFactory.createEmptyBorder());
        sideScrollPane.getViewport().setBackground(PANEL_BACKGROUND);
        sideScrollPane.setBackground(PANEL_BACKGROUND);
        sideScrollPane.setPreferredSize(new Dimension(SIDE_PANEL_WIDTH, 0));
        sideScrollPane.getVerticalScrollBar().setUnitIncrement(18);

        frame = new JFrame("Bot Tool Launcher - Console");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(new JScrollPane(textArea), BorderLayout.CENTER);
        frame.add(sideScrollPane, BorderLayout.EAST);
        frame.setPreferredSize(new Dimension(1160, 680));
        frame.pack();
        frame.setLocationRelativeTo(null);

        refreshWebLinksAsync();
    }

    private String buildLauncherText() {
        String lineBreak = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        text.append("[INFO] Bot Tool Launcher initialized").append(lineBreak);
        text.append("[INFO] Current version: ").append(getCurrentVersion()).append(lineBreak);
        text.append("[VPN] ").append(vpnStatusSummary == null ? "Not detected" : vpnStatusSummary).append(lineBreak);
        text.append("[INFO] Select a menu from the buttons below.").append(lineBreak);
        text.append(lineBreak);
        text.append("1. BotGetLog [TRUE]").append(lineBreak);
        text.append("   Run the TRUE bot directly without extra menu.").append(lineBreak);
        text.append(lineBreak);
        text.append("2. BotGetLog [DTAC]").append(lineBreak);
        text.append("   Run the DTAC SSH bot directly without extra menu.").append(lineBreak);
        text.append(lineBreak);
        text.append("3. Link Optical").append(lineBreak);
        text.append("   Open the built-in Link_Optical tool from this project.").append(lineBreak);
        text.append(lineBreak);
        text.append("4. ARP").append(lineBreak);
        text.append("   Open the built-in ARP tool from this project.").append(lineBreak);
        text.append(lineBreak);
        text.append("5. PTP").append(lineBreak);
        text.append("   Open the built-in PTP tool from this project.").append(lineBreak);
        text.append(lineBreak);
        text.append("6. Open Pulse VPN").append(lineBreak);
        text.append("   Open Pulse Secure and bring its window to the front so you can click Connect there.").append(lineBreak);
        text.append(lineBreak);
        text.append("7. Reset").append(lineBreak);
        text.append("   Delete generated log/output files in this bot folder.").append(lineBreak);
        text.append(lineBreak);
        text.append("8. Exit").append(lineBreak);
        text.append("   Close this launcher.").append(lineBreak);
        text.append(lineBreak);
        text.append("[PATH] ").append(getAppDirectory().getAbsolutePath()).append(lineBreak);
        return text.toString();
    }

    private JPanel createToolSectionPanel(JPanel actionButtonsPanel) {
        JPanel toolPanel = createCardPanel(Color.WHITE);
        toolPanel.setAlignmentX(JPanel.CENTER_ALIGNMENT);
        toolPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel actionTitle = new JLabel("Tool Menu");
        actionTitle.setFont(new Font("Segoe UI", Font.BOLD, 15));
        actionTitle.setAlignmentX(JLabel.CENTER_ALIGNMENT);

        actionButtonsPanel.setAlignmentX(JPanel.CENTER_ALIGNMENT);

        toolPanel.add(actionTitle);
        toolPanel.add(Box.createVerticalStrut(12));
        toolPanel.add(actionButtonsPanel);
        return toolPanel;
    }

    private JPanel createWebLinksPanel() {
        JPanel webPanel = createCardPanel(WEB_PANEL_BACKGROUND);
        webPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(193, 210, 235)),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));
        webPanel.setAlignmentX(JPanel.CENTER_ALIGNMENT);
        webPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel webTitle = new JLabel("Web Shortcuts");
        webTitle.setFont(new Font("Segoe UI", Font.BOLD, 15));
        webTitle.setAlignmentX(JLabel.CENTER_ALIGNMENT);

        webStatusLabel = new JLabel("Checking reachable links...");
        webStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        webStatusLabel.setForeground(new Color(58, 76, 97));
        webStatusLabel.setAlignmentX(JLabel.CENTER_ALIGNMENT);

        webButtonsPanel = new JPanel();
        webButtonsPanel.setLayout(new BoxLayout(webButtonsPanel, BoxLayout.Y_AXIS));
        webButtonsPanel.setOpaque(false);
        webButtonsPanel.setAlignmentX(JPanel.CENTER_ALIGNMENT);

        refreshLinksButton.setAlignmentX(JButton.CENTER_ALIGNMENT);

        webPanel.add(webTitle);
        webPanel.add(Box.createVerticalStrut(8));
        webPanel.add(webStatusLabel);
        webPanel.add(Box.createVerticalStrut(10));
        webPanel.add(webButtonsPanel);
        webPanel.add(Box.createVerticalStrut(10));
        webPanel.add(refreshLinksButton);
        return webPanel;
    }

    private void refreshWebLinksAsync() {
        if (webStatusLabel != null) {
            webStatusLabel.setText("Checking reachable links...");
        }
        if (refreshLinksButton != null) {
            refreshLinksButton.setEnabled(false);
        }
        new Thread(() -> {
            String preferredGroup = detectPreferredWebGroup();
            List<ResolvedWebTarget> resolvedTargets = resolveWebTargets(preferredGroup);
            SwingUtilities.invokeLater(() -> updateWebLinksPanel(resolvedTargets));
        }, "web-link-refresh").start();
    }

    private void updateWebLinksPanel(List<ResolvedWebTarget> resolvedTargets) {
        if (webButtonsPanel == null || webStatusLabel == null) {
            return;
        }

        lastResolvedTargets = new ArrayList<ResolvedWebTarget>(resolvedTargets);
        webButtonsPanel.removeAll();
        webStatusLabel.setText(buildWebStatusText(resolvedTargets));
        for (ResolvedWebTarget target : resolvedTargets) {
            webButtonsPanel.add(createWebShortcutRow(target));
            webButtonsPanel.add(Box.createVerticalStrut(8));
        }

        webButtonsPanel.revalidate();
        webButtonsPanel.repaint();
        if (refreshLinksButton != null) {
            refreshLinksButton.setEnabled(true);
        }
    }

    private String buildWebStatusText(List<ResolvedWebTarget> resolvedTargets) {
        int availableCount = 0;
        for (ResolvedWebTarget target : resolvedTargets) {
            if (target.available) {
                availableCount++;
            }
        }
        return "Available links: " + availableCount + " / " + resolvedTargets.size();
    }

    private JPanel createWebShortcutRow(ResolvedWebTarget target) {
        JPanel rowPanel = createCardPanel(Color.WHITE);
        rowPanel.setAlignmentX(JPanel.CENTER_ALIGNMENT);
        rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel titleLabel = new JLabel(target.label);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        titleLabel.setAlignmentX(JLabel.LEFT_ALIGNMENT);

        SiteCredential savedCredential = credentialStore.load(target.siteId);
        JPanel actionPanel = new JPanel();
        actionPanel.setLayout(new BoxLayout(actionPanel, BoxLayout.Y_AXIS));
        actionPanel.setOpaque(false);
        actionPanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        JPanel primaryActionPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        primaryActionPanel.setOpaque(false);
        primaryActionPanel.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        JButton openButton = new LauncherButton("Open");
        styleSmallActionButton(openButton, WEB_BUTTON_BACKGROUND, WEB_BUTTON_FOREGROUND);
        openButton.setEnabled(target.available);
        openButton.setToolTipText(target.available ? target.url : "Unavailable: ping failed on both routes");
        openButton.addActionListener(e -> openWebLink(target));

        JButton autoLoginButton = new LauncherButton(savedCredential != null && savedCredential.hasUsableCredential()
                ? "Auto Login" : "Setup");
        styleSmallActionButton(autoLoginButton, LOGIN_BUTTON_BACKGROUND, TOOL_BUTTON_FOREGROUND);
        autoLoginButton.setEnabled(target.available);
        autoLoginButton.setToolTipText(target.available
                ? "Open Chrome and fill saved credentials"
                : "Unavailable: ping failed on both routes");
        autoLoginButton.addActionListener(e -> handleAutoLogin(target));

        JButton settingsButton = new LauncherButton("Credentials");
        styleSmallActionButton(settingsButton, SECONDARY_BUTTON_BACKGROUND, SECONDARY_BUTTON_FOREGROUND);
        settingsButton.setToolTipText("Save username/password for " + target.label);
        settingsButton.addActionListener(e -> handleSiteSettings(target));
        settingsButton.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        primaryActionPanel.add(openButton);
        primaryActionPanel.add(autoLoginButton);

        actionPanel.add(primaryActionPanel);
        actionPanel.add(Box.createVerticalStrut(8));
        actionPanel.add(settingsButton);

        rowPanel.add(titleLabel);
        rowPanel.add(Box.createVerticalStrut(4));
        rowPanel.add(actionPanel);
        return rowPanel;
    }

    private void handleSiteSettings(ResolvedWebTarget target) {
        SiteSettingsDialog.Result result = SiteSettingsDialog.showDialog(
                frame,
                target.siteId,
                target.label,
                credentialStore.load(target.siteId)
        );
        if (result.isCancelled()) {
            return;
        }
        if (result.isClearRequested()) {
            credentialStore.clear(result.getSiteId());
            appendLine("[LOGIN] Cleared saved credential for " + target.label);
        } else if (result.getCredential() != null) {
            credentialStore.save(result.getCredential());
            appendLine("[LOGIN] Saved credential for " + target.label);
        }
        rerenderWebLinks();
    }

    private void handleAutoLogin(ResolvedWebTarget target) {
        if (!target.available) {
            JOptionPane.showMessageDialog(
                    frame,
                    target.label + " is unavailable right now.\nPing failed on both routes.",
                    "Link unavailable",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        SiteCredential credential = credentialStore.load(target.siteId);
        if (credential == null || !credential.hasUsableCredential()) {
            SiteSettingsDialog.Result result = SiteSettingsDialog.showDialog(
                    frame,
                    target.siteId,
                    target.label,
                    credential
            );
            if (result.isCancelled() || result.isClearRequested() || result.getCredential() == null) {
                return;
            }
            credential = result.getCredential();
            credentialStore.save(credential);
            rerenderWebLinks();
            appendLine("[LOGIN] Saved credential for " + target.label);
        }

        appendLine("[LOGIN] Auto Login started in Chrome for " + target.label);
        AutoLoginLauncher.launch(frame, target.siteId, target.label, target.url);
    }

    private void rerenderWebLinks() {
        if (lastResolvedTargets == null || lastResolvedTargets.isEmpty()) {
            return;
        }
        updateWebLinksPanel(new ArrayList<ResolvedWebTarget>(lastResolvedTargets));
    }

    private void openWebLink(ResolvedWebTarget target) {
        appendLine("[WEB] Opening " + target.label + " -> " + target.url);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(target.url));
                return;
            }
        } catch (Exception ignored) {
        }

        try {
            new ProcessBuilder("cmd", "/c", "start", "", target.url).start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Unable to open link\n" + target.url + "\n" + ex.getMessage(),
                    "Open link failed",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private static String detectPreferredWebGroup() {
        String pulseUri = detectPulseConnectedUri();
        if (pulseUri == null || pulseUri.trim().isEmpty()) {
            return null;
        }

        String normalized = pulseUri.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("apollo")) {
            return "apollo";
        }
        if (normalized.contains("pegasus")) {
            return "pegasus";
        }
        return null;
    }

    private static List<ResolvedWebTarget> resolveWebTargets(String preferredGroup) {
        List<ResolvedWebTarget> resolvedTargets = new ArrayList<>();
        for (WebTarget target : WEB_TARGETS) {
            resolvedTargets.add(resolveWebTarget(target, preferredGroup));
        }
        return resolvedTargets;
    }

    private static ResolvedWebTarget resolveWebTarget(WebTarget target, String preferredGroup) {
        List<WebCandidate> candidates = buildCandidateOrder(target, preferredGroup);
        for (WebCandidate candidate : candidates) {
            if (canPingUrl(candidate.url)) {
                return new ResolvedWebTarget(target.siteId, target.label, candidate.url, true);
            }
        }
        WebCandidate fallback = candidates.isEmpty() ? new WebCandidate(target.apolloUrl) : candidates.get(0);
        return new ResolvedWebTarget(target.siteId, target.label, fallback.url, false);
    }

    private static List<WebCandidate> buildCandidateOrder(WebTarget target, String preferredGroup) {
        LinkedHashSet<String> orderedUrls = new LinkedHashSet<String>();
        if ("pegasus".equalsIgnoreCase(preferredGroup)) {
            addCandidateUrl(orderedUrls, target.pegasusUrl);
            addCandidateUrl(orderedUrls, target.apolloUrl);
        } else {
            addCandidateUrl(orderedUrls, target.apolloUrl);
            addCandidateUrl(orderedUrls, target.pegasusUrl);
        }

        for (String extraUrl : target.extraUrls) {
            addCandidateUrl(orderedUrls, extraUrl);
        }

        List<WebCandidate> candidates = new ArrayList<WebCandidate>(orderedUrls.size());
        for (String url : orderedUrls) {
            candidates.add(new WebCandidate(url));
        }
        return candidates;
    }

    private static void addCandidateUrl(Set<String> orderedUrls, String url) {
        if (orderedUrls == null || url == null || url.trim().isEmpty()) {
            return;
        }
        orderedUrls.add(url.trim());
    }

    private static boolean canPingUrl(String urlText) {
        try {
            String host = new URL(urlText).getHost();
            if (host == null || host.trim().isEmpty()) {
                return false;
            }

            Process process = new ProcessBuilder(
                    "ping", "-n", "1", "-w", String.valueOf(WEB_PING_TIMEOUT_MS), host
            ).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Drain output so the ping process cannot block on a full buffer.
                }
            }
            return process.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static JPanel createCardPanel(Color background) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(true);
        panel.setBackground(background);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
        ));
        return panel;
    }

    private static void styleToolButton(JButton button) {
        styleButton(button, TOOL_BUTTON_BACKGROUND, TOOL_BUTTON_FOREGROUND, 42, new Font("Segoe UI", Font.BOLD, 13));
    }

    private static void styleToolButton(JButton button, Color background) {
        styleButton(button, background, TOOL_BUTTON_FOREGROUND, 42, new Font("Segoe UI", Font.BOLD, 13));
    }

    private static void styleSecondaryButton(JButton button) {
        styleButton(button, SECONDARY_BUTTON_BACKGROUND, SECONDARY_BUTTON_FOREGROUND, 38, new Font("Segoe UI", Font.BOLD, 12));
    }

    private static void styleSmallActionButton(JButton button, Color background, Color foreground) {
        styleButton(button, background, foreground, 36, new Font("Segoe UI", Font.BOLD, 11));
        button.setHorizontalAlignment(JButton.CENTER);
        button.setPreferredSize(new Dimension(120, 36));
        button.setMinimumSize(new Dimension(80, 36));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
    }

    private static void styleButton(JButton button, Color background, Color foreground, int preferredHeight, Font font) {
        button.setFont(font);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(10, 14, 10, 14));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferredHeight));
        button.setPreferredSize(new Dimension(SIDE_PANEL_WIDTH - 80, preferredHeight));
        button.setMinimumSize(new Dimension(220, preferredHeight));
        button.setAlignmentX(JComponent.CENTER_ALIGNMENT);
    }

    private static Color mixColors(Color first, Color second, float ratio) {
        if (first == null) {
            return second == null ? Color.GRAY : second;
        }
        if (second == null) {
            return first;
        }
        float clamped = Math.max(0f, Math.min(1f, ratio));
        float inverse = 1f - clamped;
        int red = Math.round(first.getRed() * inverse + second.getRed() * clamped);
        int green = Math.round(first.getGreen() * inverse + second.getGreen() * clamped);
        int blue = Math.round(first.getBlue() * inverse + second.getBlue() * clamped);
        return new Color(red, green, blue);
    }

    private static Color shiftColor(Color base, float amount) {
        if (base == null) {
            return Color.GRAY;
        }
        if (amount == 0f) {
            return base;
        }
        if (amount > 0f) {
            return mixColors(base, Color.WHITE, amount);
        }
        return mixColors(base, Color.BLACK, -amount);
    }

    private static String getVpnStatusSummary() {
        String pulseUri = detectPulseConnectedUri();
        if (pulseUri != null && !pulseUri.isEmpty()) {
            return "Connected: " + pulseUri;
        }

        String adapterSummary = detectVpnFromNetworkInterfaces();
        if (adapterSummary != null && !adapterSummary.isEmpty()) {
            return "Connected: " + adapterSummary;
        }

        String processSummary = detectVpnFromProcesses();
        if (processSummary != null && !processSummary.isEmpty()) {
            return "Client running: " + processSummary;
        }

        return "Not detected";
    }

    private static String detectPulseConnectedUri() {
        PulseConnectionState state = readLatestPulseConnectionState();
        if (state == null || !state.connected) {
            return null;
        }

        String uri = state.uri;
        if (uri == null || uri.trim().isEmpty()) {
            if (state.connectionId == null || state.connectionId.isEmpty()) {
                return null;
            }

            Map<String, String> uriById = readPulseConnectionUris();
            uri = uriById.get(state.connectionId);
            if (uri == null || uri.trim().isEmpty()) {
                return null;
            }
        }

        uri = uri.trim();
        if (uri.startsWith("https://")) {
            return uri.substring("https://".length());
        }
        if (uri.startsWith("http://")) {
            return uri.substring("http://".length());
        }
        return uri;
    }

    private static PulseConnectionState readLatestPulseConnectionState() {
        File logFile = new File("C:\\ProgramData\\Pulse Secure\\Logging\\debuglog.log");
        if (!logFile.isFile()) {
            return null;
        }

        String currentId = null;
        String lastConnectedId = null;
        String lastConnectedUri = null;
        boolean connected = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int channelIndex = line.indexOf("on_ChannelComplete - ");
                if (channelIndex >= 0) {
                    currentId = line.substring(channelIndex + "on_ChannelComplete - ".length()).trim();
                }

                String extractedId = extractPulseConnectionId(line);
                if (extractedId != null && !extractedId.isEmpty()) {
                    currentId = extractedId;
                }

                int uriIndex = line.indexOf("Connected to an SA (Network Connect) uri ");
                if (uriIndex >= 0) {
                    String suffix = line.substring(uriIndex + "Connected to an SA (Network Connect) uri ".length()).trim();
                    int viaIndex = suffix.indexOf(" via ");
                    if (viaIndex >= 0) {
                        suffix = suffix.substring(0, viaIndex).trim();
                    }
                    if (!suffix.isEmpty()) {
                        lastConnectedUri = suffix;
                        connected = true;
                    }
                    if (currentId != null && !currentId.isEmpty()) {
                        lastConnectedId = currentId;
                    }
                }

                if (line.contains("Connection Status: Connected")) {
                    if (currentId != null && !currentId.isEmpty()) {
                        lastConnectedId = currentId;
                    }
                    connected = true;
                }

                if (line.contains("Connection Status: Disconnecting")
                        || line.contains("Connection Status: Disconnected")
                        || line.contains("Tray state updated - State: No active connections")) {
                    connected = false;
                    lastConnectedUri = null;
                }
            }
        } catch (IOException ex) {
            return null;
        }

        if (!connected) {
            return new PulseConnectionState(false, null, null);
        }

        return new PulseConnectionState(true, lastConnectedId, lastConnectedUri);
    }

    private static String extractPulseConnectionId(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        String[] prefixes = {
            "on_ChannelComplete - ",
            "(ive:",
            "iveAccessMethod:",
            "ive:",
            "userdata:"
        };

        for (String prefix : prefixes) {
            int start = line.indexOf(prefix);
            if (start < 0) {
                continue;
            }

            start += prefix.length();
            int end = start;
            while (end < line.length()) {
                char ch = line.charAt(end);
                if (Character.isLetterOrDigit(ch)) {
                    end++;
                    continue;
                }
                break;
            }
            if (end > start) {
                return line.substring(start, end).trim();
            }
        }

        return null;
    }

    private static final class PulseConnectionState {

        final boolean connected;
        final String connectionId;
        final String uri;

        PulseConnectionState(boolean connected, String connectionId, String uri) {
            this.connected = connected;
            this.connectionId = connectionId == null ? "" : connectionId.trim();
            this.uri = uri == null ? "" : uri.trim();
        }
    }

    private static Map<String, String> readPulseConnectionUris() {
        File connStore = new File("C:\\ProgramData\\Pulse Secure\\ConnectionStore\\connstore.dat");
        if (!connStore.isFile()) {
            return Collections.emptyMap();
        }

        Map<String, String> uriById = new HashMap<>();
        String currentId = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(connStore)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("ive \"")) {
                    int start = trimmed.indexOf('"');
                    int end = trimmed.indexOf('"', start + 1);
                    if (start >= 0 && end > start) {
                        currentId = trimmed.substring(start + 1, end);
                    }
                    continue;
                }

                if (currentId != null && trimmed.startsWith("uri: \"")) {
                    int start = trimmed.indexOf('"');
                    int end = trimmed.lastIndexOf('"');
                    if (start >= 0 && end > start) {
                        uriById.put(currentId, trimmed.substring(start + 1, end));
                    }
                    continue;
                }

                if (trimmed.equals("}")) {
                    currentId = null;
                }
            }
        } catch (IOException ex) {
            return Collections.emptyMap();
        }

        return uriById;
    }

    private static String detectVpnFromNetworkInterfaces() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }

            List<String> matches = new ArrayList<>();
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!isPossibleVpnInterface(networkInterface)) {
                    continue;
                }

                String vendor = inferVpnVendor(networkInterface.getName(), networkInterface.getDisplayName());
                String ipv4 = getIpv4Address(networkInterface);
                String displayName = networkInterface.getDisplayName();
                String interfaceName = networkInterface.getName();
                StringBuilder summary = new StringBuilder();
                summary.append(vendor);
                if (displayName != null && !displayName.trim().isEmpty()) {
                    summary.append(" via ").append(displayName.trim());
                    if (interfaceName != null && !interfaceName.trim().isEmpty()
                            && !displayName.trim().equalsIgnoreCase(interfaceName.trim())) {
                        summary.append(" [").append(interfaceName.trim()).append("]");
                    }
                } else if (interfaceName != null && !interfaceName.trim().isEmpty()) {
                    summary.append(" via ").append(interfaceName.trim());
                }
                if (ipv4 != null && !ipv4.isEmpty()) {
                    summary.append(" (").append(ipv4).append(")");
                }
                matches.add(summary.toString());
            }

            if (matches.isEmpty()) {
                return null;
            }
            return String.join(", ", matches);
        } catch (SocketException ex) {
            return null;
        }
    }

    private static boolean isPossibleVpnInterface(NetworkInterface networkInterface) throws SocketException {
        if (networkInterface == null || !networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
            return false;
        }

        String combinedName = ((networkInterface.getName() == null ? "" : networkInterface.getName()) + " "
                + (networkInterface.getDisplayName() == null ? "" : networkInterface.getDisplayName()))
                .toLowerCase(Locale.ENGLISH);

        boolean keywordMatch = false;
        for (String keyword : VPN_KEYWORDS) {
            if (combinedName.contains(keyword)) {
                keywordMatch = true;
                break;
            }
        }
        if (!keywordMatch) {
            return false;
        }

        return getIpv4Address(networkInterface) != null;
    }

    private static String getIpv4Address(NetworkInterface networkInterface) {
        Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
        while (inetAddresses.hasMoreElements()) {
            InetAddress address = inetAddresses.nextElement();
            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                return address.getHostAddress();
            }
        }
        return null;
    }

    private static String inferVpnVendor(String name, String displayName) {
        String combined = ((name == null ? "" : name) + " " + (displayName == null ? "" : displayName))
                .toLowerCase(Locale.ENGLISH);
        if (combined.contains("juniper") || combined.contains("pulse")) {
            return "Juniper/Pulse";
        }
        if (combined.contains("pangp") || combined.contains("globalprotect")) {
            return "GlobalProtect";
        }
        if (combined.contains("forti")) {
            return "FortiClient";
        }
        if (combined.contains("anyconnect") || combined.contains("cisco")) {
            return "Cisco AnyConnect";
        }
        if (combined.contains("wireguard")) {
            return "WireGuard";
        }
        if (combined.contains("openvpn")) {
            return "OpenVPN";
        }
        if (combined.contains("zscaler")) {
            return "Zscaler";
        }
        if (combined.contains("checkpoint")) {
            return "Check Point";
        }
        return displayName != null && !displayName.trim().isEmpty() ? displayName.trim() : "VPN";
    }

    private static String detectVpnFromProcesses() {
        Set<String> detected = new LinkedHashSet<>();
        Process process = null;
        try {
            process = new ProcessBuilder("tasklist").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String normalized = line.toLowerCase(Locale.ENGLISH);
                    if (normalized.contains("pangp") || normalized.contains("globalprotect")) {
                        detected.add("GlobalProtect");
                    } else if (normalized.contains("pulse") || normalized.contains("juniper")) {
                        detected.add("Juniper/Pulse");
                    } else if (normalized.contains("forti")) {
                        detected.add("FortiClient");
                    } else if (normalized.contains("anyconnect") || normalized.contains("cisco")) {
                        detected.add("Cisco AnyConnect");
                    } else if (normalized.contains("wireguard")) {
                        detected.add("WireGuard");
                    } else if (normalized.contains("openvpn")) {
                        detected.add("OpenVPN");
                    } else if (normalized.contains("zscaler")) {
                        detected.add("Zscaler");
                    } else if (normalized.contains("checkpoint")) {
                        detected.add("Check Point");
                    }
                }
            }
            process.waitFor();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        if (detected.isEmpty()) {
            return null;
        }
        return String.join(", ", detected);
    }

    private static String getCurrentVersion() {
        Package appPackage = BotToolLauncher.class.getPackage();
        if (appPackage != null) {
            String implementationVersion = appPackage.getImplementationVersion();
            if (implementationVersion != null && !implementationVersion.trim().isEmpty()) {
                return implementationVersion.trim();
            }
        }
        return FALLBACK_VERSION;
    }

    private void launchBotJar() {
        launchBotButton.setEnabled(false);
        launchProgram(findBotJar(), BOT_JAR_NAME, launchBotButton);
    }

    private void launchDtacJar() {
        launchDtacButton.setEnabled(false);
        launchProgram(findDtacJar(), BOT_DTAC_JAR_NAME, launchDtacButton);
    }

    private void launchLinkOpticalJar() {
        linkOpticalButton.setEnabled(false);
        launchProgram(findLinkOpticalJar(), LINK_OPTICAL_JAR_NAME, linkOpticalButton);
    }

    private void launchArpJar() {
        arpButton.setEnabled(false);
        launchProgram(findArpJar(), ARP_JAR_NAME, arpButton);
    }

    private void launchPtpJar() {
        ptpButton.setEnabled(false);
        launchProgram(findPtpJar(), PTP_JAR_NAME, ptpButton);
    }

    private void connectPulseVpn() {
        connectVpnButton.setEnabled(false);
        appendLine("[VPN] Opening Pulse Secure...");
        new Thread(() -> {
            PulseSecureAutomation.ConnectResult result = null;
            try {
                if (!PulseSecureAutomation.isAvailable()) {
                    result = PulseSecureAutomation.ConnectResult.error("Pulse Secure client was not found on this machine.");
                    return;
                }
                result = PulseSecureAutomation.openPulse();
            } catch (Exception ex) {
                result = PulseSecureAutomation.ConnectResult.error("Open Pulse VPN failed: " + ex.getMessage());
            } finally {
                final PulseSecureAutomation.ConnectResult finalResult = result;
                SwingUtilities.invokeLater(() -> {
                    connectVpnButton.setEnabled(true);
                    vpnStatusSummary = getVpnStatusSummary();
                    textArea.setText(buildLauncherText());
                    if (finalResult != null) {
                        appendLine("[VPN] " + finalResult.getMessage());
                        if (!finalResult.isSuccess()) {
                            JOptionPane.showMessageDialog(
                                    frame,
                                    finalResult.getMessage(),
                                    "Pulse Secure",
                                    JOptionPane.WARNING_MESSAGE
                            );
                        }
                    }
                    refreshWebLinksAsync();
                });
            }
        }, "pulse-vpn-connect").start();
    }

    private void launchProgram(File jarFile, String displayName, JButton sourceButton) {
        if (jarFile == null || !jarFile.isFile()) {
            if (sourceButton != null) {
                sourceButton.setEnabled(true);
            }
            JOptionPane.showMessageDialog(
                    frame,
                    "Cannot find " + displayName,
                    "Program file not found",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        appendLine("[RUN] Starting " + jarFile.getName());
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    getJavaLauncher(),
                    JAVA_INITIAL_HEAP,
                    JAVA_MAX_HEAP,
                    JAVA_GC_OPTION,
                    "-D" + LauncherGate.INVOKED_BY_LAUNCHER_PROPERTY + "=true",
                    "-jar",
                    jarFile.getAbsolutePath()
            );
            File workingDir = jarFile.getParentFile();
            if (workingDir != null) {
                processBuilder.directory(workingDir);
            }
            processBuilder.start();
            frame.dispose();
        } catch (IOException ex) {
            if (sourceButton != null) {
                sourceButton.setEnabled(true);
            }
            appendLine("[ERROR] Launch failed: " + ex.getMessage());
            JOptionPane.showMessageDialog(
                    frame,
                    "Unable to start " + displayName + "\n" + ex.getMessage(),
                    "Launch failed",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void showRedirectNoticeIfAny() {
        String requestedTool = System.getProperty(LauncherGate.REQUESTED_TOOL_PROPERTY, "").trim();
        if (requestedTool.isEmpty()) {
            return;
        }
        appendLine("[INFO] Direct launch of " + requestedTool + " is disabled.");
        appendLine("[INFO] Please start " + requestedTool + " from the Tool Menu on the right.");
    }

    private void resetGeneratedFiles() {
        Object[] options = {"Yes", "No"};
        int answer = JOptionPane.showOptionDialog(
                frame,
                "Delete files in _output\\Total_Log now?",
                "Confirm Reset",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[1]
        );

        if (answer != JOptionPane.YES_OPTION) {
            appendLine("[RESET] Cancelled by user.");
            return;
        }

        File outputDir = new File(getAppDirectory(), OUTPUT_DIR);
        File totalLogDir = new File(outputDir, "Total_Log");

        int deletedCount = 0;
        deletedCount += deleteContents(totalLogDir);

        outputDir.mkdirs();
        totalLogDir.mkdirs();

        appendLine("[RESET] Completed. Deleted items: " + deletedCount);
        JOptionPane.showMessageDialog(
                frame,
                "Reset complete\nDeleted items: " + deletedCount,
                "Reset finished",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void appendLine(String text) {
        if (textArea == null) {
            return;
        }
        textArea.append(text + System.lineSeparator());
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    private static int deleteContents(File target) {
        if (target == null || !target.exists()) {
            return 0;
        }

        int deletedCount = 0;
        File[] children = target.listFiles();
        if (children == null) {
            return 0;
        }

        for (File child : children) {
            deletedCount += deleteRecursively(child);
        }
        return deletedCount;
    }

    private static int deleteRecursively(File target) {
        int deletedCount = 0;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deletedCount += deleteRecursively(child);
                }
            }
        }

        if (target.delete()) {
            deletedCount++;
        }
        return deletedCount;
    }

    private static String getJavaLauncher() {
        String javaHome = System.getProperty("java.home", "");
        File javaw = new File(javaHome, "bin\\javaw.exe");
        if (javaw.isFile()) {
            return javaw.getAbsolutePath();
        }

        File java = new File(javaHome, "bin\\java.exe");
        if (java.isFile()) {
            return java.getAbsolutePath();
        }

        return "java";
    }

    private static File findBotJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, BOT_JAR_NAME),
            new File(appDir, "dist\\" + BOT_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[0];
    }

    private static File findDtacJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, BOT_DTAC_JAR_NAME),
            new File(appDir, "dist\\" + BOT_DTAC_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[0];
    }

    private static File findLinkOpticalJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, LINK_OPTICAL_JAR_NAME),
            new File(appDir, "dist\\" + LINK_OPTICAL_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    private static File findArpJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, ARP_JAR_NAME),
            new File(appDir, "dist\\" + ARP_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    private static File findPtpJar() {
        File appDir = getAppDirectory();
        File[] candidates = new File[]{
            new File(appDir, PTP_JAR_NAME),
            new File(appDir, "dist\\" + PTP_JAR_NAME)
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    private static File getAppDirectory() {
        try {
            CodeSource codeSource = BotToolLauncher.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return new File(".").getCanonicalFile();
            }

            URL location = codeSource.getLocation();
            File file = new File(location.toURI()).getCanonicalFile();
            if (file.isFile()) {
                File parent = file.getParentFile();
                return parent != null ? parent : new File(".").getCanonicalFile();
            }
            String normalized = file.getAbsolutePath().replace('/', '\\').toLowerCase();
            if (normalized.endsWith("\\build\\classes")) {
                File buildDir = file.getParentFile();
                File projectDir = buildDir != null ? buildDir.getParentFile() : null;
                if (projectDir != null && projectDir.isDirectory()) {
                    return projectDir.getCanonicalFile();
                }
            }
            return file;
        } catch (IOException | URISyntaxException ex) {
            return new File(".").getAbsoluteFile();
        }
    }

    public static void main(String[] args) {
        if (!AppMetadata.isRunningFromIde() && AutoUpdateManager.checkForUpdatesAtStartup()) {
            return;
        }
        SwingUtilities.invokeLater(() -> new BotToolLauncher().show());
    }

    private static final class WebTarget {

        private final String siteId;
        private final String label;
        private final String pegasusUrl;
        private final String apolloUrl;
        private final List<String> extraUrls;

        private WebTarget(String siteId, String label, String pegasusUrl, String apolloUrl, String... extraUrls) {
            this.siteId = siteId;
            this.label = label;
            this.pegasusUrl = pegasusUrl;
            this.apolloUrl = apolloUrl;
            if (extraUrls == null || extraUrls.length == 0) {
                this.extraUrls = Collections.emptyList();
            } else {
                this.extraUrls = Collections.unmodifiableList(Arrays.asList(extraUrls));
            }
        }

        private static WebTarget routed(String siteId, String label, String pegasusUrl, String apolloUrl, String... extraUrls) {
            return new WebTarget(siteId, label, pegasusUrl, apolloUrl, extraUrls);
        }

        private static WebTarget shared(String siteId, String label, String sharedUrl, String... extraUrls) {
            return new WebTarget(siteId, label, sharedUrl, sharedUrl, extraUrls);
        }
    }

    private static final class WebCandidate {

        private final String url;

        private WebCandidate(String url) {
            this.url = url;
        }
    }

    private static final class ResolvedWebTarget {

        private final String siteId;
        private final String label;
        private final String url;
        private final boolean available;

        private ResolvedWebTarget(String siteId, String label, String url, boolean available) {
            this.siteId = siteId;
            this.label = label;
            this.url = url;
            this.available = available;
        }
    }

    private static final class LauncherButton extends JButton {

        private LauncherButton(String text) {
            super(text);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color base = getBackground();
            Color fill = isEnabled() ? base : mixColors(base, PANEL_BACKGROUND, 0.55f);
            if (isEnabled() && getModel().isPressed()) {
                fill = shiftColor(base, -0.18f);
            } else if (isEnabled() && getModel().isRollover()) {
                fill = shiftColor(base, 0.08f);
            }

            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.setColor(shiftColor(fill, -0.22f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.dispose();

            super.paintComponent(graphics);
        }
    }
}
