package com.truelinkoptical.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** IPv4 admission for new workbook rows. Both collectors and Control use this format. */
public final class AutoAddIpPolicy {
    public static final String FILE = "_config/auto-add-ip.properties";
    public static final String DEFAULT_ALLOW = "10.85/16,10.150/16,10.163/16,10.165/16,10.167/16,10.185/16,10.207/16";
    public static final AutoAddIpPolicy DEFAULT = new AutoAddIpPolicy(DEFAULT_ALLOW, "10.207/16");
    private final List<Range> allowed, excluded;
    public final String allow, exclude;

    public AutoAddIpPolicy(String allow, String exclude) {
        allowed = ranges(allow);
        excluded = ranges(exclude);
        this.allow = join(allowed);
        this.exclude = join(excluded);
    }

    public static AutoAddIpPolicy forWorkbook(Path workbook) throws IOException {
        String frozen = System.getenv("TRUE_AUTO_ADD_POLICY_FILE");
        Path path = frozen == null ? workbook.toAbsolutePath().getParent().resolve(FILE) : Paths.get(frozen);
        if (frozen == null && Files.notExists(path)) return DEFAULT;
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        if (frozen != null && "defaults\n".equals(content)) return DEFAULT;
        return parse(content);
    }

    public static AutoAddIpPolicy parse(String content) {
        String allow = null, exclude = null;
        for (String line : content.split("\\r?\\n")) {
            if (line.startsWith("allow=") && allow == null) allow = line.substring(6);
            else if (line.startsWith("exclude=") && exclude == null) exclude = line.substring(8);
            else if (!line.isEmpty()) throw new IllegalArgumentException("Invalid auto-add policy file");
        }
        return new AutoAddIpPolicy(allow, exclude);
    }

    public String serialize() { return "allow=" + allow + "\nexclude=" + exclude + "\n"; }

    public String decision(String ip) {
        long address = ipv4(ip);
        if (address < 0 || address == 0 || address == 0xffffffffL) return "Invalid management IP";
        for (Range range : excluded) if (range.contains(address)) return "Excluded by " + range;
        for (Range range : allowed) if (range.contains(address)) return "Allowed by " + range;
        return "Outside allowed ranges";
    }

    public boolean allows(String ip) { return decision(ip).startsWith("Allowed by "); }

    private static List<Range> ranges(String text) {
        if (text == null || text.length() > 8192) throw new IllegalArgumentException("Supply both CIDR lists (up to 8192 characters each)");
        List<Range> ranges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : text.trim().split("[\\s,]+")) {
            if (token.isEmpty()) continue;
            Range range = new Range(token);
            if (seen.add(range.toString())) ranges.add(range);
        }
        return ranges;
    }

    private static String join(List<Range> list) {
        List<String> names = new ArrayList<>();
        for (Range range : list) names.add(range.toString());
        return String.join(",", names);
    }

    private static long ipv4(String text) {
        if (text == null) return -1;
        // Called per discovery row. Parse without regex allocation or DNS.
        long value = 0;
        int octet = 0, digits = 0, dots = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (digits == 0 || ++dots > 3) return -1;
                value = value * 256 + octet;
                digits = 0;
                octet = 0;
            } else {
                if (c < '0' || c > '9' || digits == 3 || (digits > 0 && octet == 0)) return -1;
                digits++;
                octet = octet * 10 + c - '0';
                if (octet > 255) return -1;
            }
        }
        return dots == 3 && digits > 0 ? value * 256 + octet : -1;
    }

    private static class Range {
        final long first, end;
        final String name;
        Range(String token) {
            String[] parts = token.split("/", -1);
            String prefix = parts.length == 1 ? "32" : parts[1];
            if (parts.length > 2 || !prefix.matches("0|[1-9][0-9]?") || Integer.parseInt(prefix) > 32)
                throw new IllegalArgumentException("Invalid IPv4 CIDR: " + token);
            int bits = Integer.parseInt(prefix);
            String ip = parts[0];
            int count = ip.split("\\.", -1).length;
            if (count < 4 && parts.length == 2) for (int i = count; i < 4; i++) ip += ".0";
            first = ipv4(ip);
            long size = 1L << (32 - bits);
            if (first < 0 || first % size != 0)
                throw new IllegalArgumentException("Use the network address for CIDR: " + token);
            end = first + size;
            name = ip + "/" + bits;
        }
        boolean contains(long ip) { return ip >= first && ip < end; }
        @Override public String toString() { return name; }
    }
}
