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
     * Clean up stale entries under {@code overlayTmpDir} — currently-mounted fuse-overlayfs
     * overlays, leftover mountpoint dirs, {@code *_upper}/{@code *_work}/{@code *_base} dirs.
     *
     * <p>Attempts regular + lazy unmount for each stale mount; unmount may be "queued" but
     * the mount can remain in {@code /proc/mounts} if a long-lived daemon (e.g. mvnd) still
     * holds open FDs. After unmount attempts, sweeps every entry under the dir <em>except</em>
     * those still listed as mounted in {@code /proc/mounts} — those would otherwise cause a
     * pathological recursive delete through the live FUSE view.
     */
    public static void cleanupStaleMounts(Path overlayTmpDir) {
        if (!Files.isDirectory(overlayTmpDir)) return;

        for (String mountpoint : listFuseMountsUnder(overlayTmpDir)) {
            Path mp = Path.of(mountpoint);
            tryUnmount(mp, false);
            tryUnmount(mp, true); // lazy fallback even if regular succeeded (idempotent)
        }

        // Re-read mounts after the unmount attempts — mvnd may still hold FDs, keeping the
        // fuse process alive and the mount listed. We must NOT descend into those dirs.
        java.util.Set<String> stillMounted = new java.util.HashSet<>(listFuseMountsUnder(overlayTmpDir));

        try (java.util.stream.Stream<Path> entries = Files.list(overlayTmpDir)) {
            entries.filter(p -> !stillMounted.contains(p.toString()))
                   .forEach(OverlayMount::deleteQuietly);
        } catch (IOException ignored) {}
    }

    /**
     * Collect fuse-overlayfs mountpoints under {@code dir} from /proc/mounts.
     *
     * <p>/proc/mounts columns: {@code <device> <mountpoint> <fstype> ...}. For fuse-overlayfs
     * the device is {@code fuse-overlayfs} and the fstype is {@code fuse.fuse-overlayfs}
     * (note the {@code fuse.} prefix — matching on the fstype as {@code "fuse-overlayfs"}
     * silently matched nothing, which is how this method's earlier version leaked mounts).
     */
    private static List<String> listFuseMountsUnder(Path dir) {
        List<String> mounts = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3 && "fuse-overlayfs".equals(parts[0])
                        && parts[1].startsWith(dir.toString())) {
                    mounts.add(parts[1]);
                }
            }
        } catch (IOException ignored) {}
        return mounts;
    }

    /** True iff {@code mountPoint} is listed as a mount in /proc/mounts. */
    private static boolean isMounted(Path mountPoint) {
        String target = mountPoint.toString();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && target.equals(parts[1])) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }

    public Path mountPoint() { return mountPoint; }
    public Path upperDir() { return upperDir; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // Kill processes whose cwd is inside the mountpoint so unmount doesn't get "device busy".
        // Note: mvnd daemons have a stable cwd outside the mount (see commit 61946c5) and are
        // intentionally kept alive — they won't match here even though their open FDs keep the
        // mount busy. That's handled by the lazy-unmount fallback below.
        killProcessesIn(mountPoint);

        // Regular unmount first; if that fails (e.g. a live mvnd daemon is holding a JAR open),
        // fall back to a lazy unmount which queues the detach. The fuse-overlayfs process
        // only actually exits when the last FD closes, so with mvnd keeping FDs open the
        // mount entry may linger in /proc/mounts after `-uz` returns 0.
        tryUnmount(mountPoint, false);
        if (isMounted(mountPoint)) tryUnmount(mountPoint, true);

        if (isMounted(mountPoint)) {
            // Recursive delete through a live FUSE mount would descend the entire lower layer
            // (the full ~/.m2/repository) and issue one FUSE syscall per file — hours of I/O.
            // And rmdir on the mountpoint would fail with EBUSY. Skip; cleanupStaleMounts()
            // sweeps it on the next run after daemons release their FDs.
            System.err.println("[overlay] WARNING: could not unmount " + mountPoint
                    + " — skipping delete (will be cleaned on next start)");
            return;
        }

        deleteQuietly(mountPoint);
        deleteQuietly(upperDir);
        deleteQuietly(workDir);
    }

    /**
     * Attempt to unmount {@code mountPoint}. With {@code lazy=false}, retries up to 5 times
     * with backoff. With {@code lazy=true}, issues a single {@code -uz} (MNT_DETACH) call.
     */
    private static boolean tryUnmount(Path mountPoint, boolean lazy) {
        String flag = lazy ? "-uz" : "-u";
        int attempts = lazy ? 1 : 5;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                Process p = new ProcessBuilder("fusermount3", flag, mountPoint.toString())
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return true;
                }
            } catch (Exception e) {
                // retry
            }
            if (attempt < attempts - 1) {
                try { Thread.sleep(500L * (attempt + 1)); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
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
