package ch.unibe.cs.mergeci.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;


public class AppConfig {

    // ========== EXECUTION MODE ==========
    /**
     * Default value for FRESH_RUN mode
     * If true: Delete all data/output directories and start from scratch
     * If false: Resume from where work was left off (skip completed repos/experiments)
     */
    private static final boolean FRESH_RUN = true;

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
     * Whether JaCoCo coverage measurement is enabled.
     * When true, Jacoco goals are added to every Maven build and the coverage report
     * for the main (human-resolved) project is stored in the experiment output JSON.
     * Can be overridden via system property "coverageActivated" for testing.
     */
    public static boolean isCoverageActivated() {
        return Boolean.parseBoolean(System.getProperty("coverageActivated", "true"));
    }

    /**
     * Build the full Maven command array for a normal build.
     * JaCoCo goals are included when coverage is active.
     */
    public static String[] buildCommand(String mavenExecutable) {
        return buildCommand(mavenExecutable, "test");
    }

    public static String[] buildCommand(String mavenExecutable, String mavenGoal) {
        return buildCommand(mavenExecutable, mavenGoal, null);
    }

    /**
     * Build command for a specific project path. When the project already configures
     * jacoco-maven-plugin in its pom.xml, our {@code prepare-agent} goal is omitted to
     * avoid attaching two JaCoCo agents simultaneously (which causes a JVM crash).
     */
    public static String[] buildCommand(String mavenExecutable, String mavenGoal, Path projectPath) {
        if (isCoverageActivated() && !projectHasJacocoPlugin(projectPath)) {
            return concat(
                    new String[]{mavenExecutable, MAVEN_BATCH_MODE, MAVEN_FAIL_MODE, MAVEN_TEST_FAILURE_IGNORE,
                            SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE},
                    SKIP_STATIC_ANALYSIS,
                    new String[]{JACOCO_FULL + ":prepare-agent", JACOCO_EXCLUDES, mavenGoal, JACOCO_FULL + ":report"});
        }
        return concat(
                new String[]{mavenExecutable, MAVEN_BATCH_MODE, MAVEN_FAIL_MODE, MAVEN_TEST_FAILURE_IGNORE,
                        SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE},
                SKIP_STATIC_ANALYSIS,
                new String[]{mavenGoal});
    }

    /**
     * Same as {@link #buildCommand} but with {@code -o} (offline) prepended, for cache builds.
     */
    public static String[] buildCommandOffline(String mavenExecutable) {
        return buildCommandOffline(mavenExecutable, "test");
    }

    public static String[] buildCommandOffline(String mavenExecutable, String mavenGoal) {
        return buildCommandOffline(mavenExecutable, mavenGoal, null);
    }

    public static String[] buildCommandOffline(String mavenExecutable, String mavenGoal, Path projectPath) {
        if (isCoverageActivated() && !projectHasJacocoPlugin(projectPath)) {
            return concat(
                    new String[]{mavenExecutable, "-o", MAVEN_BATCH_MODE, MAVEN_FAIL_MODE, MAVEN_TEST_FAILURE_IGNORE,
                            SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE},
                    SKIP_STATIC_ANALYSIS,
                    new String[]{JACOCO_FULL + ":prepare-agent", JACOCO_EXCLUDES, mavenGoal, JACOCO_FULL + ":report"});
        }
        return concat(
                new String[]{mavenExecutable, "-o", MAVEN_BATCH_MODE, MAVEN_FAIL_MODE, MAVEN_TEST_FAILURE_IGNORE,
                        SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE},
                SKIP_STATIC_ANALYSIS,
                new String[]{mavenGoal});
    }

    @SafeVarargs
    private static String[] concat(String[]... parts) {
        return Arrays.stream(parts).flatMap(Arrays::stream).toArray(String[]::new);
    }

    /**
     * Returns true when the project's root pom.xml already declares jacoco-maven-plugin.
     * In that case we must not inject our own {@code prepare-agent} goal — doing so would
     * attach two JaCoCo javaagents to the forked Surefire JVM, causing a SIGABRT crash.
     */
    public static boolean projectHasJacocoPlugin(Path projectPath) {
        if (projectPath == null) return false;
        Path pom = projectPath.resolve("pom.xml");
        if (!pom.toFile().exists()) return false;
        try {
            return Files.readString(pom).contains("jacoco-maven-plugin");
        } catch (IOException e) {
            return false;
        }
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

    // ========== PHASE 1: REPO COLLECTOR ==========
    /** Hard wall-clock timeout for a single git clone, in seconds. */
    public static final int CLONE_TIMEOUT_SECONDS = 600; // 10 minutes
    /** Per-operation socket timeout passed to JGit transport, in seconds. */
    public static final int CLONE_SOCKET_TIMEOUT_SECONDS = 120; // 2 minutes
    public static final Path BASE_DIR = Paths.get("/home/lanpirot");
    public static final Path DATA_BASE_DIR = BASE_DIR.resolve("data/bruteforcemerge");
    public static final Path INPUT_PROJECT_CSV = DATA_BASE_DIR.resolve("projects_Java_desc-stars-1000.csv"); // CSV with list of repositories and their repo URL
    public static final Path REPO_DIR = BASE_DIR.resolve("tmp/bruteforce_repos");                             // directory to clone projects into
    public static final Path CONFLICT_DATASET_DIR = DATA_BASE_DIR.resolve("conflict_datasets");               // datasets collected by RepoCollector / MergeConflictCollector
    public static final Path TMP_DIR = BASE_DIR.resolve("tmp/bruteforce_tmp");                                // temporary working directory

    // ========== PHASE 2: CONFLICT COLLECTION ==========
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
     * Maven build timeout in seconds for conflict collection (baseline checking).
     * Lower timeout since we're just verifying the human-resolved merge compiles.
     * Builds exceeding this timeout are likely stuck and should be skipped.
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

    // ========== PHASE 3: VARIANT EXPERIMENTS ==========
    public static final Path VARIANT_EXPERIMENT_DIR = DATA_BASE_DIR.resolve("variant_experiments");

    // ========== INSPECTION: CONFLICT FILE TRIPLETS ==========
    /** Root directory for saved human/tentative/variant file triplets used by the viewer. */
    public static final Path CONFLICT_FILES_DIR = DATA_BASE_DIR.resolve("conflict_files");

    // ========== PRESENTATION (PHASE 4) ==========
    /** Python script that generates all paper-ready PDF charts with LaTeX fonts. */
    public static final Path PLOT_SCRIPT = Paths.get(
            "/home/lanpirot/projects/merge++/bruteforce/ci-cd-merge-resolver/scripts/plot_results.py");
    /** Output PDF written by the Python presentation script. */
    public static final Path PLOTS_OUTPUT_PDF = DATA_BASE_DIR.resolve("plots.pdf");

    // ========== PHASES 2+3: MAVEN RUNNER ==========
    static Runtime runtime = Runtime.getRuntime();
    static long totalRamBytes = runtime.totalMemory();
    static long totalRamGB = totalRamBytes / (1024 * 1024);
    public static final int MAX_THREADS = Math.min(Math.round((float) totalRamGB / 8), 16); // avoid hogging all RAM of machine, leave 8GB per thread

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
            "-Dlicense.skip=true"
    };

    /**
     * Timeout multiplier for variant testing in ResolutionVariantRunner.
     * Timeout is calculated as: TIMEOUT_MULTIPLIER * normalizedElapsedTime
     * This allows dynamic timeouts based on the expected build time from the dataset.
     */
    public static final int TIMEOUT_MULTIPLIER = 10;

    /**
     * JVM heap size passed to spawned Maven processes via MAVEN_OPTS.
     * Large projects (e.g. BroadleafCommerce) exhaust the default heap during compilation/testing.
     * Increase this if you see "OutOfMemoryError: Java heap space" in Maven subprocess output.
     */
    public static final String MAVEN_SUBPROCESS_HEAP = "-Xmx4g";

    // Maven / JaCoCo plugin coordinates
    public static final String JACOCO_PLUGIN = "org.jacoco:jacoco-maven-plugin";
    public static final String JACOCO_VERSION = "0.8.14";
    public static final String JACOCO_FULL = JACOCO_PLUGIN + ":" + JACOCO_VERSION;
    // Exclude bytecode-manipulation frameworks from JaCoCo instrumentation to prevent
    // double-instrumentation crashes (e.g. JaCoCo 0.8.14 + ByteBuddy/PowerMock, cglib).
    public static final String JACOCO_EXCLUDES = "-Djacoco.excludes=net.bytebuddy.**:net.sf.cglib.**";

    // ========== UTILITY ==========
    // File extensions
    public static final String CSV = ".csv";
    public static final String JSON = ".json";
    public static final String POMXML = "pom.xml";
    public static final String JAVA = ".java";
    public static final String XML = ".xml";

    // Test repo names
    public static final String myTest = "myTest";
    public static final String brokenMergeTest = "broken-merge-test";
    public static final String jacksonDatabind = "jackson-databind";
    public static final String ruoyivuepro = "ruoyi-vue-pro";
    public static final String Activiti = "Activiti";
    public static final String ripme = "ripme";
    public static final String airlift = "airlift";
    public static final String zembereknlp = "zemberek-nlp";
    public static final String jitwatch = "jitwatch";

    // Test-specific directories (all test output goes here)
    public static final Path TEST_BASE_DIR = DATA_BASE_DIR.resolve("test");
    public static final Path TEST_INPUT_PROJECT_CSV = TEST_BASE_DIR.resolve("projects_test.csv");
    public static final Path TEST_DATASET_DIR = TEST_BASE_DIR.resolve("dataset_temp");
    public static final Path TEST_REPO_DIR = Paths.get("src/test/resources/test-merge-projects");
    public static final Path TEST_EXPERIMENTS_DIR = TEST_BASE_DIR.resolve("experiments");
    public static final Path TEST_EXPERIMENTS_TEMP_DIR = TEST_EXPERIMENTS_DIR.resolve("temp");
    public static final Path TEST_TMP_DIR = TEST_BASE_DIR.resolve("temp");
    public static final Path TEST_COVERAGE_DIR = TEST_BASE_DIR.resolve("coverage");
    public static final Path TEST_RESOURCE_DIR = Paths.get("src/test/resources/test-files");
}
