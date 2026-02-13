package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.experimentSetup.DatasetCollectorTest;
import ch.unibe.cs.mergeci.experimentSetup.ExcelWriterTest;
import ch.unibe.cs.mergeci.experimentSetup.MetricsAnalyzerTest;
import ch.unibe.cs.mergeci.experimentSetup.RepoCollectorTest;
import ch.unibe.cs.mergeci.experimentSetup.coverageCalculater.CoverageCalculatorTest;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.ExperimentRunnerTest;
import ch.unibe.cs.mergeci.service.MergeAnalyzerTest;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResultTest;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunnerTest;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestResultTest;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotalTest;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotalXmlTest;
import ch.unibe.cs.mergeci.util.FileUtilsTest;
import ch.unibe.cs.mergeci.util.GitUtilsTest;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtilsTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;


/**
 * Test suite that runs all tests with ExperimentRunnerTest running last.
 * This ensures that the potentially slower or resource-intensive ExperimentRunnerTest
 * is executed after all other tests have completed.
 */
@Suite
@SelectClasses({
    // Utility tests
    FileUtilsTest.class,
    GitUtilsTest.class,
    ProjectBuilderUtilsTest.class,

    // Maven runner tests
    CompilationResultTest.class,
    TestResultTest.class,
    TestTotalTest.class,
    TestTotalXmlTest.class,

    // Service tests
    MergeAnalyzerTest.class,

    // Experiment setup tests
    ExcelWriterTest.class,
    RepoCollectorTest.class,
    DatasetCollectorTest.class,
    MetricsAnalyzerTest.class,
    CoverageCalculatorTest.class,

    // Run Runners last
    MavenRunnerTest.class,
    RepoCollectorTest.class,
    ExperimentRunnerTest.class
})
public class AllTestsSuite {
}