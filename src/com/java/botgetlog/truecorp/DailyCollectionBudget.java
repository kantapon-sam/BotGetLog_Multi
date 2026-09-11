package com.java.botgetlog.truecorp;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Persistent admission for scheduled TRUE collections; Live probes do not activate it. */
public final class DailyCollectionBudget {
    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final Map<Path, ReentrantLock> MUTEXES = new ConcurrentHashMap<>();
    private static DailyCollectionBudget scheduled;
    private final Path root;
    private final int limit;
    private final Clock clock;
    private final ReentrantLock mutex;
    private final Map<String, String> prepaid = new HashMap<>();

    public DailyCollectionBudget(Path directory, int limit, Clock clock) throws IOException {
        if (limit < 1 || limit > 20 || clock == null) throw new IllegalArgumentException("Invalid daily collection budget");
        Files.createDirectories(directory);
        this.root = directory.toRealPath();
        this.limit = limit;
        this.clock = clock;
        this.mutex = MUTEXES.computeIfAbsent(root, key -> new ReentrantLock(true));
        locked(() -> {
            Path config = root.resolve("config.properties");
            if (Files.exists(config)) {
                Map<String,String> values = read(config);
                if (!"1".equals(values.get("version")) || !Integer.toString(limit).equals(values.get("limit"))) {
                    throw new IOException("Daily budget configuration differs; collection deferred");
                }
            } else {
                write(config, "version=1\nlimit=" + limit + "\nzone=Asia/Bangkok\n");
            }
            return null;
        });
    }

    static synchronized void activate() throws IOException {
        if (scheduled != null) return;
        String dir = setting("true.daily.collection.dir", "TRUE_DAILY_COLLECTION_BUDGET_DIR",
                System.getProperty("os.name", "").toLowerCase().contains("windows")
                        ? "_output/System_Log/daily-collection-budget"
                        : "/transport/BotGetLog_Multi/dist/_output/System_Log/daily-collection-budget");
        int max;
        try { max = Integer.parseInt(setting("true.daily.collection.limit", "TRUE_DAILY_COLLECTION_LIMIT", "3")); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid daily collection limit", invalid); }
        scheduled = new DailyCollectionBudget(Paths.get(dir), max, Clock.system(ZONE));
        System.out.println("[DAILY-BUDGET] Scheduled collections share limit=" + max
                + " per IP per Bangkok day, including primary and retries; state=" + scheduled.root);
    }

    static synchronized DailyCollectionBudget active() { return scheduled; }

    static boolean canCollect(String ip) throws IOException {
        DailyCollectionBudget budget = active();
        return budget == null || budget.available(ip);
    }

    static boolean startCollection(String ip, String reason) throws IOException {
        DailyCollectionBudget budget = active();
        return budget == null || budget.start(ip, reason);
    }

    static String pairsFile() { return setting("true.daily.collection.pairsFile", "TRUE_DAILY_COLLECTION_PAIRS_FILE", ""); }

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) value = System.getenv(environment);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public int used(String rawIp) throws IOException {
        final String ip = ip(rawIp);
        return locked(() -> count(day(), ip));
    }

    public boolean available(String rawIp) throws IOException {
        final String ip = ip(rawIp);
        return locked(() -> {
            String day = day();
            return day.equals(prepaid.get(ip)) || count(day, ip) < limit;
        });
    }

    public boolean start(String rawIp, String reason) throws IOException {
        final String ip = ip(rawIp);
        return locked(() -> {
            String day = day();
            if (day.equals(prepaid.remove(ip))) {
                System.out.println("[DAILY-BUDGET] START " + ip + " reserved pair attempt");
                return true;
            }
            int used = count(day, ip);
            if (used >= limit) {
                defer(day, ip, "DAILY_LIMIT");
                return false;
            }
            consume(day, ip, used, reason);
            return true;
        });
    }

    /** Reserve both sides under one cross-process lock before selected logs are archived.
     * Reservations count immediately and are not refunded on crash, preventing repeated
     * restarts from reopening the same failed pair. Shared endpoints consume one slot.
     */
    public Set<String> reservePairs(List<String[]> pairs, Set<String> selectedIps) throws IOException {
        final List<String[]> normalized = new ArrayList<>();
        for (String[] pair : pairs) {
            if (pair.length != 2) throw new IOException("Invalid daily-budget pair");
            String a = ip(pair[0]), b = ip(pair[1]);
            if (a.equals(b)) throw new IOException("A collection pair needs two different IPs");
            normalized.add(a.compareTo(b) < 0 ? new String[]{a,b} : new String[]{b,a});
        }
        return locked(() -> {
            String day = day();
            Set<String> blocked = new LinkedHashSet<>();
            Set<String> admitted = new HashSet<>();
            Set<String> seen = new HashSet<>();
            for (String[] pair : normalized) {
                String key = pair[0] + "__" + pair[1];
                if (!seen.add(key)) continue;
                String reason = null;
                if (!selectedIps.contains(pair[0]) || !selectedIps.contains(pair[1])) reason = "PAIR_INCOMPLETE_SELECTION";
                else if (Files.exists(pairFile(day, key))) reason = "PAIR_ALREADY_TRIED";
                else for (String member : pair) {
                    if (!day.equals(prepaid.get(member)) && count(day, member) >= limit) reason = "PAIR_DAILY_LIMIT";
                }
                if (reason != null) {
                    for (String member : pair) { blocked.add(member); defer(day, member, reason); }
                    continue;
                }
                for (String member : pair) {
                    if (!day.equals(prepaid.get(member))) {
                        consume(day, member, count(day, member), "PAIR_RESERVATION");
                        prepaid.put(member, day);
                    }
                    admitted.add(member);
                }
                write(pairFile(day, key), "state=RESERVED\nreservedAt=" + clock.millis() + "\n");
            }
            blocked.removeAll(admitted);
            return blocked;
        });
    }

    static List<String[]> readPairs(Path file) throws IOException {
        List<String[]> pairs = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) pairs.add(line.trim().split("\\s+"));
        }
        return pairs;
    }

    private String day() { return LocalDate.now(clock.withZone(ZONE)).toString(); }
    private Path counter(String day, String ip) { return root.resolve(day).resolve("counts").resolve(ip + ".properties"); }
    private Path pairFile(String day, String key) { return root.resolve(day).resolve("pairs").resolve(key + ".properties"); }

    private int count(String day, String ip) throws IOException {
        Path path = counter(day, ip);
        if (!Files.exists(path)) return 0;
        try {
            int count = Integer.parseInt(read(path).get("used"));
            if (count < 0) throw new NumberFormatException();
            return count;
        } catch (RuntimeException invalid) { throw new IOException("Invalid daily count for " + ip + "; collection deferred", invalid); }
    }

    private void consume(String day, String ip, int used, String reason) throws IOException {
        write(counter(day, ip), "used=" + (used + 1) + "\nlastAdmittedAt=" + clock.millis()
                + "\nreason=" + String.valueOf(reason).replaceAll("[^A-Za-z0-9_-]", "_") + "\n");
        System.out.println("[DAILY-BUDGET] ADMIT " + ip + " " + (used + 1) + "/" + limit + " day=" + day);
    }

    private void defer(String day, String ip, String reason) throws IOException {
        Path file = root.resolve(day).resolve("deferred.tsv");
        Files.createDirectories(file.getParent());
        Files.write(file, (clock.millis() + "\t" + ip + "\t" + reason + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("[DAILY-BUDGET] DEFER " + ip + " " + reason);
    }

    private static String ip(String raw) throws IOException {
        String ip = raw == null ? "" : raw.trim();
        if (!ip.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) throw new IOException("Invalid daily-budget IP");
        StringBuilder normalized = new StringBuilder();
        for (String piece : ip.split("\\.")) {
            int value = Integer.parseInt(piece);
            if (value > 255) throw new IOException("Invalid daily-budget IP");
            if (normalized.length() > 0) normalized.append('.');
            normalized.append(value);
        }
        return normalized.toString();
    }

    private static Map<String,String> read(Path path) throws IOException {
        Map<String,String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] parts = line.split("=", 2);
            if (parts.length == 2) values.put(parts[0], parts[1]);
        }
        return values;
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".daily-next-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private interface Operation<T> { T run() throws IOException; }
    private <T> T locked(Operation<T> action) throws IOException {
        mutex.lock();
        try (FileChannel file = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = file.lock()) {
            return action.run();
        } finally { mutex.unlock(); }
    }
}
