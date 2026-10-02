package com.java.botgetlog.truecorp;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** One writer per management IP, shared by all collection and archive paths. */
final class NodeCollectionLease implements AutoCloseable {
    private static final ConcurrentHashMap<Path, Entry> ENTRIES = new ConcurrentHashMap<>();
    private final Entry entry;
    private boolean closed;

    private static final class Entry {
        final ReentrantLock mutex = new ReentrantLock(true);
        FileChannel channel;
        FileLock fileLock;
    }

    private NodeCollectionLease(Entry entry) { this.entry = entry; }

    static NodeCollectionLease acquire(Path logDirectory, String ip, BooleanSupplier stopped)
            throws IOException, InterruptedException {
        Path path = lockPath(logDirectory, ip);
        Entry entry = ENTRIES.computeIfAbsent(path, key -> new Entry());
        boolean waitingLogged = false;
        while (true) {
            if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Collection cancelled while waiting for IP ownership");
            }
            if (entry.mutex.tryLock(100, TimeUnit.MILLISECONDS)) {
                NodeCollectionLease lease = claim(path, entry);
                if (lease != null) return lease;
            }
            if (!waitingLogged) {
                System.out.println("[NODE-LOCK] Waiting for current collection of " + ip);
                waitingLogged = true;
            }
            Thread.sleep(100);
        }
    }

    static NodeCollectionLease tryAcquire(Path logDirectory, String ip) throws IOException {
        Path path = lockPath(logDirectory, ip);
        Entry entry = ENTRIES.computeIfAbsent(path, key -> new Entry());
        return entry.mutex.tryLock() ? claim(path, entry) : null;
    }

    static boolean ownedByCurrentThread(Path logDirectory, String ip) throws IOException {
        Entry entry = ENTRIES.get(lockPath(logDirectory, ip));
        return entry != null && entry.mutex.isHeldByCurrentThread();
    }

    private static Path lockPath(Path logDirectory, String rawIp) throws IOException {
        String ip = rawIp == null ? "" : rawIp.trim().toLowerCase(Locale.ROOT);
        if (ip.isEmpty() || !ip.matches("[0-9a-f:.]+")) throw new IOException("Invalid collection IP");
        Path root = logDirectory.toAbsolutePath().normalize().resolveSibling("System_Log")
                .resolve("node-collection-locks");
        Files.createDirectories(root);
        return root.toRealPath().resolve(ip.replace(':', '_') + ".lock");
    }

    // Only the outer acquisition opens a channel. Closing a second descriptor
    // for a POSIX-locked file could otherwise release another owner's lock.
    private static NodeCollectionLease claim(Path path, Entry entry) throws IOException {
        if (entry.mutex.getHoldCount() > 1) return new NodeCollectionLease(entry);
        boolean acquired = false;
        try {
            entry.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { entry.fileLock = entry.channel.tryLock(); }
            catch (OverlappingFileLockException busy) { entry.fileLock = null; }
            acquired = entry.fileLock != null;
            return acquired ? new NodeCollectionLease(entry) : null;
        } finally {
            if (!acquired) {
                try { if (entry.channel != null) entry.channel.close(); }
                finally { entry.channel = null; entry.mutex.unlock(); }
            }
        }
    }

    @Override public void close() {
        if (closed) return;
        if (!entry.mutex.isHeldByCurrentThread()) throw new IllegalStateException("Wrong collection owner");
        closed = true;
        try {
            if (entry.mutex.getHoldCount() == 1) {
                try { if (entry.fileLock != null) entry.fileLock.release(); }
                finally {
                    entry.fileLock = null;
                    if (entry.channel != null) entry.channel.close();
                    entry.channel = null;
                }
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("Cannot release collection ownership", failure);
        } finally { entry.mutex.unlock(); }
        // Never delete lock files: a waiter may already have the inode open.
    }
}
