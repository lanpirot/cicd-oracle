package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.present.ResultsPresenterTest;
import ch.unibe.cs.mergeci.experiment.ResolutionVariantRunnerTest;
import ch.unibe.cs.mergeci.runner.VariantProjectBuilderTest;
import ch.unibe.cs.mergeci.runner.maven.CompilationResultTest;
import ch.unibe.cs.mergeci.runner.maven.TestResultTest;
import ch.unibe.cs.mergeci.runner.maven.TestTotalTest;
import ch.unibe.cs.mergeci.runner.maven.TestTotalXmlTest;
import ch.unibe.cs.mergeci.util.FileUtilsTest;
import ch.unibe.cs.mergeci.util.GitUtilsTest;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtilsTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;


/**
 * Test suite that runs all tests with ResolutionVariantRunnerTest running last.
 * This ensures that the potentially slower or resource-intensive ResolutionVariantRunnerTest
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
    VariantProjectBuilderTest.class,
    ResultsPresenterTest.class,

    // Run Runners last
    ResolutionVariantRunnerTest.class
})
public class AllTestsSuite {
}
