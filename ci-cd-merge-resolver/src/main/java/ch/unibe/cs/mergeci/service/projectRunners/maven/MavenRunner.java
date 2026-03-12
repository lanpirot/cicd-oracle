package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.Utility;
import lombok.Getter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Getter
public class MavenRunner {
    private final Path logDir;
    private final boolean isUseMavenDaemon;
    private final int timeoutMinutes;
    private static final String COMPILATION_POSTFIX = "_compilation";

    public MavenRunner(Path logDir, boolean isUseMavenDaemon, int timeoutMinutes) {
        this.logDir = logDir;
        this.logDir.toFile().mkdirs();
        this.isUseMavenDaemon = isUseMavenDaemon;
        this.timeoutMinutes = timeoutMinutes;
    }

    public MavenRunner(Path tempDir, int timeoutMinutes) {
        this(tempDir, false, timeoutMinutes);
    }

    public MavenRunner(Path tempDir) {
        this(tempDir, false, AppConfig.MAVEN_BUILD_TIMEOUT_VARIANT_TESTING_MINUTES);
    }

    public MavenRunner() {
        this(AppConfig.TMP_DIR, false, AppConfig.MAVEN_BUILD_TIMEOUT_VARIANT_TESTING_MINUTES);
    }

    public void run_cache_parallel(Path... path) {
        ArrayList<String> mavenCommands = new ArrayList<String>();
        for (int i = 0; i < path.length; i++)
            mavenCommands.add(resolveMavenCommand(path[i]));

        System.out.println(path[0].toAbsolutePath());
        injectCacheArtifact(path[0]);
        String projectName = path[0].getFileName().toString();
        runCommand(path[0], logDir.resolve(projectName + COMPILATION_POSTFIX), timeoutMinutes, mavenCommands.getFirst(), AppConfig.MAVEN_FAIL_MODE, AppConfig.MAVEN_TEST_FAILURE_IGNORE, "test");



        ExecutorService executorService = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

        for (int i = 1; i < path.length; i++) {
            final int finalI = i;

            executorService.submit(() -> {
                        String projectNameFinal = path[finalI].getFileName().toString();
                        try {
                            injectCacheArtifact(path[finalI]);
                            copyTarget(path[0].toFile(), path[finalI].toFile());
                            FileUtils.copyDirectoryCompatibilityMode(path[0].resolve(".cache").toFile(), path[finalI].resolve(".cache").toFile());
                            runCommand(path[finalI], logDir.resolve(projectNameFinal + COMPILATION_POSTFIX), timeoutMinutes, mavenCommands.get(finalI), "-o", AppConfig.MAVEN_FAIL_MODE, AppConfig.MAVEN_TEST_FAILURE_IGNORE, "test");

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
            );
        }
        Utility.shutdownAndAwaitTermination(executorService);
    }


    public void run_no_optimization(Path... path) {
        for (int i = 0; i < path.length; i++) {
            final String mavenCommand = resolveMavenCommand(path[i]);
            Path projectName = path[i].getFileName();
            runCommand(path[i], logDir.resolve(projectName + COMPILATION_POSTFIX), timeoutMinutes, mavenCommand, AppConfig.MAVEN_FAIL_MODE, AppConfig.MAVEN_TEST_FAILURE_IGNORE, "test");
        }
    }

    public void run_parallel(Path... path) {
        ExecutorService executorService = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);
        for (int i = 0; i < path.length; i++) {
            int finalI = i;
            executorService.submit(() -> {
                        String mavenCommand = resolveMavenCommand(path[finalI]);
                        String projectName = path[finalI].getFileName().toString();
                        try {
                            runCommand(path[finalI], logDir.resolve(projectName + COMPILATION_POSTFIX), timeoutMinutes, mavenCommand, AppConfig.MAVEN_FAIL_MODE, AppConfig.MAVEN_TEST_FAILURE_IGNORE, "test");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
            );
        }
        Utility.shutdownAndAwaitTermination(executorService);
    }

    public void runWithCoverage(Path... path) {
        final String jacoco = AppConfig.JACOCO_FULL;
        String jacocoGoalPrepareAgent = ":prepare-agent";
        String jacocoGoalReport = ":report";

        ExecutorService executorService = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

        for (int i = 0; i < path.length; i++) {
            int finalI = i;

            executorService.submit(() -> {
                        String mavenCommand = resolveMavenCommand(path[finalI]);
                        String projectName = path[finalI].getFileName().toString();
                        try {

                            runCommand(path[finalI], logDir.resolve(projectName + COMPILATION_POSTFIX),
                                    timeoutMinutes,
                                    mavenCommand,
                                    AppConfig.MAVEN_FAIL_MODE,
                                    AppConfig.MAVEN_TEST_FAILURE_IGNORE,
                                    jacoco + jacocoGoalPrepareAgent,
                                    "test",
                                    jacoco + jacocoGoalReport);

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
            );
        }
        Utility.shutdownAndAwaitTermination(executorService);
    }

    /**
     * Helper function for calling Maven commands with timeout.
     * Builds exceeding the timeout will be forcibly terminated.
     *
     * @param directory Working directory for the command
     * @param outputDirectory Output file for command output (null for inherited IO)
     * @param timeoutMinutes Timeout in minutes before forcibly killing the process
     * @param command Command and arguments to execute
     */
    private static void runCommand(Path directory, Path outputDirectory, int timeoutMinutes, String... command) {
        System.out.println("Executing: " + Arrays.toString(command) + " (timeout: " + timeoutMinutes + " minutes)");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);
        if (outputDirectory != null) {
            pb.redirectOutput(outputDirectory.toFile());
        } else {
            pb.inheritIO();
        }

        Process process = null;
        try {
            process = pb.start();
            boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!completed) {
                // Timeout occurred - kill the process
                System.err.println("TIMEOUT: Build exceeded " + timeoutMinutes +
                    " minutes. Killing process: " + Arrays.toString(command));
                process.destroyForcibly();
                process.waitFor(); // Wait for forced termination to complete
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }

    }

    private void updateStatusMavenFile(File file,
                                       String projectNameOld, String projectNameNew, Set<String> conflictList) {
        if (!file.exists()) {
            return;
        }

        List<String> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!isConflict(line, conflictList)) {
                    line = line.replace(projectNameOld, projectNameNew);
                }
                list.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (String line : list) {
                bw.write(line);
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isConflict(String line, Set<String> conflictList) {
        Path filePath = Path.of(line);
        return conflictList.stream()
                .map(Path::of)
                .anyMatch(filePath::endsWith);
    }

    public void injectCacheArtifact(Path projectDir) {
        try {
            FileUtils.copyDirectoryCompatibilityMode(
                    new File("src/main/resources/cache-artifacts/extensions.xml"),
                    projectDir.resolve(".mvn").resolve("extensions.xml").toFile());
            FileUtils.copyDirectoryCompatibilityMode(
                    new File("src/main/resources/cache-artifacts/maven-build-cache-config.xml"),
                    projectDir.resolve(".mvn").resolve("maven-build-cache-config.xml").toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean copyTarget(File src, File dst) {
        if (!src.exists() || !src.isDirectory()) {
            System.err.println("Source project not found: ");
            return false;
        }

        if (!dst.exists()) {
            dst.mkdirs();
        }

        try (Stream<Path> walk = Files.walk(src.toPath())) {
            walk.filter(path -> path.toFile().isDirectory() && path.getFileName().toString().equals("target"))
                    .forEach(dir -> {
                        Path relative = src.toPath().relativize(dir);
                        File destDir = dst.toPath().resolve(relative).toFile();


                        System.out.println("Copying target folder: " + dst + "->" + destDir);
                        try {
                            FileUtils.copyDirectoryCompatibilityMode(dir.toFile(), destDir);
//                            FileUtils.deleteDirectory(destDir.toPath().resolve("surefire-reports").toFile());
                        } catch (IOException e) {
                            e.printStackTrace();
                            System.err.println("Failed to copy " + dst + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }


    private String resolveMavenCommand(Path projectPath) {
        if (isUseMavenDaemon) {
            return "mvnd";
        }

        boolean isWindows = System.getProperty("os.name")
                .toLowerCase(Locale.ENGLISH)
                .contains("windows");

        File mvnwCmd = projectPath.resolve("mvnw.cmd").toFile();
        File mvnw = projectPath.resolve("mvnw").toFile();

        if (isWindows) {
            if (mvnwCmd.exists()) {
                return "mvnw.cmd";
            }
            return "mvn.cmd";
        } else {
            if (mvnw.exists()) {
                //fix for Unix: first set executable, then replace wrong line endings
                mvnw.setExecutable(true);
                try {
                    String content = new String(Files.readAllBytes(mvnw.toPath()));
                    content = content.replace("\r\n", "\n").replace("\r", "\n");
                    Files.write(mvnw.toPath(), content.getBytes());
                } catch (IOException e) {}

                return "./mvnw";
            }
            return "mvn";
        }
    }
}
