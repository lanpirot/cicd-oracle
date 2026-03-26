package ch.unibe.cs.mergeci.conflict;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Stream;

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

        // maven-download-plugin (wget goal) failed to fetch a remote resource at build time.
        // The URL is dead or unreachable — no variant can fix this.
        if (content.contains("IO Error: Could not get content")) {
            return true;
        }

        // Frontend toolchain failure (Node.js / Gulp / npm version mismatch)
        if (content.contains("frontend-maven-plugin") && content.contains("BUILD FAILURE")) {
            return true;
        }

        // A system command (node, python, ruby, …) invoked by exec-maven-plugin is not on
        // the system PATH.  Exit code 127 is the POSIX "command not found" signal from
        // /usr/bin/env.  No source change can install a missing system binary.
        if (content.contains("No such file or directory") && content.contains("Exit value: 127")) {
            return true;
        }

        // No pom.xml at the checkout root (e.g. merge commit pre-dates Maven adoption in the repo)
        if (content.contains("MissingProjectException")
                || content.contains("requires a project to execute but there is no POM")) {
            return true;
        }

        // Maven wrapper (mvnw) failed to bootstrap. Maven never ran at all; no variant can fix this.
        // Known failure modes:
        //   - wrapper JAR missing/corrupt: "Could not find or load main class MavenWrapperMain"
        //   - downloaded Maven distribution ZIP is corrupted: ZipException from Installer.unzip
        if (content.contains("Could not find or load main class org.apache.maven.wrapper.MavenWrapperMain")
                || (content.contains("MavenWrapperMain") && content.contains("ZipException"))) {
            return true;
        }

        // maven-toolchains-plugin references a JDK path that does not exist on this machine
        if (content.contains("Misconfigured toolchains")
                || content.contains("Non-existing JDK home configuration")) {
            return true;
        }

        // A plugin's own classpath is missing a class that was part of an older Maven/Plexus/Aether
        // internal API (e.g. android-maven-plugin:3.5.1 needs org.sonatype.aether.RepositorySystem
        // which was removed in Maven 3.1+).  The plugin is structurally incompatible with the
        // installed Maven — no variant can fix this.
        if (content.contains("A required class was missing while executing")) {
            return true;
        }

        // Corrupted or empty JAR in the local Maven repository (~/.m2/repository/).
        // Typically caused by an interrupted download that left a zero-byte file on disk.
        // Maven's bundle/shade/jar plugins detect this when scanning the classpath.
        // No variant can fix a corrupted local cache entry.
        if (content.contains("zip file is empty") || content.contains("seems corrupted")) {
            return true;
        }

        // Kotlin compiler daemon failed to start or connect (resource contention, OOM, or
        // filesystem locking under parallel builds).  Transient infrastructure issue —
        // no source change can make the Kotlin daemon connect.
        if (content.contains("Failed connecting to the daemon")) {
            return true;
        }

        // Maven wrapper (mvnw) embeds --add-opens JVM arguments for the Java 9+ module system.
        // When JAVA_HOME points to Java 8, the JVM exits immediately because it does not
        // recognise --add-opens.  Maven never ran at all — no variant can fix a JVM version
        // mismatch at the wrapper level.
        if (content.contains("Unrecognized option: --add-opens")
                && content.contains("Could not create the Java Virtual Machine")) {
            return true;
        }

        // Multi-release JAR build: class files compiled with a newer JDK than the current
        // compilation phase expects (e.g. main sources at Java 17, module-info.java at Java 11).
        // No variant can fix this — it is a structural build infrastructure mismatch.
        if (content.contains("class file has wrong version")
                && content.contains("Please remove or make sure it appears in the correct subdirectory")) {
            return true;
        }

        // JaCoCo agent tries to instrument a class already instrumented by ByteBuddy/PowerMock.
        // This is a toolchain incompatibility between our JaCoCo version and the project's mocking
        // framework — no variant can fix it.
        if (content.contains("Please supply original non-instrumented classes")) {
            return true;
        }

        // Maven could not even parse the POM or resolve a parent/import POM.
        // Covers private Nexus repos (401), non-resolvable parent POMs, etc.
        if (content.contains("ProjectBuildingException")
                || content.contains("Non-resolvable parent POM")
                || content.contains("Non-resolvable import POM")) {
            return true;
        }

        // exec-maven-plugin invoked a system command (make, cargo, …) that is not installed.
        // "Cannot run program" with "No such file or directory" is the JVM's ProcessBuilder
        // reporting a missing executable — distinct from Exit value 127 (POSIX shell).
        if (content.contains("Cannot run program") && content.contains("No such file or directory")) {
            return true;
        }

        // maven-enforcer-plugin rejected the build environment (wrong JDK, banned dependency, etc.).
        // When enforcer fires before compilation even starts, no variant can fix the environment.
        if (content.contains("maven-enforcer-plugin") && content.contains("enforce")
                && content.contains("BUILD FAILURE")) {
            return true;
        }

        // Surefire forked VM terminated abnormally (JVM crash, OOM-killed, System.exit).
        // The test infrastructure is broken — no variant can fix a JVM crash.
        if (content.contains("The forked VM terminated without properly saying goodbye")) {
            return true;
        }

        // javac's --release flag is not supported by the installed JDK (pre-Java 9).
        // No variant can install a newer JDK.
        if (content.contains("invalid flag: --release")) {
            return true;
        }

        return false;
    }

    /**
     * Returns true when javac ran and produced source-level errors containing a
     * {@code File.java:[line,col]} reference — the hallmark of a genuine compilation
     * error in the merged source (e.g. committed conflict markers or a missing symbol).
     *
     * <p>Also catches the silent-failure case where javac runs in a forked JVM
     * ({@code maven.compiler.fork=true}) and its error output is not captured in the
     * Maven log stream.  In that case the log shows "Compiling N source files" followed
     * by "Compilation failure" with no intervening error lines.  Javac clearly started,
     * so the failure is in the source — not in infrastructure.
     */
    public static boolean isGenuineCompilationError(Path logFile) {
        String content = readLog(logFile);
        if (content == null) return false;

        // javac emitted source-level errors (e.g. committed conflict markers, missing symbol)
        if (content.contains("COMPILATION ERROR") && content.contains(".java:[")) {
            return true;
        }

        // Checkstyle violation introduced by the merge (e.g. duplicate import from both sides)
        if (content.contains("Checkstyle violation")) {
            return true;
        }

        // Forked javac (maven.compiler.fork=true): errors go to the child JVM's stdout which
        // is not captured in the Maven log.  The only evidence is that javac started ("Compiling
        // N source files") and then Maven reported "Compilation failure".  Since javac ran, the
        // failure is in the source, not in infrastructure.
        if (content.contains("Compiling ") && content.contains(" source files")
                && content.contains("Compilation failure")) {
            return true;
        }

        return false;
    }

    /**
     * Returns true when a build descriptor file in the project contains unresolved git conflict
     * markers ({@code <<<<<<<}, {@code =======}, {@code >>>>>>>}).  Such a merge broke the build
     * infrastructure itself rather than Java source code, so the log-based infra-failure patterns
     * (e.g. DependencyResolutionException) fire — but a generated variant may resolve the conflict
     * differently and produce a clean build file.  These merges should be treated as
     * {@code brokenMerge=true}, not skipped as infrastructure failures.
     *
     * <p>Scans {@code pom.xml} files (root + all modules), {@code package.json}, and
     * {@code *.gradle} / {@code *.gradle.kts} files, skipping {@code target/} and {@code .git/}.
     */
    public static boolean hasBuildFileConflictMarkers(Path projectPath) {
        List<PathMatcher> matchers = List.of(
                FileSystems.getDefault().getPathMatcher("glob:**/pom.xml"),
                FileSystems.getDefault().getPathMatcher("glob:**/package.json"),
                FileSystems.getDefault().getPathMatcher("glob:**/*.gradle"),
                FileSystems.getDefault().getPathMatcher("glob:**/*.gradle.kts")
        );
        try (Stream<Path> walk = Files.walk(projectPath)) {
            return walk
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/target/") && !s.contains("/.git/");
                    })
                    .filter(p -> matchers.stream().anyMatch(m -> m.matches(p)))
                    .anyMatch(BuildFailureClassifier::containsConflictMarkers);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsConflictMarkers(Path file) {
        try {
            String content = Files.readString(file);
            return content.contains("<<<<<<<") && content.contains("=======") && content.contains(">>>>>>>");
        } catch (IOException e) {
            return false;
        }
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
