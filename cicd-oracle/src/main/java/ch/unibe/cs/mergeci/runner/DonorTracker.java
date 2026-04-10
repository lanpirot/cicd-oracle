package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe tracker for the "evolving donor" optimization: the best-performing
 * variant so far donates its compiled artifacts (target/ dirs, .cache/) to subsequent
 * variants, creating an iterative improvement chain with progressively warmer caches.
 *
 * <p>Used by both the experiment pipeline ({@link MavenExecutionFactory}) and the
 * IntelliJ plugin orchestrator.
 */
public class DonorTracker {
    private final AtomicReference<Path> donorPath = new AtomicReference<>();
    private final AtomicReference<CompilationResult> donorCr = new AtomicReference<>();
    private final AtomicInteger bestSuccessfulModules = new AtomicInteger(0);

    /** Current donor path, or null if no donor has been promoted yet. */
    public Path getDonorPath() {
        return donorPath.get();
    }

    /** Highest successful-module count seen across all variants (including non-donors). */
    public int getBestSuccessfulModules() {
        return bestSuccessfulModules.get();
    }

    /**
     * Record that a variant compiled this many modules successfully.
     * Updates the high-water mark used by the two-phase gate.
     */
    public void updateBestModules(int modules) {
        bestSuccessfulModules.updateAndGet(old -> Math.max(old, modules));
    }

    /**
     * Promote a candidate as the new donor if it compiled more modules than the current one.
     *
     * @param candidatePath path to the candidate variant directory (or overlay mountpoint)
     * @param candidateCr   compilation result of the candidate
     * @return the old donor path that the caller should delete/close, or null if no promotion
     *         happened or there was no previous donor
     */
    public Path promoteDonorIfBetter(Path candidatePath, CompilationResult candidateCr) {
        if (!isDonorUsable(candidateCr)) return null;

        synchronized (this) {
            CompilationResult currentCr = donorCr.get();
            if (!isBetterDonor(candidateCr, currentCr)) return null;

            Path oldDonor = donorPath.get();
            donorPath.set(candidatePath);
            donorCr.set(candidateCr);
            return oldDonor;
        }
    }

    /**
     * Returns true when a variant produced at least one successful module,
     * meaning subsequent variants can benefit from copying its target directories.
     */
    public static boolean isDonorUsable(CompilationResult cr) {
        if (cr == null) return false;
        return cr.getSuccessfulModules() > 0
                || cr.getBuildStatus() == CompilationResult.Status.SUCCESS;
    }

    /**
     * Returns true when candidate should replace the current donor (more modules compiled).
     * A consumer that was built from the previous donor can itself become the new donor,
     * creating an iterative improvement chain with progressively warmer caches.
     */
    public static boolean isBetterDonor(CompilationResult candidate, CompilationResult current) {
        if (!isDonorUsable(candidate)) return false;
        if (current == null) return true;
        return candidate.getSuccessfulModules() > current.getSuccessfulModules();
    }
}
