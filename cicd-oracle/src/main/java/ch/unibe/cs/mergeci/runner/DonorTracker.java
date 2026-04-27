package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final AtomicReference<VariantScore> donorScore = new AtomicReference<>();
    private final AtomicReference<String> donorKey = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> donorPatterns = new AtomicReference<>();
    private final AtomicReference<TestTotal> donorTestTotal = new AtomicReference<>();
    private final AtomicInteger bestSuccessfulModules = new AtomicInteger(0);

    /**
     * Atomic snapshot of donor identity + TestTotal at a single instant. Use this — rather
     * than separate getter calls — when the caller needs path and testTotal that belong to
     * the SAME donor (e.g. cache-warm code that copies from {@code path} and then needs the
     * matching {@code testTotal} for inheritance after a cache-skip surefire run).
     */
    public record DonorSnapshot(Path path, String key, TestTotal testTotal) {}

    /** Current donor path, or null if no donor has been promoted yet. */
    public Path getDonorPath() {
        return donorPath.get();
    }

    /** Key (identifier) of the current donor variant, or null. */
    public String getDonorKey() {
        return donorKey.get();
    }

    /** Score of the current donor, or null. */
    public VariantScore getDonorScore() {
        return donorScore.get();
    }

    /** TestTotal of the current donor, or null. May lag {@link #getDonorPath()} by one
     *  rotation under contention — prefer {@link #getDonorSnapshot()} when consistency matters. */
    public TestTotal getDonorTestTotal() {
        return donorTestTotal.get();
    }

    /** Consistent snapshot of donor identity + TestTotal taken under the promotion lock. */
    public synchronized DonorSnapshot getDonorSnapshot() {
        return new DonorSnapshot(donorPath.get(), donorKey.get(), donorTestTotal.get());
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
     * Promote a candidate as the new donor if it outscores the current one.
     *
     * <p>Ranking uses the same {@link VariantScore} comparator as the end-of-run "best
     * variant" selection — modules first, then tests, then (unused in the current 2-arg
     * factory) simplicity and variantIndex. Keeping both places on one comparator means
     * donor ≡ best-so-far, and a change to ranking semantics touches only one spot.
     *
     * @param candidatePath path to the candidate variant directory (or overlay mountpoint)
     * @param candidateCr   compilation result of the candidate
     * @param candidateTt   test totals of the candidate (may be null)
     * @param candidateKey  human-readable key for the candidate (used for logging)
     * @return the old donor path that the caller should delete/close, or null if no
     *         promotion happened (or the candidate has no usable score)
     */
    public Path promoteDonorIfBetter(Path candidatePath, CompilationResult candidateCr,
                                     TestTotal candidateTt, String candidateKey) {
        Optional<VariantScore> candidateScoreOpt = VariantScore.of(candidateCr, candidateTt);
        if (candidateScoreOpt.isEmpty() || !isDonorUsable(candidateCr)) return null;
        VariantScore candidateScore = candidateScoreOpt.get();

        synchronized (this) {
            VariantScore currentScore = donorScore.get();
            if (currentScore != null && !candidateScore.isBetterThan(currentScore)) {
                return null;
            }

            String oldKey = donorKey.get();
            Path oldDonor = donorPath.get();
            donorPath.set(candidatePath);
            donorScore.set(candidateScore);
            donorKey.set(candidateKey);
            donorTestTotal.set(candidateTt);
            LOG.info("[cache-donor] PROMOTED variant {} as new donor ({} → {}, prev donor: {})",
                    candidateKey, fmt(currentScore), fmt(candidateScore), oldKey);
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

    private static String fmt(VariantScore s) {
        if (s == null) return "none";
        return s.successfulModules() + "m/" + s.passedTests() + "t";
    }
}
