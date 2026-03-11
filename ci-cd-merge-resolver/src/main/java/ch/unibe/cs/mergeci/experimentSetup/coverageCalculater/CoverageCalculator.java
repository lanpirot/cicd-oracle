package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.AllMergesJSON;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;
import ch.unibe.cs.mergeci.service.projectRunners.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.Utility;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static ch.unibe.cs.mergeci.util.Utility.*;

public class CoverageCalculator {
    private final File folder;
    private final Path repoDatasetsFile;
    private final Path tempDir;
    private final File cloneDir;



    public CoverageCalculator(File folder, File repoDatasetsFile, File tempDir, File cloneDir) {
        this.folder = folder;
        this.repoDatasetsFile = repoDatasetsFile.toPath();
        this.tempDir = tempDir.toPath();
        this.cloneDir = cloneDir;
    }

    public void calculateCoverage(File outputDir) {
        //TODO: currently unused and completely out of sync, e.g., clones repos, deletes directories etc.
        if (!outputDir.exists()) {outputDir.mkdirs();}
        ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        for (File file : folder.listFiles()) {
            String projectName = com.google.common.io.Files.getNameWithoutExtension(file.getName());
            System.out.printf("Start processing %s %n", file.getName());
            if (Arrays.stream(outputDir.listFiles()).anyMatch(x -> x.getName().equals(file.getName()))) {
                System.out.println("Skipping " + file.getName() + " because it already exists");
                continue;
            }

            AllMergesJSON allMergesJSON;
            try {
                allMergesJSON = objectMapper.readValue(file, AllMergesJSON.class);
                allMergesJSON.setProjectName(projectName);
                allMergesJSON.setRepoUrl(getRepoUrl(file.getName()));
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            MavenRunner maven = new MavenRunner(tempDir);

            Path projectRepoPath = new File(cloneDir, projectName).toPath();

            ExecutorService pool = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

            int index = 0;
            for (MergeOutputJSON merge : allMergesJSON.getMerges()) {
                if (merge.getCoverage() != null) continue;

                String newProjectName = allMergesJSON.getProjectName() + '_' + merge.getMergeCommit().substring(0, AppConfig.HASH_PREFIX_LENGTH);
                Path newProjectPath = tempDir.resolve(newProjectName);

                if (!projectRepoPath.toFile().exists()) {
                    GitUtils.cloneRepo(projectRepoPath, getRepoUrl(projectName));
                }

                try (Git git = GitUtils.getGit(projectRepoPath)) {
                    Map<String, ObjectId> objects = GitUtils.getObjectsFromCommit(merge.getMergeCommit(), git);
                    FileUtils.saveFilesFromObjectId(newProjectPath, objects, git);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                index++;
                int finalIndex = index;
                pool.submit(() -> {
                    try {
                        System.out.printf("Start processing %d/%d %n", finalIndex, allMergesJSON.getMerges().size());
                        maven.runWithCoverage(newProjectPath);
                        JacocoReportFinder.CoverageDTO coverage = JacocoReportFinder.getCoverageResults(newProjectPath, List.of());
                        merge.setCoverage(coverage);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            Utility.shutdownAndAwaitTermination(pool);

            try {
                objectMapper.writeValue(outputDir.toPath().resolve(file.getName()).toFile(), allMergesJSON);
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            RepositoryCache.clear();
            WindowCache.reconfigure(new WindowCacheConfig());
            FileUtils.deleteDirectory(tempDir.toFile());
            FileUtils.deleteDirectory(projectRepoPath.toFile());
        }
    }




    private String getRepoUrl(String projectName) {

        try (FileInputStream file = new FileInputStream(repoDatasetsFile.toFile()); Workbook workbook = new XSSFWorkbook(file);) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i < sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);

                String repoName = row.getCell(0).getStringCellValue().split("/")[1].trim();
                if (repoName.equals(com.google.common.io.Files.getNameWithoutExtension(projectName))) {
                    return row.getCell(1).getStringCellValue().trim();
                }

            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        return null;
    }






/*    private static List<String> getConflictJavaFiles(File repo, String parent1, String parent2) {
        try (Git git = GitUtils.getGit(repo);) {
            Map<String, Integer> map = GitUtils.countConflictChunks(parent1, parent2, git);
            List<String> files = map.keySet().stream().filter(x -> x.endsWith(AppConfig.JAVA)).map(x->);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }*/
}
