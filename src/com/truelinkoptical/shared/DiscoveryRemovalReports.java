package com.truelinkoptical.shared;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Seed a workbook from pre-policy sync reports before either collector can reinsert an old IP. */
public final class DiscoveryRemovalReports {
    private static final Pattern REPORT = Pattern.compile("node-userinput-sync-\\d{8}-\\d{6}\\.csv");
    private static final Pattern ENTRY = Pattern.compile("^([a-z-]+),(?:\"(?:[^\"]|\"\")*\"|[^,]*),((?:\\d{1,3}\\.){3}\\d{1,3}),");
    private static final Pattern MAC = Pattern.compile("baseMac=([a-fA-F0-9:]{17})(?: |$)");

    private DiscoveryRemovalReports() {}

    public static boolean importOnce(File directory, Set<String> inventory, DiscoveryHistory history) throws IOException {
        if (history.importedReports() || directory == null || !directory.isDirectory()) return false;
        File[] reports = directory.listFiles((dir, name) -> REPORT.matcher(name).matches());
        if (reports == null) throw new IOException("Cannot read sync reports in " + directory);
        Arrays.sort(reports);
        Map<String, String> removed = new HashMap<>();
        for (File file : reports) {
            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("removed-") && !line.startsWith("added,")) continue;
                    Matcher entry = ENTRY.matcher(line);
                    if (!entry.find()) continue;
                    String ip = entry.group(2);
                    if (inventory.contains(ip) || history.recordedAt(ip) != 0) continue;
                    removed.remove(ip);
                    if ("removed-unreachable-orphan".equals(entry.group(1))) {
                        removed.put(ip, "");
                    } else if ("removed-duplicate-device".equals(entry.group(1))) {
                        Matcher mac = MAC.matcher(line);
                        if (mac.find()) removed.put(ip, "mac:" + mac.group(1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        for (Map.Entry<String, String> entry : removed.entrySet()) {
            String identity = entry.getValue();
            history.remember(entry.getKey(), identity.isEmpty() ? "unreachable" : "duplicate", identity);
        }
        history.finishImport();
        return true;
    }
}
