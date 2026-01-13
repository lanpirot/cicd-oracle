package org.example.demo.threeWayMergeTool;

import com.intellij.compiler.impl.ProjectCompileScope;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.project.Project;

public class CompilationHelper {
    public static void compileProject(Project project, CompilationListener listener) {

        CompileScope scope = new ProjectCompileScope(project);
        CompilerManager.getInstance(project).make(scope, new CompileStatusNotification() {
            @Override
            public void finished(boolean aborted, int errors, int warnings, CompileContext context) {
                boolean success = errors == 0;
                // pass the result to the listener
                listener.onCompilationFinished(success);
            }
        });
    }
}
