package com.truelinkoptical.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-process connection permits. A physical lease must outlive its network connection. */
public final class SharedConnectionBudget {
    public static final String DEFAULT_DIRECTORY = "/transport/BotGetLog_Multi/dist/_output/System_Log/shared-connection-budget";
    private static final ConcurrentMap<Path, ReentrantLock> LOCAL_MUTEXES = new ConcurrentHashMap<Path, ReentrantLock>();
    private static final ConcurrentMap<Path, HeldLock> HELD = new ConcurrentHashMap<Path, HeldLock>();
    // Closing another descriptor for an inode locked by this JVM can release POSIX
    // locks. Keep an unexpected overlapping descriptor open and fail closed.
    private static final ConcurrentMap<Path, FileChannel> UNKNOWN_LOCAL_LOCKS = new ConcurrentHashMap<Path, FileChannel>();
    private final Path directory;
    private final int total;
    private final int maxLive;
    private final String pid;
    private final ReentrantLock localMutex;

    public SharedConnectionBudget(Path directory, int total, int maxLive) throws IOException {
        if (directory == null || total < 1 || total > 200 || maxLive < 1 || maxLive > total) {
            throw new IllegalArgumentException("Invalid connection budget configuration");
        }
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
        this.total = total;
        this.maxLive = maxLive;
        this.pid = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
        if (!pid.matches("[0-9]+")) throw new IOException("Cannot determine process identity");
        ReentrantLock candidate = new ReentrantLock(true);
        ReentrantLock existing = LOCAL_MUTEXES.putIfAbsent(this.directory, candidate);
        this.localMutex = existing == null ? candidate : existing;
        locked(new Operation<Void>() {
            public Void run() throws IOException { verifyConfiguration(); return null; }
        });
    }

    public static SharedConnectionBudget fromEnvironment() throws IOException {
        String dir = setting("true.shared.connectionBudget.dir", "TRUE_SHARED_CONNECTION_BUDGET_DIR", DEFAULT_DIRECTORY);
        int total = configuredNumber("true.shared.connectionBudget.total", "TRUE_SHARED_CONNECTION_TOTAL", 20);
        int live = configuredNumber("true.shared.connectionBudget.maxLive", "TRUE_SHARED_LIVE_MAX", 5);
        try { return new SharedConnectionBudget(Paths.get(dir), total, live); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid shared budget configuration", invalid); }
    }

    public Registration registerBot() throws IOException {
        return locked(new Operation<Registration>() {
            public Registration run() throws IOException {
                verifyConfiguration();
                String name = "bot-" + pid + "-" + unique() + ".lock";
                HeldLock held = hold(directory.resolve(name));
                if (held == null) throw new IOException("Cannot register collector");
                try {
                    Properties properties = new Properties();
                    properties.setProperty("pid", pid);
                    write(meta(held.path), properties);
                    return new Registration(held);
                } catch (IOException failure) { release(held); throw failure; }
            }
        });
    }

    public LiveRequest enqueueLive(final String owner, final int count) throws IOException {
        if (count < 1 || count > maxLive) throw new IllegalArgumentException("Live request exceeds its budget");
        return locked(new Operation<LiveRequest>() {
            public LiveRequest run() throws IOException {
                scan();
                Path sequencePath = directory.resolve("sequence.properties");
                long previous = Files.exists(sequencePath) ? number(read(sequencePath), "sequence", 0L, Long.MAX_VALUE - 1L) : 0L;
                long sequence = previous + 1L;
                Properties counter = new Properties();
                counter.setProperty("sequence", Long.toString(sequence));
                write(sequencePath, counter);
                String id = String.format(Locale.ROOT, "%020d-%s", sequence, unique());
                HeldLock held = hold(directory.resolve("ticket-" + id + ".lock"));
                if (held == null) throw new IOException("Cannot create live request");
                try {
                    Properties properties = new Properties();
                    properties.setProperty("id", id);
                    properties.setProperty("pid", pid);
                    properties.setProperty("owner", owner == null ? "" : owner);
                    properties.setProperty("count", Integer.toString(count));
                    properties.setProperty("state", "WAITING");
                    write(meta(held.path), properties);
                    return new LiveRequest(id, held);
                } catch (IOException failure) { release(held); throw failure; }
            }
        });
    }

    public boolean canQueue(int count) throws IOException {
        snapshot();
        return count >= 1 && count <= maxLive;
    }

    public Lease tryAcquireBot() throws IOException {
        return locked(new Operation<Lease>() {
            public Lease run() throws IOException {
                State state = scan();
                int head = state.waiting.isEmpty() ? 0 : state.waiting.get(0).count;
                int liveDemand = Math.min(maxLive, state.liveReserved + head);
                if (state.botActive >= total - liveDemand) return null;
                return physicalLease(state, "BOT", "");
            }
        });
    }

    public Lease tryAcquireLive(final String ticketId) throws IOException {
        if (!validTicketId(ticketId)) throw new IllegalArgumentException("Invalid live ticket identity");
        return locked(new Operation<Lease>() {
            public Lease run() throws IOException {
                State state = scan();
                Ticket ticket = state.tickets.get(ticketId);
                if (ticket == null || !"ACTIVE".equals(ticket.state)) return null;
                if (state.liveFor(ticketId) >= ticket.count) return null;
                return physicalLease(state, "LIVE", ticketId);
            }
        });
    }

    public Snapshot snapshot() throws IOException {
        return locked(new Operation<Snapshot>() {
            public Snapshot run() throws IOException { return new Snapshot(scan(), total, maxLive); }
        });
    }

    public final class Registration implements AutoCloseable {
        private final HeldLock held;
        private Registration(HeldLock held) { this.held = held; }
        public void close() throws IOException { closeHeld(held); }
    }

    public final class Lease implements AutoCloseable {
        public final int slot;
        public final String role;
        public final String ticketId;
        private final HeldLock held;
        private Lease(int slot, String role, String ticketId, HeldLock held) {
            this.slot = slot; this.role = role; this.ticketId = ticketId; this.held = held;
        }
        public void close() throws IOException { closeHeld(held); }
    }

    public final class LiveRequest implements AutoCloseable {
        public final String id;
        private final HeldLock held;
        private LiveRequest(String id, HeldLock held) { this.id = id; this.held = held; }
        public boolean tryActivate() throws IOException {
            return locked(new Operation<Boolean>() {
                public Boolean run() throws IOException {
                    if (held.closed) return false;
                    State state = scan();
                    Ticket ticket = state.tickets.get(id);
                    if (ticket == null) throw new IOException("Live request lock disappeared");
                    if ("ACTIVE".equals(ticket.state)) return true;
                    if (state.waiting.isEmpty() || !id.equals(state.waiting.get(0).id)) return false;
                    int unclaimed = state.liveReserved - state.liveActive;
                    int available = total - state.botActive - state.liveActive - unclaimed;
                    if (state.liveReserved + ticket.count > maxLive || available < ticket.count) return false;
                    Properties properties = read(meta(held.path));
                    properties.setProperty("state", "ACTIVE");
                    write(meta(held.path), properties);
                    return true;
                }
            });
        }
        public void close() throws IOException { closeHeld(held); }
    }

    public static final class Snapshot {
        public final int total, maxLive, botActive, liveActive, liveReserved, liveWaiting, waitingRequests;
        public final Set<String> registeredBotPids;
        private Snapshot(State state, int total, int maxLive) {
            this.total = total; this.maxLive = maxLive;
            this.botActive = state.botActive; this.liveActive = state.liveActive;
            this.liveReserved = state.liveReserved; this.liveWaiting = state.liveWaiting;
            this.waitingRequests = state.waiting.size();
            this.registeredBotPids = Collections.unmodifiableSet(new LinkedHashSet<String>(state.registeredPids));
        }
    }

    private Lease physicalLease(State state, String role, String ticket) throws IOException {
        for (int slot = 1; slot <= total; slot++) {
            if (state.occupiedSlots.contains(slot)) continue;
            HeldLock held = hold(slotPath(slot));
            if (held == null) continue;
            try {
                Properties properties = new Properties();
                properties.setProperty("role", role);
                properties.setProperty("pid", pid);
                properties.setProperty("slot", Integer.toString(slot));
                properties.setProperty("ticket", ticket);
                write(meta(held.path), properties);
                return new Lease(slot, role, ticket, held);
            } catch (IOException failure) { release(held); throw failure; }
        }
        return null;
    }

    private State scan() throws IOException {
        verifyConfiguration();
        State state = new State();
        for (int slot = 1; slot <= total; slot++) {
            Path path = slotPath(slot);
            if (!isHeld(path)) continue;
            Properties properties = read(meta(path));
            number(properties, "pid", 1L, Long.MAX_VALUE);
            if (number(properties, "slot", 1, total) != slot) throw new IOException("Slot identity mismatch");
            String role = required(properties, "role");
            state.occupiedSlots.add(slot);
            if ("BOT".equals(role)) state.botActive++;
            else if ("LIVE".equals(role)) {
                String ticket = required(properties, "ticket");
                if (!validTicketId(ticket)) throw new IOException("Invalid live slot metadata");
                state.liveActive++;
                state.liveCounts.put(ticket, state.liveFor(ticket) + 1);
            } else throw new IOException("Invalid slot role");
        }
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "ticket-*.lock")) {
            for (Path path : paths) {
                if (!isHeld(path)) { discardStale(path); continue; }
                String name = path.getFileName().toString();
                String id = name.substring(7, name.length() - 5);
                if (!validTicketId(id)) throw new IOException("Invalid ticket filename");
                Properties properties = read(meta(path));
                if (!id.equals(required(properties, "id"))) throw new IOException("Ticket identity mismatch");
                number(properties, "pid", 1L, Long.MAX_VALUE);
                int count = (int) number(properties, "count", 1, maxLive);
                String phase = required(properties, "state");
                Ticket ticket = new Ticket(id, count, phase);
                state.tickets.put(id, ticket);
                if ("ACTIVE".equals(phase)) {
                    if (state.liveFor(id) > count) throw new IOException("Live ticket exceeds reservation");
                    if (state.liveReserved > maxLive - count) throw new IOException("Live reservations exceed configured capacity");
                    state.liveReserved += count;
                } else if ("WAITING".equals(phase)) {
                    if (state.liveFor(id) > 0) throw new IOException("Waiting ticket owns physical slots");
                    state.waiting.add(ticket);
                    if (state.liveWaiting > Integer.MAX_VALUE - count) throw new IOException("Live queue is too large");
                    state.liveWaiting += count;
                } else throw new IOException("Invalid ticket state");
            }
        }
        for (Map.Entry<String, Integer> entry : state.liveCounts.entrySet()) {
            if (!state.tickets.containsKey(entry.getKey())) state.liveReserved += entry.getValue();
        }
        if (state.liveReserved > maxLive) throw new IOException("Live reservations exceed configured capacity");
        Collections.sort(state.waiting, new Comparator<Ticket>() {
            public int compare(Ticket first, Ticket second) { return first.id.compareTo(second.id); }
        });
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "bot-*.lock")) {
            for (Path path : paths) {
                if (!isHeld(path)) { discardStale(path); continue; }
                Properties properties = read(meta(path));
                String registeredPid = Long.toString(number(properties, "pid", 1L, Long.MAX_VALUE));
                if (!path.getFileName().toString().startsWith("bot-" + registeredPid + "-")) {
                    throw new IOException("Collector registration identity mismatch");
                }
                state.registeredPids.add(registeredPid);
            }
        }
        return state;
    }

    private void verifyConfiguration() throws IOException {
        Path path = directory.resolve("budget.properties");
        if (!Files.exists(path)) {
            // Initialization is allowed only before any broker records exist.
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    if (!"metadata.lock".equals(entry.getFileName().toString())) {
                        throw new IOException("Shared budget configuration is missing");
                    }
                }
            }
            Properties properties = new Properties();
            properties.setProperty("version", "1");
            properties.setProperty("total", Integer.toString(total));
            properties.setProperty("maxLive", Integer.toString(maxLive));
            write(path, properties);
        }
        Properties properties = read(path);
        if (number(properties, "version", 1, 1) != 1
                || number(properties, "total", 1, 200) != total
                || number(properties, "maxLive", 1, total) != maxLive) {
            throw new IOException("Shared budget configuration does not match");
        }
    }

    private void closeHeld(final HeldLock held) throws IOException {
        // Closing only removes ownership, so it does not need metadata admission.
        // A concurrent process scanning the old record can only overcount it;
        // new owners still require metadata.lock. Keep same-JVM operations
        // serialized, but always return lifetime locks when metadata is unavailable.
        localMutex.lock();
        try { release(held); }
        finally { localMutex.unlock(); }
    }

    private <T> T locked(Operation<T> operation) throws IOException {
        localMutex.lock();
        FileChannel channel = null;
        FileLock lock = null;
        Path path = directory.resolve("metadata.lock");
        try {
            if (UNKNOWN_LOCAL_LOCKS.containsKey(path)) throw new IOException("Untracked local broker lock");
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try { lock = channel.lock(); }
            catch (OverlappingFileLockException overlap) {
                UNKNOWN_LOCAL_LOCKS.put(path, channel); channel = null;
                throw new IOException("Untracked overlapping broker lock", overlap);
            }
            return operation.run();
        } finally {
            try { if (lock != null) lock.release(); }
            finally {
                try { if (channel != null) channel.close(); }
                finally { localMutex.unlock(); }
            }
        }
    }

    private static HeldLock hold(Path path) throws IOException {
        HeldLock existing = HELD.get(path);
        if (existing != null) {
            if (!existing.lock.isValid()) throw new IOException("Lost local broker lock");
            return null;
        }
        if (UNKNOWN_LOCAL_LOCKS.containsKey(path)) throw new IOException("Untracked local broker lock");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException overlap) {
                UNKNOWN_LOCAL_LOCKS.put(path, channel); channel = null;
                throw new IOException("Untracked overlapping broker lock", overlap);
            }
            if (lock == null) return null;
            HeldLock held = new HeldLock(path, channel, lock);
            HELD.put(path, held);
            channel = null;
            return held;
        } finally { if (channel != null) channel.close(); }
    }

    private static boolean isHeld(Path path) throws IOException {
        if (!Files.exists(path)) return false;
        HeldLock held = hold(path);
        if (held == null) return true;
        release(held);
        return false;
    }

    private static void release(HeldLock held) throws IOException {
        if (held.closed) return;
        try { held.lock.release(); }
        finally {
            try { held.channel.close(); }
            finally { held.closed = true; HELD.remove(held.path, held); }
        }
    }

    private void discardStale(Path path) throws IOException {
        Files.deleteIfExists(meta(path));
        Files.deleteIfExists(path);
    }

    private Path slotPath(int slot) { return directory.resolve(String.format(Locale.ROOT, "slot-%03d.lock", slot)); }
    private static Path meta(Path path) { return path.resolveSibling(path.getFileName().toString() + ".properties"); }
    private static String unique() { return UUID.randomUUID().toString().replace("-", ""); }
    private static boolean validTicketId(String id) { return id != null && id.matches("[0-9]{20}-[0-9a-f]{32}"); }

    private static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed broker metadata", malformed); }
        return properties;
    }

    private static void write(Path path, Properties properties) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, null);
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp-" + unique());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(output.toByteArray());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing broker metadata: " + key);
        return value.trim();
    }

    private static long number(Properties properties, String key, long minimum, long maximum) throws IOException {
        String value = required(properties, key);
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid broker metadata: " + key); }
    }

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) value = System.getenv(environment);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int configuredNumber(String property, String environment, int fallback) throws IOException {
        String value = setting(property, environment, Integer.toString(fallback));
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid shared budget setting: " + environment); }
    }

    private interface Operation<T> { T run() throws IOException; }
    private static final class HeldLock {
        final Path path; final FileChannel channel; final FileLock lock;
        volatile boolean closed;
        HeldLock(Path path, FileChannel channel, FileLock lock) { this.path = path; this.channel = channel; this.lock = lock; }
    }
    private static final class Ticket {
        final String id; final int count; final String state;
        Ticket(String id, int count, String state) { this.id = id; this.count = count; this.state = state; }
    }
    private static final class State {
        int botActive, liveActive, liveReserved, liveWaiting;
        final Set<Integer> occupiedSlots = new HashSet<Integer>();
        final Map<String, Integer> liveCounts = new HashMap<String, Integer>();
        final Map<String, Ticket> tickets = new HashMap<String, Ticket>();
        final List<Ticket> waiting = new ArrayList<Ticket>();
        final Set<String> registeredPids = new LinkedHashSet<String>();
        int liveFor(String id) { Integer count = liveCounts.get(id); return count == null ? 0 : count; }
    }
}
