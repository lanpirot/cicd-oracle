package ch.unibe.cs;


import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Maven EventSpy extension that:
 * <ol>
 *   <li>Deletes stale surefire reports before compile phases</li>
 *   <li>Proactively fixes reactor artifact resolution broken by the Maven Build Cache Extension</li>
 *   <li>Optionally aborts builds early when the variant can't beat the current best
 *       (single-phase gate, replaces two separate Maven invocations)</li>
 * </ol>
 *
 * <h3>Early-abort gate</h3>
 * When {@code -Dcicd.threshold-file=<path>} points to a shared file containing a single
 * integer, the hook tracks successful/failed modules in this build. After every module
 * completes, it atomically posts its own {@code successfulModules} to the file (raising
 * the high-water mark), reads back the latest threshold, and aborts if the remaining
 * modules can no longer push the successful count to that threshold. Cross-JVM
 * synchronisation uses {@link FileLock}; the file is the source of truth shared across
 * every variant build running in parallel for the same merge run. On abort, a sidecar
 * JSON ({@code .cicd-hook-result.json}) is written to the reactor root so the caller
 * can read accurate module counts even when Maven exits abnormally.
 */
@Named
@Singleton
public class MavenHook extends AbstractEventSpy {
    private boolean isReportsDeleted;

    /** Shared cross-JVM threshold file from -Dcicd.threshold-file; null disables the gate. */
    private Path thresholdFile;
    /** Last threshold value read — used only for sidecar/log messages. */
    private int lastSeenThreshold = -1;

    private int successfulModules;
    private int completedModules;
    private int totalReactorModules;
    private boolean earlyAborted;

    private static Logger log = LogManager.getLogger(MavenHook.class);

    @Override
    public void init(Context context) throws Exception {
        log.info("Extension initialized!");
        isReportsDeleted = false;
        String filePath = System.getProperty("cicd.threshold-file");
        if (filePath != null && !filePath.isBlank()) {
            thresholdFile = Paths.get(filePath);
            log.info("Early-abort gate enabled: threshold file = {}", thresholdFile);
        }
    }

    @Override
    public void onEvent(Object event) throws Exception {
        log.debug("Event: {}", event.getClass().getName());
        if (event instanceof ExecutionEvent) {
            ExecutionEvent ee = (ExecutionEvent) event;

            log.debug("Type: {}", ee.getType());
            log.debug("MojoExecution: {}", ee.getMojoExecution());

            if (ee.getType() == ExecutionEvent.Type.SessionStarted) {
                MavenSession session = ee.getSession();
                totalReactorModules = session.getProjects().size();
                successfulModules = 0;
                completedModules = 0;
                earlyAborted = false;
                log.info("Reactor has {} modules", totalReactorModules);
            }

            if (ee.getType() == ExecutionEvent.Type.MojoStarted) {
                MojoExecution mojoExecution = ee.getMojoExecution();
                String lifecyclePhase = mojoExecution.getLifecyclePhase();
                log.debug("LifecyclePhase:{}", lifecyclePhase);

                if ((lifecyclePhase.equals("compile") || lifecyclePhase.equals("test-compile"))) {
                    MavenSession session = ee.getSession();
                    MavenProject mavenProject = session.getCurrentProject();
                    File surefireReportsPath;
                    surefireReportsPath = mavenProject.getBasedir().toPath().resolve("target").resolve("surefire-reports").toFile();

                    log.info("Delete folder: {}", surefireReportsPath);
                    FileUtils.deleteDirectory(surefireReportsPath);
                    isReportsDeleted = true;
                }

                // Proactive fix: before any module's compile starts, scan ALL reactor
                // siblings and fix broken artifacts.  The Maven Build Cache Extension
                // may restore a module without firing ProjectSucceeded/ProjectFailed,
                // leaving its RestoredArtifact.getFile() returning null.  If we wait
                // until ProjectSucceeded to fix it, downstream modules that depend on
                // the restored module fail dependency resolution before we ever get the
                // chance.  Scanning here catches restored modules before the current
                // module's dependency resolution runs.
                MavenSession session = ee.getSession();
                for (MavenProject sibling : session.getProjects()) {
                    fixReactorArtifact(sibling);
                    attachRestoredTestJarIfMissing(sibling);
                }
            }

            // Track module outcomes and check early-abort gate
            if (ee.getType() == ExecutionEvent.Type.ProjectSucceeded) {
                MavenProject project = ee.getProject();
                fixReactorArtifact(project);
                attachRestoredTestJarIfMissing(project);
                if (isCodeModule(project)) {
                    completedModules++;
                    successfulModules++;
                    checkEarlyAbortGate(ee.getSession());
                }
            }
            if (ee.getType() == ExecutionEvent.Type.ProjectFailed) {
                MavenProject project = ee.getProject();
                fixReactorArtifact(project);
                attachRestoredTestJarIfMissing(project);
                if (isCodeModule(project)) {
                    completedModules++;
                    checkEarlyAbortGate(ee.getSession());
                }
            }
        }
    }

    /** Returns true for jar/bundle modules (skip pom-only parents). */
    private boolean isCodeModule(MavenProject project) {
        String packaging = project.getPackaging();
        return "jar".equals(packaging) || "bundle".equals(packaging)
                || "war".equals(packaging) || "ear".equals(packaging);
    }

    /**
     * Post our running {@code successfulModules} to the shared file (atomic max),
     * read the latest threshold, and abort if we can no longer reach it.
     */
    private void checkEarlyAbortGate(MavenSession session) throws Exception {
        if (thresholdFile == null || earlyAborted) return;

        int threshold = postAndReadThreshold(successfulModules);
        if (threshold < 0) return; // file IO failed — treat as gate inactive
        lastSeenThreshold = threshold;
        if (threshold == 0) return; // no variant has set a high-water mark yet

        int remaining = totalReactorModules - completedModules;
        int bestPossible = successfulModules + remaining;

        if (bestPossible < threshold) {
            earlyAborted = true;
            log.info("[early-abort] {} successful + {} remaining = {} < threshold {} — aborting",
                    successfulModules, remaining, bestPossible, threshold);
            writeSidecar(session);
            throw new RuntimeException(
                    "cicd-early-abort: " + successfulModules + "/" + totalReactorModules
                            + " cannot reach threshold " + threshold);
        }
    }

    /**
     * Atomic read-and-post under {@link FileLock}. Reads the current int from the
     * threshold file, writes back {@code max(current, ourModules)}, returns the
     * value the file now holds. Returns -1 on IO failure (caller treats as gate inactive).
     */
    private int postAndReadThreshold(int ourModules) {
        try (FileChannel ch = FileChannel.open(thresholdFile,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = ch.lock()) {
            ByteBuffer buf = ByteBuffer.allocate(64);
            int bytesRead = ch.read(buf, 0);
            int current = 0;
            if (bytesRead > 0) {
                String s = new String(buf.array(), 0, bytesRead, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) {
                    try { current = Integer.parseInt(s); } catch (NumberFormatException ignored) {}
                }
            }
            int newVal = Math.max(current, ourModules);
            if (newVal != current) {
                byte[] bytes = String.valueOf(newVal).getBytes(StandardCharsets.UTF_8);
                ch.position(0);
                ch.write(ByteBuffer.wrap(bytes));
                ch.truncate(bytes.length);
            }
            return newVal;
        } catch (IOException e) {
            log.warn("Threshold file read/post failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Write a JSON sidecar file to the reactor root so the caller can read accurate
     * module counts even when the build was aborted.
     */
    private void writeSidecar(MavenSession session) {
        try {
            List<MavenProject> projects = session.getProjects();
            Path reactorRoot = projects.get(0).getBasedir().toPath();
            Path sidecar = reactorRoot.resolve(".cicd-hook-result.json");
            String json = String.format(
                    "{\"successfulModules\": %d, \"completedModules\": %d, \"totalModules\": %d, "
                            + "\"earlyAborted\": %b, \"threshold\": %d}",
                    successfulModules, completedModules, totalReactorModules,
                    earlyAborted, lastSeenThreshold);
            Files.writeString(sidecar, json);
            log.info("Wrote sidecar: {}", sidecar);
        } catch (IOException e) {
            log.warn("Failed to write sidecar", e);
        }
    }

    /**
     * Ensure the project's main artifact points to an existing file so the
     * ReactorReader can resolve it for downstream modules.
     */
    private void fixReactorArtifact(MavenProject project) {
        if (!"jar".equals(project.getPackaging())
                && !"bundle".equals(project.getPackaging())) {
            return;
        }
        Artifact artifact = project.getArtifact();
        if (artifact == null) return;

        // Check if the current artifact file is usable
        File currentFile;
        try {
            currentFile = artifact.getFile();
        } catch (Exception e) {
            // RestoredArtifact.getFile() can throw if the cache entry has no jar
            currentFile = null;
        }

        if (currentFile != null && currentFile.exists()) {
            return; // artifact is fine
        }

        // Point the artifact to target/classes — the standard reactor fallback
        File classesDir = new File(project.getBuild().getOutputDirectory());
        if (classesDir.isDirectory()) {
            artifact.setFile(classesDir);
            log.info("Fixed reactor artifact for {} → {}",
                    project.getArtifactId(), classesDir);
        }
    }

    /**
     * Re-attach a {@code tests}-classifier artifact for a project whose test-jar
     * metadata was lost — either because the maven-build-cache extension restored
     * the project but didn't re-attach the test-jar (hawkbit symptom), or because
     * the variant build runs a goal that doesn't include the {@code package} phase
     * (so {@code mvn-jar-plugin:test-jar} never fires) yet downstream reactor modules
     * still depend on {@code <project>:jar:tests:<version>} for compile/test-compile.
     *
     * <p>The attached artifact's {@code file} is whichever of these exists, in order:
     * <ol>
     *   <li>{@code target/<finalName>-tests.jar} — the proper jar (created when the
     *       package phase ran)</li>
     *   <li>{@code target/test-classes/} — the directory of compiled test classes
     *       (always present after {@code test-compile}). Maven's compiler accepts
     *       directories on classpath, so downstream modules that need
     *       {@code <project>:jar:tests} for compilation can use this.</li>
     * </ol>
     *
     * <p>Idempotent: skips packaging types that don't produce test-jars, skips when an
     * attached artifact with classifier "tests" already exists, skips when neither
     * the jar nor the test-classes dir exists on disk.
     *
     * <p>Separate concern from {@link #fixReactorArtifact}, which fixes the project's
     * MAIN artifact. The earlier attempt to clobber the main artifact's file regressed
     * cache_parallel because RestoredArtifact is a lazy wrapper; this method
     * deliberately leaves the main artifact alone and only ADDS an attachment.
     */
    private void attachRestoredTestJarIfMissing(MavenProject project) {
        String packaging = project.getPackaging();
        if (!"jar".equals(packaging) && !"bundle".equals(packaging)) return;

        // Some packaging configurations (e.g. parent POMs masquerading as jar in
        // synthetic test setups, or pre-validation projects) leave Build null or
        // partially-populated. Don't NPE the EventSpy dispatcher in those cases —
        // EventSpyDispatcher swallows the exception with a "Failed to notify spy"
        // warning and the rest of the build continues, but the warning shows up in
        // every variant's log and obscures real problems.
        org.apache.maven.model.Build build = project.getBuild();
        if (build == null) return;
        String dir = build.getDirectory();
        String finalName = build.getFinalName();
        String testOutputDir = build.getTestOutputDirectory();
        if (dir == null || finalName == null || testOutputDir == null) return;

        java.util.List<Artifact> attached = project.getAttachedArtifacts();
        if (attached != null) {
            for (Artifact a : attached) {
                if ("tests".equals(a.getClassifier()) && "jar".equals(a.getType())) {
                    return; // already attached
                }
            }
        }

        File testJarFile = new File(dir, finalName + "-tests.jar");
        File testClassesDir = new File(testOutputDir);

        File artifactFile;
        if (testJarFile.isFile()) {
            artifactFile = testJarFile;
        } else if (testClassesDir.isDirectory()) {
            artifactFile = testClassesDir;
        } else {
            return;
        }

        DefaultArtifactHandler handler = new DefaultArtifactHandler("test-jar");
        handler.setExtension("jar");
        handler.setLanguage("java");
        handler.setAddedToClasspath(true);
        DefaultArtifact testArtifact = new DefaultArtifact(
                project.getGroupId(),
                project.getArtifactId(),
                project.getVersion(),
                "test",
                "test-jar",
                "tests",
                handler);
        testArtifact.setFile(artifactFile);
        project.addAttachedArtifact(testArtifact);
        log.info("Attached restored test-jar for {} → {}",
                project.getArtifactId(), artifactFile);
    }

    @Override
    public void close() throws Exception {
        log.info("Extension closed!");
    }
}
