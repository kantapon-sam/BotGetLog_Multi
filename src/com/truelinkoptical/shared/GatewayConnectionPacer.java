package com.truelinkoptical.shared;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** Cross-process SSH start pacing. Does not reserve or change connection-budget slots. */
public final class GatewayConnectionPacer {
    public static final long START_INTERVAL_MS = 500;
    public static final long FAILURE_COOLDOWN_MS = 5000;
    private static final ConcurrentMap<Path, ReentrantLock> MUTEXES = new ConcurrentHashMap<>();
    private final Path file;
    private final ReentrantLock mutex;

    public static GatewayConnectionPacer fromEnvironment(String host, int port, String samUser) throws IOException {
        String directory = System.getProperty("true.log.gatewayPacing.dir");
        if (directory == null || directory.isEmpty()) directory = System.getenv("TRUE_GATEWAY_PACING_DIR");
        if (directory == null || directory.isEmpty()) {
            String home = System.getenv("TRUE_LOG_HOME");
            directory = home != null && !home.isEmpty() ? home + "/gateway-pacing"
                    : System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    ? System.getProperty("user.home") + "/.botgetlog/gateway-pacing"
                    : "/transport/TrueLogConsole/gateway-pacing";
        }
        return new GatewayConnectionPacer(Paths.get(directory), "ssh://" + host.toLowerCase(Locale.ROOT) + ":" + port, samUser);
    }

    public GatewayConnectionPacer(Path directory, String endpoint, String samUser) throws IOException {
        Files.createDirectories(directory);
        privateMode(directory, "rwx------");
        // Keep compatibility with web jobs pinned to the previous Bot runtime.
        String user = samUser.trim().toLowerCase(Locale.ROOT);
        if (user.contains("\\")) user = user.substring(user.lastIndexOf('\\') + 1);
        if (user.contains("@")) user = user.substring(0, user.indexOf('@'));
        file = directory.toRealPath().resolve(sha(endpoint.trim().toLowerCase(Locale.ROOT) + "|" + sha(user)) + ".lock");
        ReentrantLock candidate = new ReentrantLock(true);
        ReentrantLock existing = MUTEXES.putIfAbsent(file, candidate);
        mutex = existing == null ? candidate : existing;
    }

    public void awaitStart() throws IOException, InterruptedException {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Gateway pacing interrupted");
                long delay = update(false);
                if (delay == 0) return;
                Thread.sleep(Math.min(250, delay));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    public void gatewayFailed() throws IOException, InterruptedException {
        try {
            while (update(true) < 0) Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private long update(boolean failed) throws IOException, InterruptedException {
        // POSIX locks can be lost when another descriptor in the same JVM closes.
        // Serialize the entire open/lock/read/write/close sequence within this JVM.
        mutex.lockInterruptibly();
        try (RandomAccessFile state = new RandomAccessFile(file.toFile(), "rw")) {
            privateMode(file, "rw-------");
            FileLock lock;
            try { lock = state.getChannel().tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Uncoordinated Gateway pacing lock", busy); }
            if (lock == null) return failed ? -1 : 50;
            try {
                long now = System.currentTimeMillis();
                long next = state.length() == 8 ? state.readLong() : 0;
                if (next > now + 30000) {
                    next = now + 30000;
                    state.seek(0);
                    state.writeLong(next);
                }
                if (!failed && next > now) return next - now;
                state.seek(0);
                state.writeLong(failed ? Math.max(next, now + FAILURE_COOLDOWN_MS) : now + START_INTERVAL_MS);
                state.setLength(8);
                return 0;
            } finally { lock.release(); }
        } finally { mutex.unlock(); }
    }

    private static String sha(String value) throws IOException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }

    private static void privateMode(Path path, String mode) throws IOException {
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)); }
        catch (UnsupportedOperationException ignored) { }
    }
}
