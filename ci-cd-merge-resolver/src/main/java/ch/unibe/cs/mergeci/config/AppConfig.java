package ch.unibe.cs.mergeci.config;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import ch.unibe.cs.mergeci.model.patterns.TheirsPattern;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.List;

@Configuration
public class AppConfig {
    public static final File BASE_DIR = new File("/home/lanpirot");

    public static final File DATA_BASE_DIR = new File(BASE_DIR, "data/bruteforcemerge");
    public static final File INPUT_PROJECT_XLSX = new File(DATA_BASE_DIR, "projects_Java_desc-stars-1000.xlsx"); // Excel file with list of repositories  and their repo URL
    public static final File DATASET_DIR = new File(DATA_BASE_DIR, "datasets");       // directory with dataset that were collected by `RepoCollector`
    public static final File REPO_DIR = new File(BASE_DIR, "tmp/bruteforce_repos");                      // name of directory to clone projects
    public static final File EXPERIMENTS_DIR = new File(DATA_BASE_DIR, "experiments");
    public static final File TMP_DIR = new File(BASE_DIR, "tmp/bruteforce_tmp");           // temporary working directory

    // Test-specific directories (all test output goes here)
    public static final File TEST_BASE_DIR = new File(DATA_BASE_DIR, "test");
    public static final File TEST_INPUT_PROJECT_XLSX = new File(TEST_BASE_DIR,"projects_test.xlsx"); // Excel file with list of repositories  and their repo URL
    public static final File TEST_DATASET_DIR = new File(TEST_BASE_DIR,"dataset_temp");
    public static final File TEST_REPO_DIR = new File("src/test/resources/test-merge-projects");
    public static final File TEST_EXPERIMENTS_DIR = new File(TEST_BASE_DIR, "experiments");
    public static final File TEST_EXPERIMENTS_TEMP_DIR = new File(TEST_EXPERIMENTS_DIR, "temp");
    public static final File TEST_TMP_DIR = new File(TEST_BASE_DIR, "temp");
    public static final File TEST_COVERAGE_DIR = new File(TEST_BASE_DIR, "coverage");
    public static final File TEST_RESOURCE_DIR = new File("src/test/resources/test-files");

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








    public static final int MAX_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 12); //avoid hogging all RAM of machine
    public static final int MAX_CONFLICT_MERGES = 200;  //sample maximally this many merges per project to avoid bias towards giant projects
    public static final int MAX_CONFLICT_CHUNKS = 6;    //if a merge has more chunks than this, we don't attempt resolutions


    public static final List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern()); //patterns we check in Experiments


    public static final int HASH_PREFIX_LENGTH = 8;     //this many chars are used to ensure uniqueness/save paths using commit hash ids

    // Maven plugins
    public static final String JACOCO_PLUGIN = "org.jacoco:jacoco-maven-plugin";
    public static final String JACOCO_VERSION = "0.8.14";
    public static final String JACOCO_FULL = JACOCO_PLUGIN + ":" + JACOCO_VERSION;
}
