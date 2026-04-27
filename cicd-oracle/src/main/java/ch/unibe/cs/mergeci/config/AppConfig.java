package ch.unibe.cs.mergeci.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;


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
     * Per-daemon mvnd reactor thread count ({@code -T} flag), set before each phase.
     * Zero leaves {@code -T} off so mvnd uses its default ({@code cores/2 + 1}).
     * Applied symmetrically to the human-baseline build and parallel variant modes
     * so that {@link #computeMaxThreads} divides spareRam by a per-daemon peak that
     * was actually measured under the same reactor parallelism the variant phase
     * will reproduce. {@link #reactorThreadsFor} computes the value.
     *
     * <p>Volatile because the orchestrator sets it sequentially between phases
     * while worker threads inside a phase read it via {@link #reactorFlagOrEmpty}.
     */
    private static volatile int reactorThreadsForCurrentRun = 0;

    /** Set the {@code -T} value used by the next Maven invocations; 0 disables the flag. */
    public static void setReactorThreads(int n) {
        reactorThreadsForCurrentRun = Math.max(0, n);
    }

    /** Current {@code -T} value (0 = no flag). */
    public static int getReactorThreads() {
        return reactorThreadsForCurrentRun;
    }

    /**
     * Compute the per-daemon {@code -T} value for a given variant fan-out.
     * Formula: {@code max(1, floor(cores / variantThreads))}. Picked so that
     * {@code (intra-daemon reactor threads) × (parallel daemons) ≈ cores},
     * keeping the CPU saturated without oversubscribing when N variant builds
     * each spawn an mvnd daemon with its own internal reactor.
     */
    public static int reactorThreadsFor(int variantThreads) {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, cores / Math.max(1, variantThreads));
    }

    /** {@code ["-T<n>"]} when set, empty array otherwise — used inside {@code buildCommand*}. */
    private static String[] reactorFlagOrEmpty() {
        int n = reactorThreadsForCurrentRun;
        return n > 0 ? new String[]{"-T" + n} : new String[0];
    }

    /**
     * Build the full Maven command array for a normal build.
     * @param executableArgs executable prefix, e.g. {@code ["mvn"]} or {@code ["mvnd", "--maven-home", "/path"]}
     */
    public static String[] buildCommand(String[] executableArgs, String mavenGoal) {
        return concat(
                executableArgs,
                reactorFlagOrEmpty(),
                new String[]{MAVEN_BATCH_MODE, MAVEN_FORCE_UPDATE, MAVEN_FAIL_MODE,
                        MAVEN_TEST_FAILURE_IGNORE,
                        SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE},
                SKIP_STATIC_ANALYSIS,
                BOUND_PARALLELISM,
                new String[]{mavenGoal});
    }

    /**
     * Compile-only command: runs {@code mvn compile} without test-related flags.
     * Used for the two-phase optimization: compile first, skip test phase if the variant
     * can't beat the current best on successful modules.
     */
    public static String[] buildCompileOnlyCommand(String[] executableArgs) {
        return concat(
                executableArgs,
                reactorFlagOrEmpty(),
                new String[]{MAVEN_BATCH_MODE, MAVEN_FORCE_UPDATE, MAVEN_FAIL_MODE},
                SKIP_STATIC_ANALYSIS,
                BOUND_PARALLELISM,
                new String[]{"compile"});
    }

    /**
     * Full build+test command with the early-abort gate.  The maven-hook reads
     * {@code -Dcicd.bestModules=N} and aborts after compile if the variant can't
     * reach N successful modules — replacing the two-phase compile-then-test split
     * with a single Maven invocation that self-terminates.
     */
    public static String[] buildCommandWithGate(String[] executableArgs, String mavenGoal,
                                                 int bestModules) {
        return concat(
                executableArgs,
                reactorFlagOrEmpty(),
                new String[]{MAVEN_BATCH_MODE, MAVEN_FORCE_UPDATE, MAVEN_FAIL_MODE,
                        MAVEN_TEST_FAILURE_IGNORE,
                        SKIP_TESTS_OVERRIDE, MAVEN_TEST_SKIP_OVERRIDE,
                        "-Dcicd.bestModules=" + bestModules},
                SKIP_STATIC_ANALYSIS,
                BOUND_PARALLELISM,
                new String[]{mavenGoal});
    }

    public static String[] concat(String[]... parts) {
        return Arrays.stream(parts).flatMap(Arrays::stream).toArray(String[]::new);
    }

    // ========== JAVA INSTALLATIONS ==========
    // Used by JavaVersionResolver to switch to the closest compatible JDK when a
    // repository requires a specific Java version.
    //
    // Resolution order:
    //   1. ~/.cicd-oracle/java-homes.properties — explicit override (lines like `21=/path/to/jdk`).
    //   2. Auto-discovery: scan /usr/lib/jvm/* for directories with bin/javac and a
    //      `release` file, parse JAVA_VERSION=, map major version → path. Symlinks
    //      are skipped so we prefer canonical install names.
    //
    // To pin a specific install on a host with several JDKs of the same major version,
    // create the override file. Otherwise auto-discovery picks the first match in
    // sorted-name order.
    public static final Map<Integer, Path> JAVA_HOMES = resolveJavaHomes();

    private static Map<Integer, Path> resolveJavaHomes() {
        Map<Integer, Path> override = readJavaHomesOverride();
        if (!override.isEmpty()) return Map.copyOf(override);
        return Map.copyOf(scanJvmDir(Paths.get("/usr/lib/jvm")));
    }

    private static Map<Integer, Path> readJavaHomesOverride() {
        Path propsFile = Paths.get(System.getProperty("user.home"), ".cicd-oracle", "java-homes.properties");
        if (!Files.isRegularFile(propsFile)) return Map.of();
        Properties props = new Properties();
        try (var in = Files.newBufferedReader(propsFile)) {
            props.load(in);
        } catch (IOException e) {
            return Map.of();
        }
        Map<Integer, Path> result = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            try {
                int v = Integer.parseInt(key.trim());
                Path p = Paths.get(props.getProperty(key).trim());
                if (Files.isExecutable(p.resolve("bin/javac"))) result.put(v, p);
            } catch (NumberFormatException ignored) { /* skip malformed lines */ }
        }
        return result;
    }

    static Map<Integer, Path> scanJvmDir(Path baseDir) {
        Map<Integer, Path> result = new TreeMap<>();
        if (!Files.isDirectory(baseDir)) return result;
        try (Stream<Path> entries = Files.list(baseDir)) {
            entries.filter(p -> !Files.isSymbolicLink(p))
                    .filter(Files::isDirectory)
                    .filter(p -> Files.isExecutable(p.resolve("bin/javac")))
                    .sorted()
                    .forEach(p -> {
                        int v = readJavaMajorVersion(p);
                        if (v > 0) result.putIfAbsent(v, p);
                    });
        } catch (IOException ignored) { /* return whatever we managed to discover */ }
        return result;
    }

    private static int readJavaMajorVersion(Path javaHome) {
        Path releaseFile = javaHome.resolve("release");
        if (!Files.isRegularFile(releaseFile)) return -1;
        try {
            for (String line : Files.readAllLines(releaseFile)) {
                if (!line.startsWith("JAVA_VERSION=")) continue;
                String v = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                String[] parts = v.split("\\.");
                if (parts.length == 0 || parts[0].isEmpty()) return -1;
                // Java 8 reports "1.8.0_202"; Java 9+ reports "21.0.2".
                if ("1".equals(parts[0]) && parts.length >= 2) return Integer.parseInt(parts[1]);
                return Integer.parseInt(parts[0]);
            }
        } catch (IOException | NumberFormatException ignored) { /* unreadable / malformed */ }
        return -1;
    }

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
    /** Overlay/variant build directory — point to tmpfs (e.g. /dev/shm) for RAM-backed I/O.
     *  When unset, defaults to TMP_DIR (HDD) — variants still benefit from overlayfs CoW. */
    public static final Path OVERLAY_TMP_DIR = Path.of(
            System.getProperty("overlayTmpDir", TMP_DIR.toString()));
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
        // Walk up from cwd to find .venv/bin/python3. The venv lives at the workspace
        // root (e.g. merge++/.venv), which may be several levels above the Maven module.
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(".venv/bin/python3");
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
    private static final int  RQ2_SAMPLE_REPOS_DEFAULT   = 50;
    public static int getRQ2SampleRepos() {
        return Integer.parseInt(System.getProperty("rq2SampleRepos", String.valueOf(RQ2_SAMPLE_REPOS_DEFAULT)));
    }
    private static final int  RQ2_MERGES_PER_REPO_DEFAULT = 1;
    public static int getRQ2MergesPerRepo() {
        return Integer.parseInt(System.getProperty("rq2MergesPerRepo", String.valueOf(RQ2_MERGES_PER_REPO_DEFAULT)));
    }

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
    // 10 GB headroom (not 5) absorbs RAM that the baseline measurement structurally
    // cannot see when N>1 daemons run concurrently: orchestrator JVM growth across
    // a long variant phase, per-daemon JIT/metaspace/code-cache creep, page-cache
    // pinning under sustained N× I/O, and small fixed costs from extra mvnd CLI
    // launchers and fuse-overlayfs daemons. The cap formula otherwise runs at
    // exactly 100% of nameplate capacity and OOMs on ~10% workload variance.
    private static final long   RAM_HEADROOM            = 10L * 1024 * 1024 * 1024;
    private static final long   RAM_PER_THREAD_DEFAULT  = 10L * 1024 * 1024 * 1024; // 10 GB assumed when peak unknown

    /**
     * Compute the number of parallel Maven variant threads.
     *
     * <p>Formula: {@code max(1, min((MemAvailable − 10 GB) / peak, cores − 2))}.
     *
     * <ul>
     *   <li>When {@code peakBuildRamBytes > 0} (measured during the baseline build via
     *       {@code MemAvailable} sampling): divides spare RAM by the measured peak.</li>
     *   <li>When {@code peakBuildRamBytes == 0} (unknown): divides spare RAM by
     *       {@value #RAM_PER_THREAD_DEFAULT} bytes (10 GB) as a conservative estimate.</li>
     * </ul>
     * Spare RAM = {@code MemAvailable − 10 GB} headroom for OS + variance slack.
     * Result is capped at {@code availableProcessors − 2} and floored at 1.
     * Returns {@value #THREAD_FALLBACK} on any error (e.g. non-Linux systems).
     *
     * @param peakBuildRamBytes measured peak RAM of one Maven build, in bytes; 0 = unknown
     */
    public static int computeMaxThreads(long peakBuildRamBytes) {
        return computeMaxThreads(peakBuildRamBytes, 0);
    }

    /**
     * Compute parallel Maven variant threads accounting for a persistent RAM reservation
     * (e.g. a shared overlay base on tmpfs).
     *
     * <p>Formula: {@code max(1, min((MemAvailable − headroom − persistentRamBytes) / perThread, cores − 2))}.
     *
     * @param perThreadRamBytes   measured per-build RAM (peak heap + Maven disk writes on tmpfs); 0 = unknown
     * @param persistentRamBytes  RAM pinned for shared state (e.g. overlay base dir on tmpfs); 0 = none
     */
    public static int computeMaxThreads(long perThreadRamBytes, long persistentRamBytes) {
        try {
            // Hard cap via system property — useful for benchmarking and throttling
            String cap = System.getProperty("maxThreads");
            if (cap != null) return Math.max(1, Integer.parseInt(cap));

            long memAvailable = readMemAvailable();
            long spareRam     = Math.max(0, memAvailable - RAM_HEADROOM - persistentRamBytes);
            long ramPerThread = (perThreadRamBytes > 0) ? perThreadRamBytes : RAM_PER_THREAD_DEFAULT;
            int  coreCap      = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
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
     * Force-update flag (-U / --update-snapshots).
     *
     * <p>Two effects we rely on:
     * <ol>
     *   <li>Re-checks SNAPSHOT artifacts (irrelevant for our well-populated local repo).</li>
     *   <li><b>Ignores cached 404s on missing artifacts</b>. Without {@code -U}, when a previous
     *       Maven invocation failed to resolve {@code <some-reactor-parent>} from central (because
     *       the project's parent POM lives only in the source tree, never published), the failure
     *       gets cached in {@code ~/.m2/.../_remote.repositories} and Maven refuses to retry until
     *       the repository's update interval elapses (default: daily). On per-thread m2 overlays
     *       used by sequential variant modes, that means the very first variant's transient scan-
     *       phase 404 poisons every subsequent variant on the same thread, surfacing as
     *       SCAN_FAILURE in modes where the parallel modes (each on a fresh m2 overlay) succeed.</li>
     * </ol>
     */
    public static final String MAVEN_FORCE_UPDATE = "-U";

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
            "-Djacoco.skip=true",
            // maven-javadoc-plugin :jar forks a per-module javadoc JVM (~300 MB resident each).
            // Multi-module projects bound to the package phase can trigger 30+ forks per build,
            // multiplied by the variant fan-out under mvnd's parallel reactor.  The
            // *-javadoc.jar artefact is never consumed for merge-conflict evaluation.
            "-Dmaven.javadoc.skip=true"
    };

    /**
     * Cap the per-Maven-invocation parallelism of plugins we still need to run.
     * Defaults are usually safe, but a project's pom.xml can override surefire to
     * {@code forkCount=1C} (one test JVM per CPU core) or enable intra-fork parallel
     * tests — multiplied by the variant fan-out under mvnd's parallel reactor that
     * scales into dozens of test JVMs and breaks the cap formula's per-thread RAM
     * model. Command-line {@code -D} overrides any pom.xml setting.
     */
    private static final String[] BOUND_PARALLELISM = {
            // surefire: one test JVM per Maven invocation, no intra-fork test threads
            "-DforkCount=1",
            "-Dsurefire.parallel=none",
            // failsafe (integration tests, when mavenGoal=verify): same bound
            "-Dfailsafe.forkCount=1",
            // compiler: keep javac in-process — no separate JVM per module compile
            "-Dmaven.compiler.fork=false"
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

    /** Use Maven Daemon (mvnd) for variant builds. Auto-detected from PATH. */
    public static final boolean USE_MAVEN_DAEMON = detectMvnd();

    private static boolean detectMvnd() {
        try {
            Process p = new ProcessBuilder("which", "mvnd").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

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
