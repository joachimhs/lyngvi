package no.syntakserror.lyngvi.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import no.syntakserror.lyngvi.core.config.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocksTest {

    @Test
    void createsTheLockDirectoryAndFile(@TempDir Path dir) throws Exception {
        Paths paths = Paths.of(dir);

        try (Locks.Held ignored = Locks.sandbox(paths)) {
            assertThat(paths.locks().resolve("sandbox.lock")).exists();
        }
    }

    @Test
    void secondHolderWaitsUntilTheFirstReleases(@TempDir Path dir) throws Exception {
        Paths paths = Paths.of(dir);
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = new Thread(() -> {
            try (Locks.Held ignored = Locks.session(paths, "abc")) {
                events.add("first-acquired");
                firstHeld.countDown();
                release.await(5, TimeUnit.SECONDS);
                events.add("first-released");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread second = new Thread(() -> {
            try {
                firstHeld.await(5, TimeUnit.SECONDS);
                try (Locks.Held ignored = Locks.session(paths, "abc")) {
                    events.add("second-acquired");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        first.start();
        second.start();
        firstHeld.await(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        assertThat(events).containsExactly("first-acquired");

        release.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(events).containsExactly("first-acquired", "first-released", "second-acquired");
    }

    @Test
    void differentSessionsDoNotBlockEachOther(@TempDir Path dir) throws Exception {
        Paths paths = Paths.of(dir);

        try (Locks.Held a = Locks.session(paths, "a"); Locks.Held b = Locks.session(paths, "b")) {
            assertThat(Files.list(paths.locks()).count()).isEqualTo(2);
        }
    }
}
