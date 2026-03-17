package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.util.Optional;

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
 * <p>Variants that timed out or whose compilation result is unavailable carry no score
 * and are excluded from quality comparisons. They are still counted in
 * started/finished variant statistics.
 */
public record VariantScore(int successfulModules, int passedTests) implements Comparable<VariantScore> {

    /**
     * Returns a score for the variant, or empty if the build timed out or has no data.
     */
    public static Optional<VariantScore> of(CompilationResult cr, TestTotal tt) {
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

        return Optional.of(new VariantScore(modules, tests));
    }

    @Override
    public int compareTo(VariantScore other) {
        int cmp = Integer.compare(this.successfulModules, other.successfulModules);
        if (cmp != 0) return cmp;
        return Integer.compare(this.passedTests, other.passedTests);
    }

    public boolean isBetterThan(VariantScore other) {
        return compareTo(other) > 0;
    }

    public boolean isAtLeastAsGoodAs(VariantScore other) {
        return compareTo(other) >= 0;
    }
}
