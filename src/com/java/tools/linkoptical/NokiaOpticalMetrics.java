package com.java.tools.linkoptical;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optical values for one Nokia physical port, shared by CSV and live parsing. */
final class NokiaOpticalMetrics {
    private static final Pattern WAVELENGTH = Pattern.compile(
            "^TX Laser Wavelength\\s*:\\s*([0-9]+(?:\\.[0-9]+)?\\s*nm)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANES = Pattern.compile("^Number of Lanes\\s*:\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("^([+-]?[0-9]+(?:\\.[0-9]+)?)(?:[!*]+|/[HL]-[WA]+)?$");
    private final Map<Integer, String[]> laneValues = new LinkedHashMap<Integer, String[]>();
    private String wavelength = "", tx = "", rx = "", lowWarn = "", highWarn = "";
    private int laneCount = 4; // Older output omits the count for four-lane optics.
    private boolean inLaneTable;

    void accept(String rawLine) {
        String line = rawLine.trim();
        Matcher wave = WAVELENGTH.matcher(line);
        Matcher count = LANES.matcher(line);
        if (wave.find()) {
            wavelength = wave.group(1);
        } else if (count.find()) {
            laneCount = Integer.parseInt(count.group(1));
        } else if (line.startsWith("Rx Optical Power")) {
            String[] values = valuesAfterUnit(line);
            rx = value(values, 0);
            highWarn = value(values, 2);
            lowWarn = value(values, 3);
        } else if (line.startsWith("Tx Output Power")) {
            tx = value(valuesAfterUnit(line), 0);
        } else if (line.startsWith("Lane Rx Optical Pwr")) {
            String[] values = valuesAfterUnit(line);
            highWarn = value(values, 1);
            lowWarn = value(values, 2);
        } else if (line.startsWith("Lane ID") && line.contains("Rx Pwr")) {
            inLaneTable = true;
            laneValues.clear();
        } else if (inLaneTable) {
            if (line.isEmpty() || line.matches("[-=]+")) return;
            String[] values = line.split("\\s+");
            if (values.length == 5 && values[0].matches("\\d+")) {
                int id = Integer.parseInt(values[0]);
                if (id >= 1 && id <= laneCount) {
                    laneValues.put(id, new String[]{value(values, 3), value(values, 4)});
                }
            } else {
                inLaneTable = false;
            }
        }
    }

    String wavelength() { return wavelength; }
    String tx() { return tx.isEmpty() ? laneAverage(0) : tx; }
    String rx() { return rx.isEmpty() ? laneAverage(1) : rx; }
    String lowWarn() { return lowWarn; }
    String warningRange() {
        return lowWarn.isEmpty() || highWarn.isEmpty() ? lowWarn : "[" + lowWarn + "<>" + highWarn + "]";
    }

    private String laneAverage(int column) {
        if (laneCount < 1 || laneValues.size() != laneCount) return "";
        float sum = 0;
        for (int id = 1; id <= laneCount; id++) {
            String[] values = laneValues.get(id);
            if (values == null || values[column].isEmpty()) return "";
            sum += Float.parseFloat(values[column]);
        }
        // Preserve the existing CSV convention: arithmetic mean of lane dBm.
        return String.format(Locale.ROOT, "%.2f", sum / laneCount);
    }

    private static String[] valuesAfterUnit(String line) {
        int end = line.indexOf(')');
        return end < 0 ? new String[0] : line.substring(end + 1).trim().split("\\s+");
    }

    private static String value(String[] values, int index) {
        if (index >= values.length) return "";
        Matcher number = NUMBER.matcher(values[index]);
        return number.matches() ? number.group(1) : "";
    }
}
