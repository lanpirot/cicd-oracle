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

    public IRunner createMavenRunner(boolean isParallel, boolean isCache) {
        return new IRunner() {
            @Override
            public RunExecutionTIme run(Path mainProject, List<Path> variants, Boolean useMvnDaemon) {
                MavenRunner mavenRunner = new MavenRunner(logDir, false);
                RunExecutionTIme runExecutionTime = new RunExecutionTIme();

                Instant start = Instant.now();
                mavenRunner.run_no_optimization(mainProject);
                Instant end = Instant.now();
                runExecutionTime.setMainExecutionTime(Duration.between(start, end));

                start = Instant.now();
                if (isParallel && isCache)
                    mavenRunner.run_cache_parallel(variants.toArray(new Path[0]));
                else if (isParallel)
                    mavenRunner.run_parallel(variants.toArray(new Path[0]));
                else if (!isCache)
                    mavenRunner.run_no_optimization(variants.toArray(new Path[0]));
                else System.out.println("ERROR in createMavenRunner!");
                end = Instant.now();
                runExecutionTime.setVariantsExecutionTime(Duration.between(start, end));

                return runExecutionTime;
            }
        };
    }
}
