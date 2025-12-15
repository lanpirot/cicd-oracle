package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class MavenExecutionFactory {
    private final Path logDir;

    public MavenExecutionFactory(Path logDir) {
        this.logDir = logDir;
    }

    public IRunner createMavenRunner() {
        return new IRunner() {


            @Override
            public RunExecutionTIme run(Path mainProject, List<Path> variants, Boolean useMvnDaemon) {
                MavenRunner mavenRunner = new MavenRunner(logDir, false);
                RunExecutionTIme runExecutionTIme = new RunExecutionTIme();

                Instant start = Instant.now();
                mavenRunner.runWithoutCache(mainProject);
                Instant end = Instant.now();
                runExecutionTIme.setMainExecutionTime(Duration.between(start,end));

                start = Instant.now();
                mavenRunner.runWithoutCacheMultithread(variants.toArray(new Path[0]));
                end = Instant.now();
                runExecutionTIme.setVariantsExecutionTime(Duration.between(start,end));

                return runExecutionTIme;
            }
        };
    }
}
