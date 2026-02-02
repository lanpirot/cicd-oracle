package ch.unibe.cs;


import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

        }
    }

    @Override
    public void close() throws Exception {
        log.info("Extension closed!");
    }
}

