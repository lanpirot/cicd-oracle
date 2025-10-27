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
import java.util.stream.Stream;

@Getter
public class MavenRunner implements IRunner {
    private final Path tempDir;
    private final static String LOG_PATH = "log";
    private final Path logDir;

    public MavenRunner(Path tempDir) {
        this.tempDir = tempDir;
        this.logDir = tempDir.resolve(LOG_PATH);
        this.logDir.toFile().mkdirs();
    }

    public MavenRunner() {
        this(Paths.get("temp"));
    }

    public void run(String... path) {
        final String mavenCommand = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows") ? "mvn.cmd" : "mvn";

        System.out.println(new File(path[0]).getAbsolutePath());
        Process pr = null;
        injectCacheArtifact(path[0]);
        Path projectName = Paths.get(path[0]).getFileName();
//        runCommand(new File(path[0]), logDir.resolve(projectName+"_compile").toFile(), mavenCommand, "compile", "-fae");
//        runCommand(new File(path[0]), logDir.resolve(projectName+"_compile-test").toFile(), mavenCommand, "test-compile", "-fae");
        runCommand(new File(path[0]), logDir.resolve(projectName+"_compilation").toFile(),mavenCommand, "test", "-fae");


        for (int i = 1; i < path.length; i++) {

            try {
                injectCacheArtifact(path[i]);
                copyTarget(path[0], path[i]);
                FileUtils.copyDirectoryCompatibityMode(new File(path[0], ".cache"), new File(path[i], ".cache"));
                projectName = Paths.get(path[i]).getFileName();
//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile").toFile(),mavenCommand, "compile", "-fae");
//                runCommand(new File(path[i]),logDir.resolve(projectName+"_compile-test").toFile(),mavenCommand, "test-compile", "-fae");
                runCommand(new File(path[i]),logDir.resolve(projectName+"_compilation").toFile(),mavenCommand, "test", "-fae");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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

    public void injectCacheArtifact(String projectDir) {
        try {
            FileUtils.copyDirectoryCompatibityMode(
                    new File("src/main/resources/cache-artifacts/extensions.xml"),
                    new File(projectDir + File.separator + ".mvn" + File.separator + File.separator + "extensions.xml"));
            FileUtils.copyDirectoryCompatibityMode(
                    new File("src/main/resources/cache-artifacts/maven-build-cache-config.xml"),
                    new File(projectDir + File.separator + ".mvn" + File.separator + "maven-build-cache-config.xml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean copyTarget(String sourceDir, String targetDir) {
        File src = new File(sourceDir);
        File dst = new File(targetDir);

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


                        System.out.println("Copying target folder: " + targetDir + "->" + destDir);
                        try {
                            FileUtils.copyDirectoryCompatibityMode(dir.toFile(), destDir);
                            FileUtils.deleteDirectory(destDir.toPath().resolve("surefire-reports").toFile());
                        } catch (IOException e) {
                            e.printStackTrace();
                            System.err.println("Failed to copy " + targetDir + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

}
