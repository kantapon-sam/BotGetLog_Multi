package com.java.tools.linkoptical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Junos physical interface, CRC, and DOM sections from a live transcript. */
final class JuniperLivePortParser {
    private static final String PORT = "(?:ge|xe|et)-\\d+/\\d+/\\d+(?::\\d+)?";
    private static final Pattern SUMMARY = Pattern.compile(
            "^\\s*(" + PORT + ")\\s+(up|down)\\s+(up|down)(?:\\s+(.*))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHYSICAL = Pattern.compile(
            "^\\s*Physical interface:\\s*(" + PORT + ")\\b(.*)$",
            Pattern.CASE_INSENSITIVE);

    private JuniperLivePortParser() {}

    static List<LiveNodeHealthParser.PortSnapshot> parse(String transcript) {
        if (transcript == null || transcript.trim().isEmpty()) return Collections.emptyList();
        Map<String, PortData> ports = new LinkedHashMap<String, PortData>();
        PortData current = null;
        for (String raw : transcript.split("\\r?\\n")) {
            String line = raw.trim();
            Matcher summary = SUMMARY.matcher(line);
            if (summary.matches()) {
                PortData port = get(ports, summary.group(1));
                port.status = "up".equalsIgnoreCase(summary.group(3)) ? "UP" : "DOWN";
                if (summary.group(4) != null && !summary.group(4).trim().isEmpty()
                        && !summary.group(4).trim().startsWith("inet")) {
                    port.description = summary.group(4).trim();
                }
                continue;
            }
            Matcher physical = PHYSICAL.matcher(line);
            if (physical.find()) {
                current = get(ports, physical.group(1));
                Matcher state = Pattern.compile("Physical link is\\s+(Up|Down)", Pattern.CASE_INSENSITIVE)
                        .matcher(physical.group(2));
                if (state.find()) current.status = state.group(1).toUpperCase(Locale.ROOT);
                continue;
            }
            if (line.regionMatches(true, 0, "Physical interface:", 0, 19)) {
                current = null;
                continue;
            }
            if (current == null) continue;
            if (line.startsWith("show ") || line.startsWith("set cli ")) {
                current = null;
                continue;
            }
            Matcher speed = Pattern.compile("\\bSpeed:\\s*([^,]+)", Pattern.CASE_INSENSITIVE).matcher(line);
            if (speed.find() && current.speed.isEmpty()) current.speed = normalizeSpeed(speed.group(1));
            if (current.description.isEmpty()
                    && line.regionMatches(true, 0, "Description:", 0, 12)) {
                current.description = line.substring(12).trim();
            }
            Matcher crc = Pattern.compile("\\bCRC/Align errors\\s*:?\\s*([\\d,]+)(?:\\s+([\\d,]+))?",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (crc.find()) {
                current.crcInput = parseLong(crc.group(1));
                current.crcOutput = parseLong(crc.group(2));
            }
            Matcher wavelength = Pattern.compile("^Wavelength(?:\\s+setpoint)?\\s*:\\s*([\\d.]+)\\s*nm",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (wavelength.find()) {
                current.wavelength = wavelength.group(1) + " nm";
                current.hasOptical = true;
            }
            if (line.matches("(?i)^Laser rx power low warning threshold.*")) {
                current.rxLow = dbm(line);
            } else if (line.matches("(?i)^Laser rx power high warning threshold.*")) {
                current.rxHigh = dbm(line);
            } else if (line.matches("(?i)^(?:Laser output power|Tx optical power)\\s*:.*")) {
                Double value = dbm(line);
                if (value != null) {
                    current.txSum += value;
                    current.txCount++;
                    current.hasOptical = true;
                }
            } else if (line.matches("(?i)^(?:Laser rx power|Laser receiver power|Receiver signal average optical power|Rx optical power)\\s*:.*")) {
                Double value = dbm(line);
                if (value != null) {
                    current.rxValues.add(value);
                    current.hasOptical = true;
                }
            }
            if (line.matches("(?i)^.*(?:rx power|receiver power).*\\b(?:alarm|warning)\\s*:\\s*On\\s*$")) {
                current.alarm = true;
            }
        }
        List<LiveNodeHealthParser.PortSnapshot> result = new ArrayList<LiveNodeHealthParser.PortSnapshot>();
        for (PortData port : ports.values()) {
            Double tx = port.txCount == 0 ? null : port.txSum / port.txCount;
            Double rx = null;
            if (!port.rxValues.isEmpty()) {
                double sum = 0.0d;
                for (Double value : port.rxValues) sum += value;
                rx = sum / port.rxValues.size();
            }
            String range = port.rxLow == null && port.rxHigh == null ? ""
                    : (port.rxLow == null ? "" : port.rxLow) + " to "
                    + (port.rxHigh == null ? "" : port.rxHigh) + " dBm";
            boolean outOfRange = false;
            for (Double value : port.rxValues) {
                if ((port.rxLow != null && value < port.rxLow)
                        || (port.rxHigh != null && value > port.rxHigh)) {
                    outOfRange = true;
                    break;
                }
            }
            String opticalStatus = !port.hasOptical ? "NO_DATA"
                    : port.alarm || outOfRange ? "ALARM"
                    : rx == null ? "NO_DATA" : "NORMAL";
            Long crcTotal = port.crcInput == null && port.crcOutput == null ? null
                    : (port.crcInput == null ? 0L : port.crcInput)
                    + (port.crcOutput == null ? 0L : port.crcOutput);
            result.add(new LiveNodeHealthParser.PortSnapshot(port.name, port.status,
                    port.description, port.speed, port.wavelength, "", tx, rx,
                    range, port.hasOptical, opticalStatus, port.crcInput, port.crcOutput, crcTotal));
        }
        return Collections.unmodifiableList(result);
    }

    private static PortData get(Map<String, PortData> ports, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        PortData result = ports.get(key);
        if (result == null) {
            result = new PortData(name);
            ports.put(key, result);
        }
        return result;
    }

    private static Double dbm(String line) {
        Matcher match = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)\\s*dBm", Pattern.CASE_INSENSITIVE)
                .matcher(line);
        return match.find() ? Double.valueOf(match.group(1)) : null;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return Long.valueOf(value.replace(",", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String normalizeSpeed(String value) {
        String speed = value == null ? "" : value.trim();
        Matcher rate = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([GMK])(?:bps|bit/s)$",
                Pattern.CASE_INSENSITIVE).matcher(speed);
        return rate.matches() ? rate.group(1) + rate.group(2).toUpperCase(Locale.ROOT) : speed;
    }

    private static final class PortData {
        final String name;
        String status = "UNKNOWN";
        String description = "";
        String speed = "";
        String wavelength = "";
        double txSum;
        int txCount;
        final List<Double> rxValues = new ArrayList<Double>();
        Double rxLow;
        Double rxHigh;
        Long crcInput;
        Long crcOutput;
        boolean hasOptical;
        boolean alarm;

        PortData(String name) { this.name = name; }
    }
}
