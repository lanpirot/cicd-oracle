package ch.unibe.cs.mergeci.runner;

/**
 * Runner that builds variant directories just-in-time and cleans them up immediately.
 * This reduces disk usage by only keeping one (or a few) variant directories at a time.
 */
public interface IJustInTimeRunner {
    /**
     * Run tests on variants with just-in-time directory creation and immediate cleanup.
     *
     * @param context Variant build context containing metadata for all variants
     * @param builder VariantProjectBuilder for building directories and collecting results
     * @return Execution time statistics
     */
    ExperimentTiming run(VariantBuildContext context, VariantProjectBuilder builder) throws Exception;
}
