package ch.unibe.cs;


import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * When {@code -Dcicd.bestModules=N} is set, the hook tracks successful/failed modules.
 * After each module completes, if the remaining modules can't push the successful count
 * to N or above, it writes a sidecar JSON ({@code .cicd-hook-result.json}) to the reactor
 * root and throws to abort the build.  The caller reads the sidecar to get accurate module
 * counts even when Maven exits abnormally.
 */
@Named
@Singleton
public class MavenHook extends AbstractEventSpy {
    private boolean isReportsDeleted;

    /** Threshold from -Dcicd.bestModules; -1 means disabled. */
    private int bestModulesThreshold = -1;
    private int successfulModules;
    private int completedModules;
    private int totalReactorModules;
    private boolean earlyAborted;

    private static Logger log = LogManager.getLogger(MavenHook.class);

    @Override
    public void init(Context context) throws Exception {
        log.info("Extension initialized!");
        isReportsDeleted = false;
        String prop = System.getProperty("cicd.bestModules");
        if (prop != null) {
            try {
                bestModulesThreshold = Integer.parseInt(prop);
                log.info("Early-abort gate enabled: bestModules threshold = {}", bestModulesThreshold);
            } catch (NumberFormatException e) {
                log.warn("Invalid cicd.bestModules value: {}", prop);
            }
        }
    }

    @Override
    public void onEvent(Object event) throws Exception {
        log.debug("Event: {}", event.getClass().getName());
        if (event instanceof ExecutionEvent ee) {

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
                }
            }

            // Track module outcomes and check early-abort gate
            if (ee.getType() == ExecutionEvent.Type.ProjectSucceeded) {
                MavenProject project = ee.getProject();
                fixReactorArtifact(project);
                if (isCodeModule(project)) {
                    completedModules++;
                    successfulModules++;
                    checkEarlyAbortGate(ee.getSession());
                }
            }
            if (ee.getType() == ExecutionEvent.Type.ProjectFailed) {
                MavenProject project = ee.getProject();
                fixReactorArtifact(project);
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
     * After each module completes, check if it's still possible to reach the threshold.
     * If not, write the sidecar and abort.
     */
    private void checkEarlyAbortGate(MavenSession session) throws Exception {
        if (bestModulesThreshold < 0 || earlyAborted) return;

        int remaining = totalReactorModules - completedModules;
        int bestPossible = successfulModules + remaining;

        if (bestPossible < bestModulesThreshold) {
            earlyAborted = true;
            log.info("[early-abort] {} successful + {} remaining = {} < threshold {} — aborting",
                    successfulModules, remaining, bestPossible, bestModulesThreshold);
            writeSidecar(session);
            throw new RuntimeException(
                    "cicd-early-abort: " + successfulModules + "/" + totalReactorModules
                            + " cannot reach threshold " + bestModulesThreshold);
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
                    earlyAborted, bestModulesThreshold);
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

    @Override
    public void close() throws Exception {
        log.info("Extension closed!");
    }
}
