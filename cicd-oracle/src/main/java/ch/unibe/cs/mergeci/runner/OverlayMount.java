package ch.unibe.cs.mergeci.runner;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AutoCloseable wrapper for a single fuse-overlayfs mount.
 *
 * <p>Creates a CoW overlay backed by a shared read-only {@code lowerDir}. Writes land in
 * a per-variant {@code upperDir}; the {@code mountPoint} is where Maven sees the merged
 * view.  Closing the mount unmounts the FUSE filesystem, kills any processes whose cwd is
 * inside the mountpoint, and deletes the upper/work/mount directories.
 *
 * <p><b>Shared with plugin:</b> the IntelliJ plugin calls {@link #isAvailable},
 * {@link #create}, {@link #cleanupStaleMounts}, {@link #close}, and {@link #mountPoint}.
 */
public class OverlayMount implements AutoCloseable {
    private final Path mountPoint;
    private final Path upperDir;
    private final Path workDir;
    private volatile boolean closed;

    private OverlayMount(Path mountPoint, Path upperDir, Path workDir) {
        this.mountPoint = mountPoint;
        this.upperDir = upperDir;
        this.workDir = workDir;
    }

    /**
     * Create and mount a fuse-overlayfs overlay.
     *
     * @param lowerDir  read-only base directory (shared across variants)
     * @param mountBase parent directory for mount/upper/work dirs
     * @param name      unique name used as directory prefix (e.g. "project_1")
     * @return a mounted overlay; call {@link #close()} when done
     */
    public static OverlayMount create(Path lowerDir, Path mountBase, String name) throws IOException {
        Path mount = mountBase.resolve(name);
        Path upper = mountBase.resolve(name + "_upper");
        Path work  = mountBase.resolve(name + "_work");

        Files.createDirectories(mount);
        Files.createDirectories(upper);
        Files.createDirectories(work);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "fuse-overlayfs",
                    "-o", "lowerdir=" + lowerDir + ",upperdir=" + upper + ",workdir=" + work,
                    mount.toString());
            pb.inheritIO();
            Process p = pb.start();
            if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
                // Clean up partial state
                deleteQuietly(mount);
                deleteQuietly(upper);
                deleteQuietly(work);
                throw new IOException("fuse-overlayfs failed (exit " + p.exitValue() + ") for " + name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(mount);
            deleteQuietly(upper);
            deleteQuietly(work);
            throw new IOException("Interrupted while mounting overlay " + name, e);
        }

        return new OverlayMount(mount, upper, work);
    }

    /** Check whether fuse-overlayfs is available on the system. */
    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("fuse-overlayfs", "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes(); // drain
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clean up stale fuse-overlayfs mounts under {@code overlayTmpDir} from a prior crash.
     * Reads {@code /proc/mounts} and unmounts any fuse-overlayfs entries whose mountpoint
     * starts with the given directory.
     */
    public static void cleanupStaleMounts(Path overlayTmpDir) {
        List<String> staleMounts = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Format: <device> <mountpoint> <type> <options> ...
                String[] parts = line.split("\\s+");
                if (parts.length >= 3 && "fuse-overlayfs".equals(parts[2])
                        && parts[1].startsWith(overlayTmpDir.toString())) {
                    staleMounts.add(parts[1]);
                }
            }
        } catch (IOException e) {
            // /proc/mounts not readable — nothing to clean up
            return;
        }

        for (String mountpoint : staleMounts) {
            System.err.println("[overlay] cleaning up stale mount: " + mountpoint);
            try {
                Process p = new ProcessBuilder("fusermount3", "-u", mountpoint)
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("[overlay] failed to unmount " + mountpoint + ": " + e.getMessage());
            }
        }
    }

    public Path mountPoint() { return mountPoint; }
    public Path upperDir() { return upperDir; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // Kill processes whose cwd is inside the mountpoint so unmount doesn't get "device busy"
        killProcessesIn(mountPoint);

        // Retry unmount with backoff
        boolean unmounted = false;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Process p = new ProcessBuilder("fusermount3", "-u", mountPoint.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    unmounted = true;
                    break;
                }
            } catch (Exception e) {
                // retry
            }
            try { Thread.sleep(500L * (attempt + 1)); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!unmounted) {
            System.err.println("[overlay] WARNING: could not unmount " + mountPoint);
        }

        deleteQuietly(mountPoint);
        deleteQuietly(upperDir);
        deleteQuietly(workDir);
    }

    /**
     * Kill processes whose current working directory is inside the given path.
     * Scans /proc/[pid]/cwd symlinks.
     */
    private static void killProcessesIn(Path dir) {
        try {
            String dirStr = dir.toRealPath().toString();
            Path procDir = Path.of("/proc");
            if (!procDir.toFile().exists()) return;

            for (java.io.File entry : procDir.toFile().listFiles()) {
                if (!entry.isDirectory()) continue;
                try {
                    Integer.parseInt(entry.getName()); // only pid dirs
                } catch (NumberFormatException e) { continue; }

                try {
                    Path cwd = Path.of("/proc", entry.getName(), "cwd").toRealPath();
                    if (cwd.toString().startsWith(dirStr)) {
                        long pid = Long.parseLong(entry.getName());
                        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
                    }
                } catch (Exception ignored) {
                    // permission denied, process already gone, etc.
                }
            }
            // Give processes a moment to die
            Thread.sleep(200);
        } catch (Exception ignored) {}
    }

    private static void deleteQuietly(Path dir) {
        try {
            org.apache.commons.io.FileUtils.deleteDirectory(dir.toFile());
        } catch (Exception ignored) {}
    }
}
