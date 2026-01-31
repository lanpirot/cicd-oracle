package ch.unibe.cs.mergeci.config;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import ch.unibe.cs.mergeci.model.patterns.TheirsPattern;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.List;

@Configuration
public class AppConfig {
    public static final String BASE_DIR = "/home/lanpirot/";
    public static final String DATA_BASE_DIR = BASE_DIR + "data/bruteforcemerge/";


    public static final File INPUT_PROJECT_XLSX = new File(DATA_BASE_DIR + "projects_Java_desc-stars-1000.xlsx"); // Excel file with list of repositories  and their repo URL
    public static final File DATASET_DIR = new File(DATA_BASE_DIR + "datasets/");       // directory with dataset that were collected by `RepoCollector`
    public static final File REPO_DIR = new File(BASE_DIR + "tmp/bruteforce_repos");                      // name of directory to clone projects
    public static final String EXPERIMENTS_DIR = DATA_BASE_DIR + "experiments/";
    public static final File TMP_DIR = new File(BASE_DIR + "tmp/bruteforce_tmp");           // temporary working directory

    // Test-specific directories (all test output goes here)
    public static final String TEST_BASE_DIR = DATA_BASE_DIR + "test/";
    public static final File INPUT_PROJECT_XLSX_TEST = new File(TEST_BASE_DIR + "projects_test.xlsx"); // Excel file with list of repositories  and their repo URL
    public static final File TEST_TEMP_DIR = new File(TEST_BASE_DIR + "temp/");
    public static final File TEST_MYTEMP_DIR = new File(TEST_BASE_DIR + "mytemp/");
    public static final File TEST_COVERAGE_DIR = new File(TEST_BASE_DIR + "coverageTemp/");
    public static final File TEST_DATASET_TEMP_DIR = new File(TEST_BASE_DIR + "dataset_temp/");
    public static final String TEST_EXPERIMENTS_DIR = TEST_BASE_DIR + "experiments/";
    public static final File TEST_RESOURCE_DIR = new File("src/test/resources/test-merge-projects/");
    public static final File TEST_RESOURCE_DIR2 = new File("src/test/resources/test-files/");





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
