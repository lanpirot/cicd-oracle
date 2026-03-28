package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.model.patterns.PatternHeuristics;
import ch.unibe.cs.mergeci.model.patterns.PatternStrategy;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Lexicographic quality score for a merge variant.
 *
 * <p>Primary key: number of successfully built modules (higher = better).
 * For single-module projects the reactor block is absent, so a global BUILD SUCCESS
 * is normalised to 1 module to keep single- and multi-module results comparable.
 *
 * <p>Secondary key: number of passing tests (higher = better).
 * If test reports are absent the test count defaults to 0, which is still dominated
 * by the module count and therefore does not distort cross-variant comparisons.
 *
 * <p>Tertiary key: simplicity score (higher = better, i.e. simpler/more common strategy).
 * Product of the global-row pattern weights for each conflict chunk's resolution.
 * A variant whose chunks all use high-probability patterns (e.g. all OURS) scores higher
 * than one using rare compound patterns. Only meaningful when comparing variants within
 * the same merge (same chunk count).
 *
 * <p>Variants that timed out or whose compilation result is unavailable carry no score
 * and are excluded from quality comparisons. They are still counted in
 * started/finished variant statistics.
 */
public record VariantScore(int successfulModules, int passedTests,
                           double simplicityScore) implements Comparable<VariantScore> {

    /**
     * Returns a score for the variant, or empty if the build timed out or has no data.
     * Simplicity score defaults to 0 (no pattern data available).
     */
    public static Optional<VariantScore> of(CompilationResult cr, TestTotal tt) {
        return of(cr, tt, 0.0);
    }

    /**
     * Returns a score for the variant with an explicit simplicity score,
     * or empty if the build timed out or has no data.
     */
    public static Optional<VariantScore> of(CompilationResult cr, TestTotal tt,
                                            double simplicityScore) {
        if (cr == null) return Optional.empty();
        CompilationResult.Status status = cr.getBuildStatus();
        if (status == null || status == CompilationResult.Status.TIMEOUT) return Optional.empty();

        // Single-module projects have no reactor block, so getNumberOfSuccessfulModules() == 0
        // even on success.  Normalise to 1 so SUCCESS > FAILURE for single-module builds.
        int modules = (status == CompilationResult.Status.SUCCESS)
                ? Math.max(1, cr.getNumberOfSuccessfulModules())
                : cr.getNumberOfSuccessfulModules();

        int tests = (tt != null && tt.isHasData())
                ? tt.getRunNum() - tt.getErrorsNum() - tt.getFailuresNum()
                : 0;

        return Optional.of(new VariantScore(modules, tests, simplicityScore));
    }

    /**
     * Compute the simplicity score for a variant's conflict patterns using the global
     * pattern distribution. Each chunk's pattern is looked up in the global row and
     * all weights are multiplied together.
     *
     * @param conflictPatterns variant's conflict patterns (file → list of chunk patterns)
     * @param globalWeights    map from meso-strategy name (e.g. "OURS", "OURSBASE") to weight,
     *                         built via {@link #buildGlobalWeightMap(PatternHeuristics)}
     * @return product of per-chunk weights (higher = simpler/more common strategy)
     * @throws IllegalStateException if a chunk pattern is not found in the global distribution
     */
    public static double computeSimplicityScore(Map<String, List<String>> conflictPatterns,
                                                Map<String, Double> globalWeights) {
        if (conflictPatterns == null || conflictPatterns.isEmpty()) {
            return 0.0;
        }

        double product = 1.0;
        for (String chunkPattern : conflictPatterns.values().stream()
                .flatMap(Collection::stream).toList()) {
            // Convert colon notation (OURS:BASE) to concatenated form (OURSBASE)
            String key = chunkPattern.replace(":", "");
            Double weight = globalWeights.get(key);
            if (weight == null) {
                throw new IllegalStateException(
                        "Pattern '" + chunkPattern + "' (key '" + key
                                + "') not found in global pattern distribution. "
                                + "Available patterns: " + globalWeights.keySet());
            }
            product *= weight;
        }
        return product;
    }

    /**
     * Build a lookup map from meso-strategy name to weight from the global row
     * of a {@link PatternHeuristics} instance. Each global-row entry is expected to be
     * {@code weight*(100*PATTERN)}.
     */
    public static Map<String, Double> buildGlobalWeightMap(PatternHeuristics heuristics) {
        return heuristics.getGlobalStrategies().stream()
                .collect(Collectors.toMap(
                        s -> s.getSubPatterns().getFirst().getPattern(),
                        PatternStrategy::getWeight));
    }

    @Override
    public int compareTo(VariantScore other) {
        int cmp = Integer.compare(this.successfulModules, other.successfulModules);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.passedTests, other.passedTests);
        if (cmp != 0) return cmp;
        return Double.compare(this.simplicityScore, other.simplicityScore);
    }

    public boolean isBetterThan(VariantScore other) {
        return compareTo(other) > 0;
    }

    public boolean isAtLeastAsGoodAs(VariantScore other) {
        return compareTo(other) >= 0;
    }
}
