package ch.unibe.cs.mergeci.conflict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classifies Maven build failures by inspecting the compilation log.
 *
 * Three mutually exclusive categories:
 *  - Infrastructure failure: Maven cannot resolve artifacts or plugins from a (dead) remote
 *    repository, or a frontend toolchain (Node/Gulp) is incompatible.  Permanently unfixable.
 *  - Genuine compilation error: javac ran but emitted source-level errors (e.g. the merge
 *    commit committed unresolved conflict markers, or a symbol is missing).  A generated
 *    variant may resolve the conflict differently and compile cleanly — these merges are
 *    valuable and should be included in the dataset with {@code baselineBroken=true}.
 *  - Unknown: neither pattern matched (treated as a generic build failure).
 */
public class BuildFailureClassifier {

    public static boolean isInfraFailure(Path logFile) {
        String content = readLog(logFile);
        if (content == null) return false;

        // Dead Maven artifact / plugin repository (cached 404 or network failure)
        if (content.contains("could not be resolved")
                || content.contains("was not found in")
                || content.contains("PluginResolutionException")
                || content.contains("DependencyResolutionException")) {
            return true;
        }

        // Frontend toolchain failure (Node.js / Gulp / npm version mismatch)
        if (content.contains("frontend-maven-plugin") && content.contains("BUILD FAILURE")) {
            return true;
        }

        return false;
    }

    /**
     * Returns true when javac ran and produced source-level errors containing a
     * {@code File.java:[line,col]} reference — the hallmark of a genuine compilation
     * error in the merged source (e.g. committed conflict markers or a missing symbol).
     */
    public static boolean isGenuineCompilationError(Path logFile) {
        String content = readLog(logFile);
        if (content == null) return false;
        return content.contains("COMPILATION ERROR") && content.contains(".java:[");
    }

    private static String readLog(Path logFile) {
        if (logFile == null || !logFile.toFile().exists()) return null;
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            return null;
        }
    }
}
