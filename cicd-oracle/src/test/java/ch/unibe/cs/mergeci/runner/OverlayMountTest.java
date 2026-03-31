package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.BaseTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link OverlayMount} — fuse-overlayfs wrapper.
 * Skipped automatically on systems without fuse-overlayfs.
 */
public class OverlayMountTest extends BaseTest {

    @BeforeAll
    static void checkOverlayAvailable() {
        assumeTrue(OverlayMount.isAvailable(), "fuse-overlayfs not available — skipping overlay tests");
    }

    @Test
    void testCreateAndReadThrough(@TempDir Path tmp) throws IOException {
        // Create a base directory with a file
        Path lower = tmp.resolve("lower");
        Files.createDirectories(lower);
        Files.writeString(lower.resolve("hello.txt"), "world");

        try (OverlayMount overlay = OverlayMount.create(lower, tmp, "test_read")) {
            // File from lowerdir is readable at mountpoint
            Path mounted = overlay.mountPoint().resolve("hello.txt");
            assertTrue(mounted.toFile().exists());
            assertEquals("world", Files.readString(mounted));
        }
    }

    @Test
    void testWriteGoesToUpper(@TempDir Path tmp) throws IOException {
        Path lower = tmp.resolve("lower");
        Files.createDirectories(lower);
        Files.writeString(lower.resolve("original.txt"), "base");

        try (OverlayMount overlay = OverlayMount.create(lower, tmp, "test_write")) {
            // Write a new file at the mountpoint
            Files.writeString(overlay.mountPoint().resolve("new.txt"), "overlay");

            // New file exists in upperdir
            assertTrue(overlay.upperDir().resolve("new.txt").toFile().exists());
            assertEquals("overlay", Files.readString(overlay.upperDir().resolve("new.txt")));

            // Original lowerdir is unmodified
            assertFalse(lower.resolve("new.txt").toFile().exists());
        }
    }

    @Test
    void testCloseUnmountsAndCleansUp(@TempDir Path tmp) throws IOException {
        Path lower = tmp.resolve("lower");
        Files.createDirectories(lower);

        OverlayMount overlay = OverlayMount.create(lower, tmp, "test_cleanup");
        Path mount = overlay.mountPoint();
        Path upper = overlay.upperDir();
        assertTrue(mount.toFile().exists());

        overlay.close();

        // Mount point and upper/work dirs should be cleaned up
        assertFalse(mount.toFile().exists(), "mountpoint should be deleted after close");
        assertFalse(upper.toFile().exists(), "upperdir should be deleted after close");
    }

    @Test
    void testConcurrentOverlaysSameBase(@TempDir Path tmp) throws IOException {
        Path lower = tmp.resolve("lower");
        Files.createDirectories(lower);
        Files.writeString(lower.resolve("shared.txt"), "base");

        try (OverlayMount o1 = OverlayMount.create(lower, tmp, "concurrent_1");
             OverlayMount o2 = OverlayMount.create(lower, tmp, "concurrent_2")) {

            // Both see the shared file
            assertEquals("base", Files.readString(o1.mountPoint().resolve("shared.txt")));
            assertEquals("base", Files.readString(o2.mountPoint().resolve("shared.txt")));

            // Writes are isolated
            Files.writeString(o1.mountPoint().resolve("only_in_1.txt"), "one");
            Files.writeString(o2.mountPoint().resolve("only_in_2.txt"), "two");

            assertTrue(o1.mountPoint().resolve("only_in_1.txt").toFile().exists());
            assertFalse(o1.mountPoint().resolve("only_in_2.txt").toFile().exists());

            assertTrue(o2.mountPoint().resolve("only_in_2.txt").toFile().exists());
            assertFalse(o2.mountPoint().resolve("only_in_1.txt").toFile().exists());

            // Lower dir untouched
            assertFalse(lower.resolve("only_in_1.txt").toFile().exists());
            assertFalse(lower.resolve("only_in_2.txt").toFile().exists());
        }
    }
}
