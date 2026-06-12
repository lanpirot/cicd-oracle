package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"processed", "mode", "projectName", "mergeCommit", "parent1", "parent2",
        "numConflictFiles", "numJavaConflictFiles", "numConflictChunks",
        "isMultiModule", "baselineBroken", "baselineFailureType", "variantsSkipped",
        "buildFileConflictMarkers",
        "budgetBasisSeconds", "peakBaselineRamBytes", "baselineDirGrowthBytes",
        "variantBudgetSeconds", "threads",
        "totalExecutionTime", "numInFlightVariantsKilled", "budgetExhausted",
        "variantsExecutionTimeSeconds", "variants"})
@ToString
public class MergeOutputJSON {
    /** Set to {@code true} after the merge was fully processed and all results collected.
     *  On restart, a JSON with {@code processed=false} is treated as incomplete and reprocessed. */
    private boolean processed;

    private String mode;
    private String projectName;
    private String mergeCommit;
    private String parent1;
    private String parent2;

    private int numConflictFiles;
    private int numJavaConflictFiles;
    private int numConflictChunks;
    private Boolean isMultiModule;

    /** True when the human baseline build fails to compile. */
    private boolean baselineBroken;

    /** Classification of the baseline/variant failure: INFRA_FAILURE, BROKEN_MERGE, COMPILE_FAILURE,
     *  TIMEOUT, NO_TESTS, CHUNK_MISMATCH, MISSING_GIT_OBJECT, VARIANT_SKIP,
     *  or null when the baseline compiled and ran tests successfully. */
    private String baselineFailureType;

    /** True when variant modes should skip this merge entirely.  Set in the human_baseline
     *  JSON by {@code classifyBaseline} when the baseline outcome makes variants pointless
     *  (timeout, infra failure, or 0 tests with a non-broken baseline). */
    private boolean variantsSkipped;

    /** True when build descriptor files (pom.xml, package.json, *.gradle) contain unresolved
     *  git conflict markers.  An INFRA_FAILURE with this flag set is potentially fixable by
     *  a variant that resolves the build file conflict differently. */
    private boolean buildFileConflictMarkers;

    /** Baseline build time used to size the variant budget. For variant modes this is read from
     *  the prior human_baseline JSON (not re-measured); for human_baseline mode it is measured. */
    private long budgetBasisSeconds;

    /** Peak RAM consumed by the baseline build (bytes), measured via MemAvailable sampling.
     *  Stored in the human_baseline JSON so that variant modes can compute thread counts
     *  without re-running the baseline. Zero when the baseline was not measured locally. */
    private long peakBaselineRamBytes;

    /** Bytes written by Maven during one build (target/, surefire-reports, etc.).
     *  On tmpfs these become per-variant RAM overhead. Stored in human_baseline JSON. */
    private long baselineDirGrowthBytes;

    private long variantBudgetSeconds;
    private int threads;
    private long totalExecutionTime;
    private int numInFlightVariantsKilled;
    private boolean budgetExhausted;

    private long variantsExecutionTimeSeconds;
    private List<Variant> variants;

    // ── External-candidates mode only (competitor scoring) — absent in all other modes ──

    /** Wall-clock seconds the competitor tool needed to compute its candidates
     *  (measured on the inference machine, from meta.json). */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Double candidateComputeSeconds;

    /** Competitor tool version (from meta.json). */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String toolVersion;

    /** Competitor tool configuration/flags (from meta.json). */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String toolConfig;

    /**
     * Returns the baseline (index 0) TestTotal by looking at the variants list.
     * Falls back to null if not available.
     */
    @JsonIgnore
    public TestTotal getTestResults() {
        if (variants != null) {
            for (Variant v : variants) {
                if (v.getVariantIndex() == 0 && v.getTestResults() != null) {
                    return v.getTestResults();
                }
            }
        }
        return null;
    }

    /**
     * Returns the baseline (index 0) CompilationResult by looking at the variants list.
     * Returns null if not available.
     */
    @JsonIgnore
    public CompilationResult getCompilationResult() {
        if (variants != null) {
            for (Variant v : variants) {
                if (v.getVariantIndex() == 0 && v.getCompilationResult() != null) {
                    return v.getCompilationResult();
                }
            }
        }
        return null;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"variantIndex", "isCacheDonor", "hadWarmCacheReady",
            "ownExecutionSeconds",
            "totalTimeSinceMergeStartSeconds", "timedOut",
            "compilationResult", "testResults", "conflictPatterns"})
    public static class Variant {
        private int variantIndex;
        @JsonProperty("isCacheDonor")
        @com.fasterxml.jackson.annotation.JsonAlias("isCacheWarmer")
        private boolean isCacheDonor;
        @com.fasterxml.jackson.annotation.JsonAlias("didUseCache")
        private boolean hadWarmCacheReady;
        private Double ownExecutionSeconds;
        private Double totalTimeSinceMergeStartSeconds;
        private boolean timedOut;
        private CompilationResult compilationResult;
        private TestTotal testResults;
        private Map<String, List<String>> conflictPatterns;

        // ── External-candidates mode only (per-candidate meta.json fields) ──

        /** Candidate k-rank within the tool's output (cand_k directory index). */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Integer candidateK;
        /** Tool sub-mode that produced this candidate (e.g. jDime structured/semistructured). */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private String candidateMode;
        /** True when the tool itself resolved every conflict chunk (no fallback). */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Boolean candidateStrict;
        /** True when unresolved chunks were filled with the ours side. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Boolean candidateBestEffort;
        /** Conflict chunks resolved by the tool itself (CLI-git chunk count). */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Integer candidateChunksResolved;
        /** Total conflict chunks as seen by CLI git. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Integer candidateChunksTotal;
        /** True when the tool crashed while producing this candidate — for best-effort
         *  candidates the scored content is then (partly or fully) the ours-side fallback. */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Boolean candidateToolFailed;
    }

}
