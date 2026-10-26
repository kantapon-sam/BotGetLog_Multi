package com.java.updater;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Startup must parse release notes without using stack proportional to text length. */
public final class AutoUpdateLongNotesRegression {
    public static void main(String[] args) throws Exception {
        Method extract = AutoUpdateManager.class.getDeclaredMethod("extractJsonString", String.class, String.class);
        extract.setAccessible(true);
        try {
            StringBuilder longNotes = new StringBuilder();
            for (int i = 0; i < 30000; i++) longNotes.append("Fix ").append("\\\"quoted\\\" text\\n");
            String raw = longNotes.toString();
            String json = "{\"body\":\"" + raw + "\",\"version\":\"1.2.21\"}";
            equal(raw, (String) extract.invoke(null, json, "body"));
            equal("1.2.21", (String) extract.invoke(null, json, "version"));
            equal("", (String) extract.invoke(null, "{\"body\":\"\"}", "body"));
            equal("", (String) extract.invoke(null, "{\"body\":\"unterminated", "body"));
            equal("", (String) extract.invoke(null, json, "missing"));
            String longName = raw.replace("\\\"", "").replace("\\n", "");
            String assets = "{\"assets\":[{\"url\":\"https://api.github.com/repos/acme/demo/releases/assets/1\","
                    + "\"name\":\"" + longName + "\"},{\"url\":\"https://api.github.com/repos/acme/demo/releases/assets/2\","
                    + "\"name\":\"wanted.zip\"}]}";
            equal("https://api.github.com/repos/acme/demo/releases/assets/2",
                    AutoUpdateManager.findGitHubAssetApiUrl(assets, "wanted.zip"));
            if (args.length > 0) {
                String actual = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
                String body = (String) extract.invoke(null, actual, "body");
                if (body.length() < 1000) throw new AssertionError("Expected retained long GitHub release response");
            }
        } catch (InvocationTargetException e) {
            throw new AssertionError("Release JSON parser failed: " + e.getCause().getClass().getSimpleName());
        }
        System.out.println("PASS AutoUpdateLongNotesRegression: long escaped notes, asset names, empty/missing/malformed strings");
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("JSON field content changed");
    }
}
