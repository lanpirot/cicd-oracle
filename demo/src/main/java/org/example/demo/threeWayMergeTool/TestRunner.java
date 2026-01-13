package org.example.demo.threeWayMergeTool;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.execution.junit.JUnitUtil.isTestClass;

public class TestRunner {
    public static void runJUnitTests(Project project) throws ExecutionException {

        List<PsiClass> testClasses = findAllTestClasses(project);
        // Create a configuration to run a JUnit test

        JUnitConfigurationType configurationType = JUnitConfigurationType.getInstance();
        for (PsiClass testClass : testClasses) {
            JUnitConfiguration configuration = new JUnitConfiguration("Run Tests", project, configurationType.getConfigurationFactories()[0]);

            configuration.setMainClass(testClass);

            // Set the test class to run
            configuration.setName("Run " + testClass.getName());

            // Get Executor and create execution environment
            Executor executor = DefaultRunExecutor.getRunExecutorInstance();
            ExecutionEnvironmentBuilder.create(project, executor, configuration).buildAndExecute();

            System.out.println(testClass.getName());

            SMTestProxy.SMRootTestProxy rootTest = new SMTestProxy.SMRootTestProxy();
            rootTest.getChildren().forEach(testProxy -> {
                if (testProxy.isPassed()) {
                    System.out.println("bla");
                } else {
                    System.out.println("bu");
                }
            });
        }
    }

    public static List<PsiClass> findAllTestClasses(@NotNull Project project) {
        List<PsiClass> testClasses = new ArrayList<>();

        Query<PsiClass> query = AllClassesSearch.search(GlobalSearchScope.projectScope(project),project);
        for (PsiClass psiClass : query.findAll()) {
            if (isTestClass(psiClass)) {
                testClasses.add(psiClass);
            }
        }

        return testClasses;
    }
    /*private static boolean isTestClass(PsiClass psiClass) {
        // Checking for the presence of @Test or @RunWith annotation on a class or its methods
        for (PsiMethod method : psiClass.getMethods()) {
            PsiAnnotation testAnnotation = method.getAnnotation("org.junit.Test");
            PsiAnnotation testNGAnnotation = method.getAnnotation("org.testng.annotations.Test");
            PsiAnnotation jupyter = method.getAnnotation("org.junit.jupiter.api.Test");
            if (testAnnotation != null || testNGAnnotation != null || jupyter != null) {
                return true;
            }
        }
        return false;
    }*/
}
