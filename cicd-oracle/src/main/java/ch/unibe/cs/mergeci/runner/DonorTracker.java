package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private static final Logger LOG = LoggerFactory.getLogger(DonorTracker.class);

    private final AtomicReference<Path> donorPath = new AtomicReference<>();
    private final AtomicReference<CompilationResult> donorCr = new AtomicReference<>();
    private final AtomicReference<String> donorKey = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> donorPatterns = new AtomicReference<>();
    private final AtomicInteger bestSuccessfulModules = new AtomicInteger(0);

    /** Current donor path, or null if no donor has been promoted yet. */
    public Path getDonorPath() {
        return donorPath.get();
    }

    /** Key (identifier) of the current donor variant, or null. */
    public String getDonorKey() {
        return donorKey.get();
    }

    /** Compilation result of the current donor, or null. */
    public CompilationResult getDonorCompilationResult() {
        return donorCr.get();
    }

    /** Resolution patterns of the current donor, or null. */
    public Map<String, List<String>> getDonorPatterns() {
        return donorPatterns.get();
    }

    /** Store the resolution patterns for the current donor. */
    public void setDonorPatterns(Map<String, List<String>> patterns) {
        donorPatterns.set(patterns);
    }

    /**
     * Compute chunk-level similarity between two pattern maps (fraction of matching choices).
     * Returns a value in [0.0, 1.0], or -1 if comparison is not possible.
     */
    public static double computePatternSimilarity(Map<String, List<String>> a,
                                                   Map<String, List<String>> b) {
        if (a == null || b == null) return -1;
        int matches = 0, total = 0;
        for (String file : a.keySet()) {
            List<String> pA = a.get(file);
            List<String> pB = b.getOrDefault(file, List.of());
            int len = Math.max(pA.size(), pB.size());
            for (int i = 0; i < len; i++) {
                total++;
                if (i < pA.size() && i < pB.size() && pA.get(i).equals(pB.get(i))) {
                    matches++;
                }
            }
        }
        // also count files in b but not in a
        for (String file : b.keySet()) {
            if (!a.containsKey(file)) {
                total += b.get(file).size();
            }
        }
        return total == 0 ? 1.0 : (double) matches / total;
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
        return promoteDonorIfBetter(candidatePath, candidateCr, null);
    }

    /**
     * Promote a candidate as the new donor if it compiled more modules than the current one.
     *
     * @param candidatePath path to the candidate variant directory (or overlay mountpoint)
     * @param candidateCr   compilation result of the candidate
     * @param candidateKey  human-readable key for the candidate (used for logging)
     * @return the old donor path that the caller should delete/close, or null if no promotion
     *         happened or there was no previous donor
     */
    public Path promoteDonorIfBetter(Path candidatePath, CompilationResult candidateCr,
                                     String candidateKey) {
        if (!isDonorUsable(candidateCr)) return null;

        synchronized (this) {
            CompilationResult currentCr = donorCr.get();
            if (!isBetterDonor(candidateCr, currentCr)) {
                LOG.info("[cache-donor] variant {} NOT promoted — {} successful modules vs donor {}'s {}",
                        candidateKey, candidateCr.getSuccessfulModules(),
                        donorKey.get(), currentCr != null ? currentCr.getSuccessfulModules() : 0);
                return null;
            }

            String oldKey = donorKey.get();
            Path oldDonor = donorPath.get();
            int oldModules = currentCr != null ? currentCr.getSuccessfulModules() : 0;
            donorPath.set(candidatePath);
            donorCr.set(candidateCr);
            donorKey.set(candidateKey);
            LOG.info("[cache-donor] PROMOTED variant {} as new donor ({} → {} successful modules, prev donor: {})",
                    candidateKey, oldModules, candidateCr.getSuccessfulModules(), oldKey);
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
