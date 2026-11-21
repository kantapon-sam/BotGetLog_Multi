package com.java.botgetlog.truecorp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/** Per-workflow Equipment groups. Each pass resolves workbook rows afresh. */
final class MultiIpCollection {
    static final MultiIpCollection EMPTY = new MultiIpCollection(null);
    private final Path directory;
    private final Map<String, Group> byIp = new HashMap<>();
    private final Set<String> failed = new HashSet<>();

    private static final class Group {
        final List<String> ips = new ArrayList<>();
        final Map<String, List<Integer>> rows = new LinkedHashMap<>();
    }

    private MultiIpCollection(Path directory) { this.directory = directory; }

    static MultiIpCollection load(Path plan, Sheet sheet) throws IOException {
        MultiIpCollection result = new MultiIpCollection(plan.toAbsolutePath().getParent());
        Map<String, List<Row>> workbook = new HashMap<>();
        for (Row row : sheet) {
            String ip = BotGetLog_TrueCorp.getCell(row, 3).trim();
            workbook.computeIfAbsent(ip, k -> new ArrayList<>()).add(row);
        }
        for (String line : Files.readAllLines(plan, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields.length < 3) throw new IOException("Invalid Multi-IP group");
            Group group = new Group();
            boolean conflicting = false;
            for (int i = 1; i < fields.length; i++) {
                String ip = fields[i];
                if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || group.rows.containsKey(ip)
                        || result.byIp.containsKey(ip)) throw new IOException("Invalid Multi-IP address: " + ip);
                List<Integer> enabled = new ArrayList<>();
                for (Row row : workbook.getOrDefault(ip, Collections.emptyList())) {
                    String name = BotGetLog_TrueCorp.getCell(row, 2).trim().replaceAll("\\s+", " ");
                    if (!fields[0].equalsIgnoreCase(name)) conflicting = true;
                    if ("Y".equalsIgnoreCase(BotGetLog_TrueCorp.getCell(row, 0).trim())
                            && !TrueLinkOpticalAutoMode.readLinkOpticalCmdSets(row).isEmpty()) {
                        enabled.add(row.getRowNum() + 1);
                    }
                }
                group.rows.put(ip, enabled);
                if (!enabled.isEmpty()) group.ips.add(ip);
            }
            if (conflicting) continue; // The workbook may have reassigned an IP since publication.
            for (String ip : group.rows.keySet()) {
                result.byIp.put(ip, group);
                if (Files.exists(result.directory.resolve("unavailable-" + ip))) result.failed.add(ip);
            }
        }
        System.out.println("[MULTI-IP] Loaded " + new HashSet<>(result.byIp.values()).size()
                + " verified workbook groups; preferred addresses before standby addresses.");
        return result;
    }

    boolean grouped(String ip) { return byIp.containsKey(ip); }

    int rank(String ip) {
        Group group = byIp.get(ip);
        return group == null ? 0 : group.ips.indexOf(ip);
    }

    private synchronized String current(Group group) {
        for (String ip : group.ips) if (!failed.contains(ip)) return ip;
        return "";
    }

    boolean allowed(String ip) {
        Group group = byIp.get(ip);
        return group == null || ip.equals(current(group));
    }

    synchronized void unavailable(String ip) throws IOException {
        Group group = byIp.get(ip);
        if (group == null || !ip.equals(current(group))) return;
        Files.write(directory.resolve("unavailable-" + ip), new byte[0], StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        failed.add(ip);
        String next = current(group);
        System.out.println("[MULTI-IP] Node login unavailable via " + ip + "; next="
                + (next.isEmpty() ? "exhausted" : next));
    }

    TrueLinkOpticalAutoMode.Selection route(TrueLinkOpticalAutoMode.Selection selection,
            Sheet sheet, boolean includeStandbys, boolean exactPairs) {
        if (byIp.isEmpty()) return selection;
        LinkedHashMap<Integer, List<String>> rows = new LinkedHashMap<>();
        Set<Group> seen = new HashSet<>();
        for (Map.Entry<Integer, List<String>> entry : selection.getCmdSetsByRow().entrySet()) {
            Row row = sheet.getRow(entry.getKey() - 1);
            String ip = BotGetLog_TrueCorp.getCell(row, 3).trim();
            Group group = byIp.get(ip);
            if (group == null) {
                rows.put(entry.getKey(), entry.getValue());
            } else if (exactPairs) {
                if (allowed(ip) && group.rows.get(ip).contains(entry.getKey())) rows.put(entry.getKey(), entry.getValue());
            } else if (group.rows.get(ip).contains(entry.getKey()) && seen.add(group)) {
                String primary = current(group);
                Set<String> commands = new HashSet<>();
                for (String candidate : group.ips) {
                    if (failed.contains(candidate) || (!includeStandbys && !candidate.equals(primary))) continue;
                    for (int number : group.rows.get(candidate)) {
                        List<String> available = new ArrayList<>();
                        for (String cmd : TrueLinkOpticalAutoMode.readLinkOpticalCmdSets(sheet.getRow(number - 1))) {
                            if (commands.add(candidate + "|" + cmd.toUpperCase(Locale.ROOT))) available.add(cmd);
                        }
                        if (!available.isEmpty()) rows.put(number, available);
                    }
                }
            }
        }
        return selection.withRows(rows);
    }
}
