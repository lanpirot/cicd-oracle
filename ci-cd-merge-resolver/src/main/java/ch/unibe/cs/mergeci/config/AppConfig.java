package ch.unibe.cs.mergeci.config;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import ch.unibe.cs.mergeci.model.patterns.TheirsPattern;
import org.springframework.context.annotation.Configuration;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.*;
import java.util.List;


@Configuration
public class AppConfig {
    public static final Path BASE_DIR = Paths.get("/home/lanpirot");

    public static final Path DATA_BASE_DIR = BASE_DIR.resolve("data/bruteforcemerge");
    public static final Path INPUT_PROJECT_XLSX = DATA_BASE_DIR.resolve("projects_Java_desc-stars-1000.xlsx"); // Excel Path with list of repositories  and their repo URL
    public static final Path DATASET_DIR = DATA_BASE_DIR.resolve("datasets");       // directory with dataset that were collected by `RepoCollector`
    public static final Path REPO_DIR = BASE_DIR.resolve("tmp/bruteforce_repos");                      // name of directory to clone projects
    public static final Path EXPERIMENTS_DIR = DATA_BASE_DIR.resolve("experiments");
    public static final Path TMP_DIR = BASE_DIR.resolve("tmp/bruteforce_tmp");           // temporary working directory
    public static final Path TMP_PROJECT_DIR = TMP_DIR.resolve("projects");

    // Test-specific directories (all test output goes here)
    public static final Path TEST_BASE_DIR = DATA_BASE_DIR.resolve("test");
    public static final Path TEST_INPUT_PROJECT_XLSX = TEST_BASE_DIR.resolve("projects_test.xlsx"); // Excel Path with list of repositories  and their repo URL
    public static final Path TEST_DATASET_DIR = TEST_BASE_DIR.resolve("dataset_temp");
    public static final Path TEST_REPO_DIR = Paths.get("src/test/resources/test-merge-projects");
    public static final Path TEST_EXPERIMENTS_DIR = TEST_BASE_DIR.resolve("experiments");
    public static final Path TEST_EXPERIMENTS_TEMP_DIR = TEST_EXPERIMENTS_DIR.resolve("temp");
    public static final Path TEST_TMP_DIR = TEST_BASE_DIR.resolve("temp");
    public static final Path TEST_COVERAGE_DIR = TEST_BASE_DIR.resolve("coverage");
    public static final Path TEST_RESOURCE_DIR = Paths.get("src/test/resources/test-Paths");

    public static final String myTest = "myTest";
    public static final String jacksonDatabind = "jackson-databind";
    public static final String ruoyivuepro = "ruoyi-vue-pro";
    public static final String Activiti = "Activiti";
    public static final String ripme = "ripme";
    public static final String airlift = "airlift";
    public static final String zembereknlp = "zemberek-nlp";
    public static final String jitwatch = "jitwatch";
    public static final String XLSX = ".xlsx";
    public static final String JSON = ".json";
    public static final String POMXML = "pom.xml";
    public static final String JAVA = ".java";
    public static final String XML = ".xml";
    public static final int TEST_MAX_CONFLICT_MERGES = 100;  //sample maximally this many merges per project to avoid bias towards giant projects







    static Runtime runtime = Runtime.getRuntime();
    static long totalRamBytes = runtime.totalMemory();
    static long totalRamGB = totalRamBytes / (1024 * 1024);
    public static final int MAX_THREADS = Math.min(Math.round((float) totalRamGB / 8), 16); //avoid hogging all RAM of machine, leave 8GB per thread
    public static final int MAX_CONFLICT_MERGES = 200;  //sample maximally this many merges per project to avoid bias towards giant projects
    public static final int MAX_CONFLICT_CHUNKS = 6;    //if a merge has more chunks than this, we don't attempt resolutions


    public static final List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern()); //patterns we check in Experiments


    public static final int HASH_PREFIX_LENGTH = 8;     //this many chars are used to ensure uniqueness/save paths using commit hash ids

    // Maven plugins
    public static final String JACOCO_PLUGIN = "org.jacoco:jacoco-maven-plugin";
    public static final String JACOCO_VERSION = "0.8.14";
    public static final String JACOCO_FULL = JACOCO_PLUGIN + ":" + JACOCO_VERSION;
}
