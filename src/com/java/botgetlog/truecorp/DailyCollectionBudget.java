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
    private String onceDay = "";
    private java.nio.file.attribute.FileTime onceModified;
    private long onceSize = -1;
    private Set<String> onceIps = Collections.emptySet();
    interface CompletionCheck { boolean complete(java.io.File file, String commandSet) throws IOException; }
    private final CompletionCheck completionCheck;

    public DailyCollectionBudget(Path directory, int limit, Clock clock) throws IOException {
        this(directory, limit, clock, DailyCollectionBudget::validateLog);
    }

    DailyCollectionBudget(Path directory, int limit, Clock clock, CompletionCheck completionCheck) throws IOException {
        if (limit < 1 || limit > 20 || clock == null) throw new IllegalArgumentException("Invalid daily collection budget");
        Files.createDirectories(directory);
        this.root = directory.toRealPath();
        this.limit = limit;
        this.clock = clock;
        this.completionCheck = completionCheck;
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
            return day.equals(prepaid.get(ip)) || (!rxComplete(day, ip) && count(day, ip) < limit);
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
            if (rxComplete(day, ip)) {
                defer(day, ip, "RX_LOG_ALREADY_COMPLETE");
                return false;
            }
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
                    if (!day.equals(prepaid.get(member)) && (rxComplete(day, member) || count(day, member) >= limit)) reason = "PAIR_DAILY_LIMIT";
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

    /** Restrict named Rx targets to one complete log/day across all scheduled reasons.
     * Additive registration cannot erase another planner's restrictions or reset counters.
     */
    public int registerDailyOnce(List<String> rawIps, String expectedDay) throws IOException {
        final Set<String> additions = new java.util.TreeSet<>();
        for (String raw : rawIps) if (!raw.trim().isEmpty()) additions.add(ip(raw));
        return locked(() -> {
            String today = day();
            if (!today.equals(expectedDay)) throw new IOException("Rx plan crossed Bangkok midnight; rebuild it");
            Set<String> all = new java.util.TreeSet<>(readOnce(today));
            all.addAll(additions);
            StringBuilder text = new StringBuilder("version=1\nday=").append(today).append('\n');
            for (String member : all) text.append(member).append("=1\n");
            write(onceFile(today), text.toString());
            onceModified = null;
            return all.size();
        });
    }

    private Path onceFile(String day) { return root.resolve(day).resolve("rx-once-per-day.properties"); }

    private Set<String> readOnce(String day) throws IOException {
        Path path = onceFile(day);
        if (!Files.exists(path)) return Collections.emptySet();
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(path);
        long size = Files.size(path);
        if (day.equals(onceDay) && modified.equals(onceModified) && size == onceSize) return onceIps;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !"version=1".equals(lines.get(0)) || !("day=" + day).equals(lines.get(1))) {
            throw new IOException("Invalid Rx daily policy; collection deferred");
        }
        Set<String> members = new HashSet<>();
        for (int i = 2; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("=", -1);
            if (parts.length != 2 || !"1".equals(parts[1]) || !parts[0].equals(ip(parts[0])) || !members.add(parts[0])) {
                throw new IOException("Invalid Rx daily policy entry; collection deferred");
            }
        }
        onceDay = day; onceModified = modified; onceSize = size; onceIps = members;
        return members;
    }

    private boolean rxComplete(String day, String ip) throws IOException {
        if (!readOnce(day).contains(ip)) return false;
        Path marker = root.resolve(day).resolve("rx-completed").resolve(ip + ".properties");
        if (Files.exists(marker)) {
            if (!"1".equals(read(marker).get("complete"))) throw new IOException("Invalid Rx completion marker");
            return true;
        }
        if (count(day, ip) == 0) return false;
        // Reuse the collector's real command/header validator, not file size or [OK] text.
        // The daily filename plus modification time prevents yesterday's checkpoint log
        // from satisfying today's refresh. Incomplete/missing logs retain the normal cap.
        Path logs = Paths.get(setting("true.daily.collection.logDir", "TRUE_DAILY_COLLECTION_LOG_DIR",
                root.getParent().getParent().resolve("Total_Log").toString()));
        if (!Files.isDirectory(logs)) return false;
        long midnight = LocalDate.parse(day).atStartOfDay(ZONE).toInstant().toEpochMilli();
        String suffix = "-LLDP-Link_OPTIC_" + day + ".txt";
        final java.util.regex.Pattern identity = java.util.regex.Pattern.compile("\\[\\d+\\]" + java.util.regex.Pattern.quote(ip) + "_.*");
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(logs,
                path -> identity.matcher(path.getFileName().toString()).matches()
                        && path.getFileName().toString().endsWith(suffix))) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file)
                        || Files.getLastModifiedTime(file).toMillis() < midnight) continue;
                int end = name.length() - ("_" + day + ".txt").length();
                int start = name.lastIndexOf('_', name.length() - suffix.length() - 1) + 1;
                String commandSet = name.substring(start, end);
                if (completionCheck.complete(file.toFile(), commandSet)) {
                    write(marker, "complete=1\nvalidatedAt=" + clock.millis() + "\n");
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean validateLog(java.io.File file, String commandSet) throws IOException {
        try {
            Class<?> collector = Class.forName("com.java.botgetlog.truecorp.BotGetLog_TrueCorp");
            return Boolean.TRUE.equals(collector.getMethod("isLogCompleteForCmdSet", java.io.File.class, String.class)
                    .invoke(null, file, commandSet));
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Cannot validate Rx log completeness; collection deferred", failure);
        }
    }

    // The collector normally fills these command caches before admitting nodes.
    // Its offline CLI must do the same once, rather than reopen the full inventory
    // for every log. Reuse its read-only loader and validator from the same JAR.
    private static void initializeOfflineValidation() throws Exception {
        Class<?> metadata = Class.forName("com.java.shared.AppMetadata");
        java.io.File directory = (java.io.File) metadata.getMethod("getAppDirectory").invoke(null);
        java.io.File input = new java.io.File(directory, "UserInterface_Input.xlsx");
        if (!input.isFile()) throw new IOException("Missing validator inventory; Rx selection deferred");
        Class<?> collector = Class.forName("com.java.botgetlog.truecorp.BotGetLog_TrueCorp");
        Class<?> workbookType = Class.forName("org.apache.poi.ss.usermodel.Workbook");
        java.lang.reflect.Method open = collector.getDeclaredMethod("openWorkbookReadOnly", java.io.File.class);
        java.lang.reflect.Method cache = collector.getDeclaredMethod("rebuildExcelCache", workbookType);
        open.setAccessible(true);
        cache.setAccessible(true);
        try (AutoCloseable workbook = (AutoCloseable) open.invoke(null, input)) {
            cache.invoke(null, workbook);
        }
    }

    /** Offline policy registration only: never opens a device session or charges an admission. */
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !("--register-rx-once".equals(args[0]) || "--eligible-rx".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: --register-rx-once|--eligible-rx BUDGET_DIR LIMIT IP_FILE YYYY-MM-DD");
        }
        DailyCollectionBudget budget = new DailyCollectionBudget(Paths.get(args[1]), Integer.parseInt(args[2]), Clock.system(ZONE));
        if (!budget.day().equals(args[4])) throw new IOException("Rx plan crossed Bangkok midnight; rebuild it");
        List<String> ips = Files.readAllLines(Paths.get(args[3]), StandardCharsets.UTF_8);
        if ("--register-rx-once".equals(args[0])) {
            int count = budget.registerDailyOnce(ips, args[4]);
            System.out.println("[RX-DAILY] Registered one-complete-log-per-day IPs=" + count + " day=" + args[4]);
        } else {
            initializeOfflineValidation();
            for (String member : ips) if (!member.trim().isEmpty()) {
                if (!budget.day().equals(args[4])) throw new IOException("Rx queue crossed Bangkok midnight; rebuild it");
                System.out.println("RX_ELIGIBLE\t" + ip(member) + "\t" + budget.used(member) + "\t" + budget.available(member));
            }
        }
    }
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
