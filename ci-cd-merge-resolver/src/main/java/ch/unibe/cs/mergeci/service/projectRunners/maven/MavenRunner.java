package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.util.FileUtils;
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
    private static final String COMPILATION_POSTFIX = "_compilation";

    public MavenRunner(Path logDir, boolean isUseMavenDaemon) {
        this.logDir = logDir;
        this.logDir.toFile().mkdirs();
        this.isUseMavenDaemon = isUseMavenDaemon;
    }

    public MavenRunner(Path tempDir) {
        this(tempDir, false);
    }

    public MavenRunner() {
        this(Paths.get("temp"));
    }

    public void run(Path... path) {
        String mavenCommand = resolveMavenCommand(path[0]);

        System.out.println(path[0].toAbsolutePath().toString());
        Process pr = null;
        injectCacheArtifact(path[0]);
        Path projectName = path[0].getFileName();
//        runCommand(new File(path[0]), logDir.resolve(projectName+"_compile").toFile(), mavenCommand, "compile", "-fae");
//        runCommand(new File(path[0]), logDir.resolve(projectName+"_compile-test").toFile(), mavenCommand, "test-compile", "-fae");
        runCommand(path[0].toFile(), logDir.resolve(projectName + COMPILATION_POSTFIX).toFile(), mavenCommand, "test", "-fae");


        for (int i = 1; i < path.length; i++) {
            mavenCommand = resolveMavenCommand(path[i]);
            try {
                injectCacheArtifact(path[i]);
                copyTarget(path[0].toFile(), path[i].toFile());
                FileUtils.copyDirectoryCompatibityMode(path[0].resolve(".cache").toFile(), path[i].resolve(".cache").toFile());
                projectName = path[i].getFileName();
//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile").toFile(),mavenCommand, "compile", "-fae");
//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile-test").toFile(),mavenCommand, "test-compile", "-fae");
                runCommand(path[i].toFile(), logDir.resolve(projectName + COMPILATION_POSTFIX).toFile(), mavenCommand, "test", "-fae");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void runWithCacheMultithread(Path... path) {
        String mavenCommand = "mvn.cmd";

        System.out.println(path[0].toAbsolutePath().toString());
        Process pr = null;
        injectCacheArtifact(path[0]);
        String projectName = path[0].getFileName().toString();
//        runCommand(new File(path[0]), logDir.resolve(projectName+"_compile").toFile(), mavenCommand, "compile", "-fae");
//        runCommand(new File(path[0]), logDir.resolve(projectName+"_compile-test").toFile(), mavenCommand, "test-compile", "-fae");
        runCommand(path[0].toFile(), logDir.resolve(projectName + COMPILATION_POSTFIX).toFile(), mavenCommand, "test", "-fae");


        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (int i = 1; i < path.length; i++) {
            int finalI = i;

            executorService.submit(() -> {
                        String projectNameFinal = path[finalI].getFileName().toString();
                        try {
                            injectCacheArtifact(path[finalI]);
                            copyTarget(path[0].toFile(), path[finalI].toFile());
                            FileUtils.copyDirectoryCompatibityMode(path[0].resolve(".cache").toFile(), path[finalI].resolve(".cache").toFile());

//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile").toFile(),mavenCommand, "compile", "-fae");
//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile-test").toFile(),mavenCommand, "test-compile", "-fae");
                            runCommand(path[finalI].toFile(), logDir.resolve(projectNameFinal + COMPILATION_POSTFIX).toFile(), mavenCommand, "test", "-fae");

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
            );
        }
        shutdownAndAwaitTermination(executorService);
    }


    public void runWithoutCache(Path... path) {
        for (int i = 0; i < path.length; i++) {
            final String mavenCommand = resolveMavenCommand(path[i]);

            Path projectName = path[i].getFileName();
//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile").toFile(),mavenCommand, "compile", "-fae");
//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile-test").toFile(),mavenCommand, "test-compile", "-fae");
            runCommand(path[i].toFile(), logDir.resolve(projectName + COMPILATION_POSTFIX).toFile(), mavenCommand, "-fae", "test");

        }
    }

    public void runWithoutCacheMultithread(Path... path) {

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (int i = 0; i < path.length; i++) {
            int finalI = i;

            executorService.submit(() -> {
                        String mavenCommand = resolveMavenCommand(path[finalI]);
                        String projectName = path[finalI].getFileName().toString();
                        try {

                            runCommand(path[finalI].toFile(), logDir.resolve(projectName + COMPILATION_POSTFIX).toFile(), mavenCommand, "-fae","-Dmaven.test.failure.ignore=true","test");

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
            );
        }
        shutdownAndAwaitTermination(executorService);
    }

    public void runWithCoverage(Path... path) {
        final String jacoco = "org.jacoco:jacoco-maven-plugin:0.8.14";
        String jacocoGoalPrepareAgent = ":prepare-agent";
        String jacocoGoalReport = ":report";

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (int i = 0; i < path.length; i++) {
            int finalI = i;

            executorService.submit(() -> {
                        String mavenCommand = resolveMavenCommand(path[finalI]);
                        String projectName = path[finalI].getFileName().toString();
                        try {

                            runCommand(path[finalI].toFile(), logDir.resolve(projectName + COMPILATION_POSTFIX).toFile(),
                                    mavenCommand,
                                    "-fae",
                                    "-Dmaven.test.failure.ignore=true",
                                    jacoco + jacocoGoalPrepareAgent,
                                    "test",
                                    jacoco + jacocoGoalReport);

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
            );
        }
        shutdownAndAwaitTermination(executorService);
    }

    /**
     * Helper function for calling git console commands
     */
    private static void runCommand(File directory, File outputDirectory, String... command) {
        System.out.println("Executing: " + Arrays.toString(command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory);
        pb.redirectErrorStream(true);
        if (outputDirectory != null) {
            pb.redirectOutput(outputDirectory);
        } else {
            pb.inheritIO();
        }

        Process process = null;
        try {
            process = pb.start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

    }

    private static void runCommand(File directory, String... command) {
        runCommand(directory, null, command);
    }

    private void updateStatusMavenFile(String fileInputPath,
                                       String projectNameOld, String projectNameNew, Set<String> conflictList) {
        File file = new File(fileInputPath);
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

    public void visitModules(String path, String projectNameOld, String projectNameNew, Set<String> conflictList) {
        File dir = new File(path);
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File newFile : files) {
            if (newFile.isDirectory()) {
                File pom = new File(newFile, "pom.xml");
                if (pom.exists()) {
                    updateStatusMavenFile(
                            newFile.getPath() + File.separator + "target" + File.separator + "maven-status" +
                                    File.separator + "maven-compiler-plugin" + File.separator + "compile" +
                                    File.separator + "default-compile" + File.separator + "inputFiles.lst",
                            projectNameOld, projectNameNew, conflictList
                    );
                }
                visitModules(newFile.getPath(), projectNameOld, projectNameNew, conflictList);
            }
        }
    }

    public void injectCacheArtifact(Path projectDir) {
        try {
            FileUtils.copyDirectoryCompatibityMode(
                    new File("src/main/resources/cache-artifacts/extensions.xml"),
                    projectDir.resolve(".mvn").resolve("extensions.xml").toFile());
            FileUtils.copyDirectoryCompatibityMode(
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
                            FileUtils.copyDirectoryCompatibityMode(dir.toFile(), destDir);
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
                mvnw.setExecutable(true);
                return "mvnw";
            }
            return "mvn";
        }
    }

    void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
}
