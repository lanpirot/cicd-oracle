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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class MavenRunner implements IRunner {
    public void run(Set<String> conflictList, String... path) {
        final String mavenCommand = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows") ? "mvn.cmd" : "mvn";
        ProcessBuilder pb = new ProcessBuilder(mavenCommand, "compile");
        pb.directory(new File(path[0]));
        pb.inheritIO();
        System.out.println(new File(path[0]).getAbsolutePath());
        Process pr = null;
        try {
            pr = pb.start();
            pr.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        FileUtils.printErrorMessage(pr);

        for (int i = 1; i < path.length; i++) {
            File source = new File(path[0] + File.separator + "target");
            File destination = new File(path[i] + File.separator + "target");
            try {
                FileUtils.copyDirectoryCompatibityMode(source, destination);
                updateStatusMavenFile(path[i] + File.separator + "target" + File.separator + "maven-status" +
                                File.separator + "maven-compiler-plugin" + File.separator + "compile" +
                                File.separator + "default-compile" + File.separator + "inputFiles.lst",
                        path[0], path[i], conflictList);

                visitModules(path[i],path[0], path[i], conflictList);
            } catch (IOException e) {
                e.printStackTrace();
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
}
