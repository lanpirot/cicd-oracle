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

@Named
@Singleton
public class MavenHook extends AbstractEventSpy {
    private boolean isReportsDeleted;

    private static Logger log = LogManager.getLogger(MavenHook.class);

    @Override
    public void init(Context context) throws Exception {
        log.info("Extension initialized!");
        isReportsDeleted = false;
    }

    @Override
    public void onEvent(Object event) throws Exception {
        log.debug("Event: {}", event.getClass().getName());
        if (event instanceof ExecutionEvent) {
            ExecutionEvent ee = (ExecutionEvent) event;

            log.debug("Type: {}", ee.getType());

            log.debug("MojoExecution: {}", ee.getMojoExecution());

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
            }

            // Fix reactor artifact resolution after Maven Build Cache Extension partial restore.
            //
            // When the cache extension partially restores a module (e.g. compile cached,
            // test requested), it replaces the project's artifact with a RestoredArtifact
            // whose getFile() lazily fetches from the cache.  For compile-only cache entries
            // there IS no jar — getFile() returns null or a non-existent path.  Downstream
            // reactor modules then fail dependency resolution:
            //   "Could not find artifact <sibling>:jar:..."
            //
            // Fix: after each module completes, ensure its artifact file points to
            // target/classes (the compile output directory) if the current file is missing.
            // This is exactly what Maven's ReactorReader expects for intra-reactor
            // resolution during "mvn test" (where no jar is produced).
            if (ee.getType() == ExecutionEvent.Type.ProjectSucceeded
                    || ee.getType() == ExecutionEvent.Type.ProjectFailed) {
                MavenProject project = ee.getProject();
                fixReactorArtifact(project);
            }
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

