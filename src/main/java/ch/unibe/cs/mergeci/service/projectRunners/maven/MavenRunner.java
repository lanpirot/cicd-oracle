package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.util.FileUtils;
import org.apache.maven.cli.MavenCli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class MavenRunner implements IRunner {
    public void run(Set<String> conflictList, String... path) {
        final String mavenCommand = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows") ? "mvn.cmd" : "mvn";
//        ProcessBuilder compileProcess = new ProcessBuilder(mavenCommand, "compile");
//        compileProcess.directory(new File(path[0]));
//        compileProcess.inheritIO();
        ProcessBuilder testProcess = new ProcessBuilder(mavenCommand, "test", "-fae");
        testProcess.inheritIO();
        testProcess.directory(new File(path[0]));

        System.out.println(new File(path[0]).getAbsolutePath());
        Process pr = null;

        injectCacheArtifact(path[0]);

        try {
            /*pr = compileProcess.start();
            FileUtils.printErrorMessage(pr);
            pr.waitFor();*/
            pr = testProcess.start();
            FileUtils.printErrorMessage(pr);
            pr.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }



        for (int i = 1; i < path.length; i++) {

//            compileProcess.directory(new File(path[i]));
            testProcess.directory(new File(path[i]));
            try {
                injectCacheArtifact(path[i]);

                copyTarget(path[0], path[i]);
                FileUtils.copyDirectoryCompatibityMode(new File(path[0],".cache"), new File(path[i],".cache"));
//                pr = compileProcess.start();
                FileUtils.printErrorMessage(pr);
                pr.waitFor();
                pr = testProcess.start();
                FileUtils.printErrorMessage(pr);

            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
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
        }catch (IOException e) {
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
