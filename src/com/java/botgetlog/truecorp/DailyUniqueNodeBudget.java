package com.java.botgetlog.truecorp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Called only while DailyCollectionBudget holds its shared process/file lock. */
final class DailyUniqueNodeBudget {
    private final Path directory, ledger;
    private final String day;
    private final int limit;
    private final Map<String,String> exceptions = new HashMap<>();
    private final Set<String> admitted = new HashSet<>();
    private long loadedSize = -1;

    static int configuredLimit(Path root, String day) throws IOException {
        // The Friday Full retains its existing, uncapped collection policy.
        if ("1".equals(System.getenv("BOT_WEEKLY_FULL"))) return 0;
        String value = setting("true.daily.unique.limit", "TRUE_DAILY_UNIQUE_LIMIT");
        Path policy = root.resolve(day).resolve("unique-node-policy.properties");
        if (value.isEmpty() && Files.exists(policy)) {
            List<String> lines = Files.readAllLines(policy, StandardCharsets.UTF_8);
            if (lines.size() != 3 || !lines.get(0).equals("version=1")
                    || !lines.get(1).equals("day=" + day) || !lines.get(2).startsWith("limit="))
                throw new IOException("Invalid daily unique-node policy");
            value = lines.get(2).substring(6);
        }
        if (value.isEmpty()) return 0;
        try {
            int result = Integer.parseInt(value);
            if (result < 0 || result > 1000000) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid unique-node limit", invalid); }
    }

    DailyUniqueNodeBudget(Path root, String day, int limit) throws IOException {
        this.directory = root.resolve(day);
        this.ledger = directory.resolve("unique-node-admissions.tsv");
        this.day = day;
        this.limit = limit;
        String file = setting("true.daily.unique.exceptionsFile", "TRUE_DAILY_UNIQUE_EXCEPTIONS_FILE");
        if (!file.isEmpty()) {
            List<String> lines = Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8);
            if (lines.size() < 2 || !lines.get(0).equals("version=1") || !lines.get(1).equals("day=" + day))
                throw new IOException("LLDP exception scope belongs to another cycle");
            for (String line : lines.subList(2, lines.size())) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 3 || !validIp(fields[0]) || fields[2].trim().isEmpty()
                        || !Arrays.asList("LLDP_TOPOLOGY_CHANGE", "LLDP_ONE_WAY", "LLDP_PORT_MISMATCH", "LLDP_DISCOVERY").contains(fields[1]))
                    throw new IOException("Invalid LLDP unique-node exception");
                if (exceptions.put(fields[0], fields[1]) != null) throw new IOException("Duplicate LLDP exception IP");
            }
        }
    }

    boolean available(String ip) throws IOException {
        load();
        return admitted.contains(ip) || admitted.size() < limit || exceptions.containsKey(ip);
    }

    boolean admitAll(Collection<String> ips) throws IOException {
        load();
        Set<String> regular = new TreeSet<>(), exceptional = new TreeSet<>();
        for (String ip : ips) {
            if (!validIp(ip)) throw new IOException("Invalid unique-node IP");
            if (!admitted.contains(ip)) (exceptions.containsKey(ip) ? exceptional : regular).add(ip);
        }
        if (!regular.isEmpty() && admitted.size() + regular.size() > limit) return false;
        // Ordinary members take the remaining slots before justified LLDP overflow.
        for (String ip : regular) append(ip, "BASE");
        for (String ip : exceptional) append(ip, exceptions.get(ip));
        return true;
    }

    private void load() throws IOException {
        if (!Files.exists(ledger)) {
            Files.createDirectories(directory);
            StringBuilder initial = new StringBuilder("version=1\nday=" + day + "\nlimit=" + limit + "\n");
            Path counts = directory.resolve("counts");
            if (Files.isDirectory(counts)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(counts, "*.properties")) {
                    for (Path file : files) {
                        String ip = file.getFileName().toString().replaceFirst("\\.properties$", "");
                        if (!validIp(ip)) throw new IOException("Invalid prior collection IP");
                        Properties values = new Properties();
                        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
                        int count;
                        try { count = Integer.parseInt(values.getProperty("used", "")); }
                        catch (NumberFormatException invalid) { throw new IOException("Invalid prior collection count", invalid); }
                        if (count < 0) throw new IOException("Negative prior collection count");
                        if (count > 0) initial.append(ip).append("\tPREEXISTING\n");
                    }
                }
            }
            Path temporary = Files.createTempFile(directory, ".unique-node-", ".tmp");
            Files.write(temporary, initial.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE);
        }
        long size = Files.size(ledger);
        if (size == loadedSize) return;
        String text = new String(Files.readAllBytes(ledger), StandardCharsets.UTF_8);
        if (!text.endsWith("\n")) throw new IOException("Incomplete unique-node admission journal; collection deferred");
        String[] lines = text.split("\n");
        if (lines.length < 3 || !lines[0].equals("version=1") || !lines[1].equals("day=" + day)
                || !lines[2].equals("limit=" + limit)) throw new IOException("Unique-node journal configuration differs");
        Set<String> loaded = new HashSet<>();
        for (int i = 3; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length != 2 || !validIp(fields[0]) || !loaded.add(fields[0])
                    || !Arrays.asList("BASE", "PREEXISTING", "LLDP_TOPOLOGY_CHANGE", "LLDP_ONE_WAY", "LLDP_PORT_MISMATCH", "LLDP_DISCOVERY").contains(fields[1]))
                throw new IOException("Invalid unique-node admission journal");
        }
        admitted.clear(); admitted.addAll(loaded); loadedSize = size;
    }

    private void append(String ip, String reason) throws IOException {
        byte[] record = (ip + "\t" + reason + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(ledger, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(record);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        admitted.add(ip); loadedSize += record.length;
        System.out.println("[DAILY-UNIQUE] ADMIT " + ip + " reason=" + reason + " total=" + admitted.size() + "/" + limit + " day=" + day);
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(environment);
        return value == null ? "" : value.trim();
    }

    private static boolean validIp(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255 || !Integer.toString(n).equals(part)) return false;
            } catch (NumberFormatException invalid) { return false; }
        }
        return true;
    }
}
