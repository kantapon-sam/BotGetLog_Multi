package com.java.botgetlog.truecorp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Explicit collection mode: retain old bytes and avoid bulk log maintenance. */
final class ExistingLogPreservation {
    static final String ARGUMENT = "--link-optical-preserve-existing-logs";
    static final String ENVIRONMENT = "TRUE_LINKOPTICAL_PRESERVE_EXISTING_LOGS";
    static final String DISCARD_ARGUMENT = "--link-optical-discard-incomplete-logs";
    static final String DISCARD_ENVIRONMENT = "TRUE_LINKOPTICAL_DISCARD_INCOMPLETE_LOGS";
    private static volatile boolean enabled = environmentEnabled();
    private static volatile boolean discardIncomplete = environmentTrue(DISCARD_ENVIRONMENT);

    static boolean enabled() { return enabled; }
    static boolean discardIncomplete() { return enabled && discardIncomplete; }

    static void configure(String[] args) {
        enabled = environmentEnabled();
        discardIncomplete = environmentTrue(DISCARD_ENVIRONMENT);
        if (args != null) for (String arg : args) if (ARGUMENT.equals(arg)) enabled = true;
        if (args != null) for (String arg : args) if (DISCARD_ARGUMENT.equals(arg)) discardIncomplete = true;
        if (enabled) {
            Telnet_Multi.setBackgroundMonitorsSuppressed(true);
            System.out.println("[LOG-PRESERVE] Existing complete/duplicate logs retained; verified incomplete removal=" + discardIncomplete + "; uncertain replacements archived without overwrite.");
        }
    }

    private static boolean environmentEnabled() {
        return environmentTrue(ENVIRONMENT);
    }

    private static boolean environmentTrue(String name) {
        String value = System.getenv(name);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    static Path archive(Path source, Path directory, String baseName) throws IOException {
        Files.createDirectories(directory);
        String uniqueName = baseName.replaceFirst("(?i)\\.txt$", "") + "_preserved_" + UUID.randomUUID() + ".txt";
        Path target = directory.resolve(uniqueName);
        // ATOMIC_MOVE may replace an existing destination even without
        // REPLACE_EXISTING on some providers. Keep the no-replace guarantee.
        return moveWithoutReplacement(source, target);
    }

    static Path moveWithoutReplacement(Path source, Path target) throws IOException {
        return Files.move(source, target);
    }
}
