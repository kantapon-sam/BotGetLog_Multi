package com.truelinkoptical.shared;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** Coordinate workbook replacements with Control Console's SidecarFileLock. */
public final class DiscoveryWorkbookLock {
    private DiscoveryWorkbookLock() {}
    public interface Operation<T> { T run() throws Exception; }

    public static synchronized <T> T withLock(File workbook, Operation<T> operation) throws Exception {
        try (FileChannel channel = FileChannel.open(Paths.get(workbook.getCanonicalPath() + ".mutation.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            return operation.run();
        }
    }
}
