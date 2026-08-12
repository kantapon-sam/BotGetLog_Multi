package com.java.updater;

public final class AutoUpdateManagerRegression {

    private AutoUpdateManagerRegression() {
    }

    public static void main(String[] args) {
        String json = "{\"assets\":["
                + "{\"url\":\"https://api.github.com/repos/acme/demo/releases/assets/101\","
                + "\"name\":\"other.zip\"},"
                + "{\"url\":\"https://api.github.com/repos/acme/demo/releases/assets/202\","
                + "\"name\":\"BotGetLog_Multi-dist-1.1.43.zip\"}]}";

        assertEquals(
                "https://api.github.com/repos/acme/demo/releases/assets/202",
                AutoUpdateManager.findGitHubAssetApiUrl(
                        json, "BotGetLog_Multi-dist-1.1.43.zip"));
        assertEquals("", AutoUpdateManager.findGitHubAssetApiUrl(json, "missing.zip"));
        assertEquals("", AutoUpdateManager.findGitHubAssetApiUrl("", "missing.zip"));
        System.out.println("PASS AutoUpdateManagerRegression");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }
}
