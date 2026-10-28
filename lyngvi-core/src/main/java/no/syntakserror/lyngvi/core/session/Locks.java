package no.syntakserror.lyngvi.core.session;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import no.syntakserror.lyngvi.core.api.ErrorCode;
import no.syntakserror.lyngvi.core.api.SandboxException;
import no.syntakserror.lyngvi.core.config.Paths;

/**
 * Cross-process locks on files under {@code state/locks/} (spec §4.4).
 *
 * <p>A lock is a {@link FileLock}, released by the operating system when the
 * process dies, so there are no stale locks. Within one JVM a
 * {@link ReentrantLock} per file serialises threads first, because the JVM
 * rejects overlapping file locks from the same process.
 */
public final class Locks {

    private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();

    private Locks() {}

    /** The whole-sandbox lock: rebuild-config, up, down, migrate. */
    public static Held sandbox(Paths paths) {
        return acquire(paths.locks().resolve("sandbox.lock"));
    }

    /** The per-session lock: new, ask, review, apply, remove. */
    public static Held session(Paths paths, String sessionId) {
        return acquire(paths.locks().resolve(sessionId + ".lock"));
    }

    private static Held acquire(Path file) {
        Path key = file.toAbsolutePath().normalize();
        ReentrantLock local = IN_PROCESS.computeIfAbsent(key, k -> new ReentrantLock());
        local.lock();
        if (local.getHoldCount() > 1) {
            // Re-entrant: this thread already holds the file lock through an outer Held.
            return new Held(local, null, null);
        }
        try {
            Files.createDirectories(key.getParent());
            FileChannel channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return new Held(local, channel, lock);
        } catch (IOException e) {
            local.unlock();
            throw new SandboxException(ErrorCode.INTERNAL, "Cannot lock " + key + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            local.unlock();
            throw e;
        }
    }

    /** A held lock; closing releases it. */
    public static final class Held implements AutoCloseable {
        private final ReentrantLock local;
        private final FileChannel channel;
        private final FileLock lock;

        private Held(ReentrantLock local, FileChannel channel, FileLock lock) {
            this.local = local;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                if (lock != null) lock.release();
                if (channel != null) channel.close();
            } catch (IOException ignored) {
                // nothing sensible to do; the OS releases the lock with the channel
            } finally {
                local.unlock();
            }
        }
    }
}
