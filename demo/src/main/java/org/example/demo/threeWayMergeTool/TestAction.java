package org.example.demo.threeWayMergeTool;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import org.jetbrains.annotations.NotNull;

import static com.intellij.execution.junit.JUnitUtil.isTestClass;

public class TestAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        //TestResultCounter testResultCounter = new TestResultCounter(e.getProject());
       /* PsiClass psiClass = JavaPsiFacade.getInstance(e.getProject()).findClass("Main", GlobalSearchScope.allScope(e.getProject()));
        PsiClass[] classes = JavaPsiFacade.getInstance(e.getProject()).findPackage("org.example") // Пустая строка для корневого пакета
                .getClasses(GlobalSearchScope.allScope(e.getProject()));
        for (PsiClass cls : classes) {
            System.out.println(cls.getQualifiedName());
        }*/
//        try {
//            TestRunner.runJUnitTests(e.getProject());
//        } catch (ExecutionException ee) {
//            throw new RuntimeException(ee);
//        }
        new Task.Backgroundable(e.getProject(), "Loading Merge Data", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                GitUtils.checkoutWithDiff3Conflict(e.getProject());
            }
        }.queue();
    }

}
