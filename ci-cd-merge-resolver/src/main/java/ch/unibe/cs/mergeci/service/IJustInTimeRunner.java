package ch.unibe.cs.mergeci.service;

/**
 * Runner that builds variant directories just-in-time and cleans them up immediately.
 * This reduces disk usage by only keeping one (or a few) variant directories at a time.
 */
public interface IJustInTimeRunner {
    /**
     * Run tests on variants with just-in-time directory creation and immediate cleanup.
     *
     * @param context Variant build context containing metadata for all variants
     * @param analyzer MergeAnalyzer for building directories and collecting results
     * @return Execution time statistics
     */
    RunExecutionTIme run(VariantBuildContext context, MergeAnalyzer analyzer) throws Exception;
}
