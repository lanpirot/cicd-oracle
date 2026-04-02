package ch.unibe.cs.mergeci.config;

import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;


public class AppConfig {

    // ========== EXECUTION MODE ==========
    /**
     * Default value for FRESH_RUN mode
     * If true: Delete all data/output directories and start from scratch
     * If false: Resume from where work was left off (skip completed repos/experiments)
     */
private static final boolean FRESH_RUN = false;

    /**
     * Get FRESH_RUN mode value. Can be overridden via system property "freshRun" for testing.
     * @return true if FRESH_RUN mode is enabled
     */
    public static boolean isFreshRun() {
        return Boolean.parseBoolean(System.getProperty("freshRun", String.valueOf(FRESH_RUN)));
    }

    /**
     * If true: repos currently marked SUCCESS are reset to NOT_PROCESSED_BUT_CLONED so that
     * the ConflictCollector and VariantRunner re-analyze them from scratch — without re-cloning.
     * Repos with any other status are left untouched.
     * Can be overridden via system property "reanalyzeSuccess".
     */
    private static final boolean REANALYZE_SUCCESS = false;

    public static boolean isReanalyzeSuccess() {
        return Boolean.parseBoolean(System.getProperty("reanalyzeSuccess", String.valueOf(REANALYZE_SUCCESS)));
    }

    /**
     * Build the full Maven command array for a normal build.
     */
    public static String[] buildCommand(String mavenExecutable) {
        return buildCommand(mavenExecutable, "test");
    }

    public static String[] buildCommand(String mavenExecutable, String mavenGoal) {
        return concat(
                new String[]{mavenExecutable, MAVEN_BATCH_MODE, MAVEN_FAIL_MODE, MAVEN_TEST_FAILURE_IGNORE,
                        SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE},
                SKIP_STATIC_ANALYSIS,
                new String[]{mavenGoal});
    }

    /** Overload for callers that previously passed a projectPath for JaCoCo detection (now ignored). */
    public static String[] buildCommand(String mavenExecutable, String mavenGoal, @SuppressWarnings("unused") Path projectPath) {
        return buildCommand(mavenExecutable, mavenGoal);
    }

    /**
     * Same as {@link #buildCommand} but with {@code -o} (offline) prepended, for cache builds.
     */
    public static String[] buildCommandOffline(String mavenExecutable) {
        return buildCommandOffline(mavenExecutable, "test");
    }

    public static String[] buildCommandOffline(String mavenExecutable, String mavenGoal) {
        return concat(
                new String[]{mavenExecutable, "-o", MAVEN_BATCH_MODE, MAVEN_FAIL_MODE, MAVEN_TEST_FAILURE_IGNORE,
                        SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE},
                SKIP_STATIC_ANALYSIS,
                new String[]{mavenGoal});
    }

    /** Overload for callers that previously passed a projectPath for JaCoCo detection (now ignored). */
    public static String[] buildCommandOffline(String mavenExecutable, String mavenGoal, @SuppressWarnings("unused") Path projectPath) {
        return buildCommandOffline(mavenExecutable, mavenGoal);
    }

    @SafeVarargs
    private static String[] concat(String[]... parts) {
        return Arrays.stream(parts).flatMap(Arrays::stream).toArray(String[]::new);
    }

    // ========== JAVA INSTALLATIONS ==========
    // NOTE: These paths are hardcoded for the development machine and MUST be updated
    // to match the Java installations available on your system.
    // Run `ls /usr/lib/jvm/` (Linux) or `ls /Library/Java/JavaVirtualMachines/` (macOS)
    // to find installed JDKs. Only include versions that are actually present.
    // Used by JavaVersionResolver to switch to the closest compatible JDK when a
    // repository requires a specific Java version.
    public static final Map<Integer, Path> JAVA_HOMES = Map.of(
            8,  Paths.get("/usr/lib/jvm/jdk1.8.0_202"),
            11, Paths.get("/usr/lib/jvm/jdk-11.0.2"),
            17, Paths.get("/usr/lib/jvm/java-17-openjdk-amd64"),
            21, Paths.get("/usr/lib/jvm/jdk-21.0.2"),
            25, Paths.get("/usr/lib/jvm/jdk-25.0.1")
    );

    // Maven -D flags that are safe to always pass: they skip style/validation plugins
    // that have no effect on compilation or test execution. Discovered incrementally
    // by inspecting projects that fail before tests run.

    // ========== REPO COLLECTOR (legacy) / SHARED PATHS ==========
    /** Hard wall-clock timeout for a single git clone, in seconds. */
    public static final int CLONE_TIMEOUT_SECONDS = 600; // 10 minutes
    /** Per-operation socket timeout passed to JGit transport, in seconds. */
    public static final int CLONE_SOCKET_TIMEOUT_SECONDS = 120; // 2 minutes
    public static final Path BASE_DIR = Paths.get(System.getProperty("user.home"));
    public static final Path DATA_BASE_DIR = BASE_DIR.resolve("data/bruteforcemerge");
    public static final Path REPO_DIR = BASE_DIR.resolve("tmp/bruteforce_repos");                             // directory to clone projects into
    public static final Path CONFLICT_DATASET_DIR = DATA_BASE_DIR.resolve("conflict_datasets");
    public static final Path TMP_DIR = BASE_DIR.resolve("tmp/bruteforce_tmp");                                // temporary working directory
    /** Shared input data: SQL dump, all_conflicts.csv, maven_conflicts.csv, maven_check_cache.json. */
    public static final Path COMMON_DIR = DATA_BASE_DIR.resolve("common");
    /** Shared CSV of LaTeX variables populated by Java pipelines and Python scripts. */
    public static final Path LATEX_VARIABLES_FILE = COMMON_DIR.resolve("latex_variables.csv");

    // ========== CONFLICT COLLECTION (legacy) ==========
    private static final int MAX_CONFLICT_MERGES_DEFAULT = 5;  // sample maximally this many merges per project to avoid bias towards giant projects

    /**
     * Maximum number of conflict merges to collect per project.
     * Can be overridden via system property "maxConflictMerges" for testing (e.g., set to 5).
     */
    public static int getMaxConflictMerges() {
        return Integer.parseInt(System.getProperty("maxConflictMerges", String.valueOf(MAX_CONFLICT_MERGES_DEFAULT)));
    }

    public static final int HASH_PREFIX_LENGTH = 8;     // this many chars ensure uniqueness when saving paths using commit hash ids

    /**
     * Maven build timeout in seconds for single-build operations: conflict collection
     * (baseline checking) and the human-baseline build in experiment phases.
     * Builds exceeding this limit are killed; the wall-clock duration up to the kill
     * is still used as the budget basis for variant modes.
     */
    public static final int MAVEN_BUILD_TIMEOUT = 600;

    // ========== RQ1: PATTERN HEURISTICS / ML-AR ==========
    /** Root data directory for RQ1 artefacts (Java_chunks.csv, fold files, checkpoints …). */
    public static final Path RQ1_DIR             = DATA_BASE_DIR.resolve("rq1");
    /** Fold train/eval CSVs produced by learn_cv_folds.py. */
    public static final Path RQ1_CV_FOLDS_DIR    = RQ1_DIR.resolve("cv_folds");
    /** Trained ML-AR checkpoints (.pt) and fold assignment JSON. */
    public static final Path RQ1_CHECKPOINTS_DIR = RQ1_DIR.resolve("checkpoints");
    /** Per-fold ML-AR prediction CSVs (~200 MB each). */
    public static final Path RQ1_PREDICTIONS_DIR = RQ1_DIR.resolve("predictions");
    /** cv_results.csv, cv_trajectory.csv, LaTeX tables and PDFs. */
    public static final Path RQ1_RESULTS_DIR     = RQ1_DIR.resolve("results");
    /** Python scripts (stays under src/main/resources, not data). */
    public static final Path RQ1_SCRIPTS_DIR     = Paths.get("src/main/resources/pattern-heuristics");
    /**
     * Python executable to use for ML scripts. Prefers the repo-root .venv (GPU-enabled PyTorch,
     * correct numpy version) over system python3.
     */
    public static final String PYTHON_EXECUTABLE = resolveVenvPython();

    private static String resolveVenvPython() {
        // .venv may sit at the Maven module root or one level up (repo root)
        Path moduleDir = Paths.get("").toAbsolutePath();
        for (Path candidate : new Path[]{
                moduleDir.resolve(".venv/bin/python3"),
                moduleDir.getParent().resolve(".venv/bin/python3")}) {
            if (candidate.toFile().canExecute()) return candidate.toString();
        }
        return "python3";
    }
    /** Fold assignment JSON: maps merge_id → fold index (0-based). Required for leakage-free ML-AR inference. */
    public static final Path RQ1_FOLD_ASSIGNMENT_FILE = RQ1_CHECKPOINTS_DIR.resolve("autoregressive_fold_assignment.json");
    /** Maximum number of ML-AR variant predictions to load per merge (matches Python --variants cap). */
    public static final int  ML_VARIANT_CAP      = 500;

    // ========== SHARED DATA SOURCES (RQ1 + RQ2 + RQ3) ==========
    /** All conflict chunks — input for RQ1 ML training (generated by extract_from_sql_dump.py). */
    public static final Path ALL_CONFLICTS_CSV           = COMMON_DIR.resolve("all_conflicts.csv");
    /** Maven conflict merges shared by RQ2 and RQ3 (generated by extract_from_sql_dump.py). */
    public static final Path MAVEN_CONFLICTS_CSV         = COMMON_DIR.resolve("maven_conflicts.csv");

    // ========== EXPERIMENT TAG ==========
    /** Optional tag (e.g. git tag) that namespaces RQ2/RQ3 output directories.
     *  When set, output goes to {@code DATA_BASE_DIR/<tag>/rq2} etc. instead of {@code DATA_BASE_DIR/rq2}. */
    private static final String EXPERIMENT_TAG = System.getProperty("experimentTag", "");
    private static final Path EXPERIMENT_BASE = EXPERIMENT_TAG.isEmpty()
            ? DATA_BASE_DIR : DATA_BASE_DIR.resolve(EXPERIMENT_TAG);

    // ========== RQ2: JAVA CHUNKS PIPELINE ==========
    public static final Path RQ2_VARIANT_EXPERIMENT_DIR = EXPERIMENT_BASE.resolve("rq2");
    public static final int  RQ2_SAMPLE_REPOS           = 50;
    public static final int  RQ2_MERGES_PER_REPO        = 1;

    // ========== RQ3: LARGE-SCALE PIPELINE ==========
    public static final Path RQ3_VARIANT_EXPERIMENT_DIR = EXPERIMENT_BASE.resolve("rq3");
    public static final int  RQ3_SAMPLE_TOTAL           = 500;
    /** Best experiment mode from RQ2 to use in RQ3. Overridable via system property rq3BestMode. */
    public static String getRQ3BestMode() {
        return System.getProperty("rq3BestMode", "cache_parallel");
    }

    // ========== VARIANT EXPERIMENTS ==========
    public static final Path VARIANT_EXPERIMENT_DIR = DATA_BASE_DIR.resolve("variant_experiments");

    // ========== INSPECTION: CONFLICT FILE TRIPLETS ==========
    /** Root directory for saved human/tentative/variant file triplets used by the viewer. */
    public static final Path CONFLICT_FILES_DIR = DATA_BASE_DIR.resolve("conflict_files");

    // ========== PRESENTATION ==========
    /** Python script that generates all paper-ready PDF charts with LaTeX fonts. */
    public static final Path PLOT_SCRIPT = Path.of(
            System.getProperty("projectDir",
                    BASE_DIR.resolve("projects/merge++/cicd-oracle").toString()))
            .resolve("scripts/plot_results.py");

    // ========== PHASES 2+3: MAVEN RUNNER ==========
    private static final int    THREAD_FALLBACK         = 4;
    private static final long   RAM_HEADROOM            = 10L * 1024 * 1024 * 1024; // 10 GB reserved for OS + user tasks
    private static final long   RAM_PER_THREAD_DEFAULT  = 10L * 1024 * 1024 * 1024; // 10 GB assumed when peak unknown

    /**
     * Compute the number of parallel Maven variant threads.
     *
     * <p>Formula: {@code max(1, min((MemAvailable − 10 GB) / peak, cores − 9))}.
     *
     * <ul>
     *   <li>When {@code peakBuildRamBytes > 0} (measured during the baseline build via
     *       {@code MemAvailable} sampling): divides spare RAM by the measured peak.</li>
     *   <li>When {@code peakBuildRamBytes == 0} (unknown): divides spare RAM by
     *       {@value #RAM_PER_THREAD_DEFAULT} bytes (10 GB) as a conservative estimate.</li>
     * </ul>
     * Spare RAM = {@code MemAvailable − 10 GB} headroom for OS and user tasks.
     * Result is capped at {@code availableProcessors − 4} and floored at 1.
     * Returns {@value #THREAD_FALLBACK} on any error (e.g. non-Linux systems).
     *
     * @param peakBuildRamBytes measured peak RAM of one Maven build, in bytes; 0 = unknown
     */
    public static int computeMaxThreads(long peakBuildRamBytes) {
        try {
            long memAvailable = readMemAvailable();
            long spareRam     = Math.max(0, memAvailable - RAM_HEADROOM);
            long ramPerThread = (peakBuildRamBytes > 0) ? peakBuildRamBytes : RAM_PER_THREAD_DEFAULT;
            int  coreCap      = Math.max(1, Runtime.getRuntime().availableProcessors() - 4);
            return Math.max(1, Math.min((int)(spareRam / ramPerThread), coreCap));
        } catch (Exception e) {
            return THREAD_FALLBACK;
        }
    }

    /** Read {@code MemAvailable} from {@code /proc/meminfo} (Linux). */
    static long readMemAvailable() throws Exception {
        try (var br = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.split("\\s+")[1]) * 1024; // kB → bytes
                }
            }
        }
        throw new IllegalStateException("MemAvailable not found in /proc/meminfo");
    }

    /** Conservative thread count used before a baseline build is measured. */
    public static final int MAX_THREADS = computeMaxThreads(0);

    /**
     * Maven batch mode flag.
     * Suppresses interactive progress bars and ANSI escape codes, making log files
     * produced by redirectOutput() clean and reliably parseable.
     */
    public static final String MAVEN_BATCH_MODE = "-B";

    /**
     * Maven reactor behavior flag.
     * -fae (fail-at-end): Continue building all modules regardless of failures.
     * This ensures we get compilation results for all modules, not just those before the first failure.
     */
    public static final String MAVEN_FAIL_MODE = "-fae";

    /**
     * Maven test failure behavior flag.
     * Allows the build to continue even if tests fail, so we can collect complete test results.
     */
    public static final String MAVEN_TEST_FAILURE_IGNORE = "-Dmaven.test.failure.ignore=true";

    /**
     * Override any pom.xml-level skipTests/maven.test.skip flags so command-line properties
     * always win, ensuring tests are actually executed even in projects that default to skipping.
     */
    private static final String SKIP_TESTS_OVERRIDE      = "-DskipTests=false";
    private static final String MAVEN_TEST_SKIP_OVERRIDE = "-Dmaven.test.skip=false";

    /**
     * Skip read-only static-analysis plugins that are irrelevant to merge conflict resolution.
     * These plugins never generate or transform source/bytecode — they only inspect the build
     * and optionally fail it.  They frequently fail to download their own plugin JARs or
     * rule-set artifacts (e.g. TLS "peer not authenticated", stale mirror, version yanked from
     * Central), triggering the broad "could not be resolved" infra-failure pattern and causing
     * merges to be permanently skipped even though compilation and tests would succeed.
     *
     * <p>maven-enforcer-plugin is intentionally NOT skipped: it validates structural build
     * contracts (banned dependencies, required Java version, no-SNAPSHOT rules) that may
     * legitimately fail as a direct consequence of the merged changes.
     */
    private static final String[] SKIP_STATIC_ANALYSIS = {
            "-Dpmd.skip=true", "-Dcheckstyle.skip=true", "-Dspotbugs.skip=true",
            "-Dfindbugs.skip=true", "-Ddependency-check.skip=true",
            // license-maven-plugin with update-file-header/update-project-license goals modifies
            // source files and generated sources during the build — skip it to avoid mutating the
            // checkout we are analysing.
            "-Dlicense.skip=true",
            // JaCoCo bound via a parent POM (e.g. commons-parent:47 uses 0.8.1) attaches an
            // incompatible javaagent to the surefire forked JVM when the project is built on
            // Java 17+.  The JVM crashes immediately (exit 134) and surefire reports 0 tests
            // while Maven still exits BUILD SUCCESS due to -Dmaven.test.failure.ignore=true.
            // The pipeline does not use JaCoCo coverage data, so skipping it globally is safe.
            "-Djacoco.skip=true"
    };

    private static final int TIMEOUT_MULTIPLIER = 10;
    private static final long MIN_VARIANT_BUDGET = 300;

    /** Compute variant budget: max(300s, normalizedBaseline × 10). */
    public static long variantBudget(long normalizedBaselineSeconds) {
        return Math.max(MIN_VARIANT_BUDGET, normalizedBaselineSeconds * TIMEOUT_MULTIPLIER);
    }

    /**
     * JVM heap size passed to spawned Maven processes via MAVEN_OPTS.
     * Large projects (e.g. BroadleafCommerce) exhaust the default heap during compilation/testing.
     * Increase this if you see "OutOfMemoryError: Java heap space" in Maven subprocess output.
     */
    public static final String MAVEN_SUBPROCESS_HEAP = "-Xmx4g";

    // ========== UTILITY ==========
    // File extensions
    public static final String CSV = ".csv";
    public static final String JSON = ".json";
    public static final String POMXML = "pom.xml";


    // Test repo names
    public static final String myTest = "myTest";

    public static final String jacksonDatabind = "jackson-databind";
    public static final String ruoyivuepro = "ruoyi-vue-pro";

    public static final String ripme = "ripme";

    public static final String zembereknlp = "zemberek-nlp";
    public static final String jitwatch = "jitwatch";

    // Test-specific directories (all test output goes here)
    public static final Path TEST_BASE_DIR = DATA_BASE_DIR.resolve("test");

    public static final Path TEST_DATASET_DIR = TEST_BASE_DIR.resolve("dataset_temp");
    public static final Path TEST_REPO_DIR = Paths.get("src/test/resources/test-merge-projects");
    public static final Path TEST_EXPERIMENTS_DIR = TEST_BASE_DIR.resolve("experiments");
    public static final Path TEST_EXPERIMENTS_TEMP_DIR = TEST_EXPERIMENTS_DIR.resolve("temp");
    public static final Path TEST_TMP_DIR = TEST_BASE_DIR.resolve("temp");
    public static final Path TEST_COVERAGE_DIR = TEST_BASE_DIR.resolve("coverage");
    public static final Path TEST_RESOURCE_DIR = Paths.get("src/test/resources/test-files");
}
