package com.java.shared;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Narrow read-only command alternatives verified on supported network devices. */
public final class DeviceCommandFallback {
    private DeviceCommandFallback() { }

    private static String safeTrim(String value) { return value == null ? "" : value.trim(); }
    private static boolean hasCommandError(String value) {
        return value.toLowerCase(Locale.ROOT).matches(".*(%error\\s*[0-9:]*|invalid input detected|bad command|unrecognized command|unknown command|syntax error|command not found|invalid command).*" );
    }
    private static final Pattern HUAWEI_MEMORY_REJECTION = Pattern.compile(
            "(?mi)^\\s*Error: Unrecognized command found at '\\^' position\\.\\s*$");

    public static boolean shouldFallbackHuaweiMemory(String command, String response) {
        return "display memory-usage".equalsIgnoreCase(safeTrim(command))
                && response != null && HUAWEI_MEMORY_REJECTION.matcher(response).find();
    }

    public static String commandFallback(String vendor, String command, String response) {
        return "HW".equals(vendor) && shouldFallbackHuaweiMemory(command, response) ? "display memory" : "";
    }

    public static boolean hasValidFallbackResponse(String vendor, String response) {
        if (response == null) return false;
        for (String line : response.split("\\r?\\n")) {
            if (hasCommandError(line) || line.contains("[TIMEOUT")
                    || line.trim().matches("(?i)^(?:%Error|Error:).*")) return false;
        }
        if (!"HW".equals(vendor)) return false;
        Double total = memoryValue(response, "(?:System Total Memory(?: Is)?|Total Physical Available Memory)", "Kbytes");
        Double used = memoryValue(response, "(?:Total Memory Used(?: Is)?|Total Physical Memory Used)", "Kbytes");
        Double percent = memoryValue(response, "(?:Physical )?Memory Using Percentage(?: Is)?", "%");
        return total != null && total > 0 && used != null && used >= 0 && used <= total
                && percent != null && percent >= 0 && percent <= 100;
    }

    private static Double memoryValue(String response, String label, String unit) {
        Matcher match = Pattern.compile("(?mi)^\\s*" + label + "\\s*:\\s*([\\d.,]+)\\s*" + unit + "\\s*$").matcher(response);
        if (!match.find()) return null;
        try { return Double.valueOf(match.group(1).replace(",", "")); }
        catch (NumberFormatException invalid) { return null; }
    }

}
