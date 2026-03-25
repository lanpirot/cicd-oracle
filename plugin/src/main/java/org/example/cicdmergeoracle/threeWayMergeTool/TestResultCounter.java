package org.example.cicdmergeoracle.threeWayMergeTool;

import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.TestStatusListener;
import org.jetbrains.annotations.Nullable;

public class TestResultCounter extends TestStatusListener {
    private ThreeWayMergePanel mergePanel;

    private int passedTests = 0;
    private int failedTests = 0;

    private TestResultCounter() {
    }


    public int getPassedTests() {
        return passedTests;
    }

    public int getFailedTests() {
        return failedTests;
    }

    public void setMergePanel(ThreeWayMergePanel mergePanel) {
        this.mergePanel = mergePanel;
    }

    @Override
    public void testSuiteFinished(@Nullable AbstractTestProxy root) {
        passedTests = 0;
        failedTests = 0;


        for (AbstractTestProxy test : root.getAllTests()) {
            System.out.println(test.getName());
            if (test.isLeaf()) {
                if (test.isPassed()) {
                    passedTests++;
                } else if (test.isDefect()) {
                    failedTests++;
                }
            }
        }

        if(mergePanel != null) {
            mergePanel.updateTestResults(passedTests, failedTests);
        }
        System.out.println("Passed tests: " + passedTests);
        System.out.println("Failed tests: " + failedTests);
    }

   /* public static void runAllTests(Project project) {
        RunManager runManager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings configurationSettings = runManager.findConfigurationByName("All Tests");

        ExecutionEnvironment environment = new ExecutionEnvironmentBuilder(project, DefaultRunExecutor.getRunExecutorInstance())
                .runProfile(configurationSettings.getConfiguration())
                .build();
        for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
            if (settings.getConfiguration().getName().contains("All Tests")) {
                // Run a configuration that includes all tests
                ProgramRunner<?> runner = ProgramRunner.getRunner("Run", settings.getConfiguration());
                if (runner != null) {
                    try {
                        runner.execute(environment, null);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                break;
            }
        }
    }*/
}
