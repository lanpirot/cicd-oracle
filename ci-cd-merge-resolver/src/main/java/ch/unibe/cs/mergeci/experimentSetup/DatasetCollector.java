package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.service.MergeAnalyzer;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.model.MergeInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DatasetCollector {
    private final Git git;
    private final String projectPath;
    private final String tempPath;
    private final Path tempDir;
    private final String projectName;
    private final int maxConflictMerges;

    public DatasetCollector(String projectPath, String tempPath, int maxConflictMerges) throws IOException {
        this.git = GitUtils.getGit(projectPath);
        this.projectPath = projectPath;
        this.tempPath = tempPath;
        this.tempDir = Paths.get(tempPath);
        this.projectName = Paths.get(projectPath).getFileName().toString();
        this.maxConflictMerges = maxConflictMerges;
    }

    public void collectDataset(String excelOut) throws Exception {

        List<MergeInfo> merges = GitUtils.getConflictCommits(maxConflictMerges, git);
        List<ExcelWriter.DatasetRow> rows = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger counter = new AtomicInteger(0);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(24);

        FileUtils.deleteDirectory(new File(tempPath));

        for (MergeInfo merge : merges) {


            if (!hasJavaConflicts(merge)) {
                System.out.println("Skip merge (no Java conflicts): " + merge.getResultedMergeCommit().getName());
                counter.incrementAndGet();
                continue;
            }

            pool.submit(() -> {
                try {
                    System.out.printf("Processing merge: %s \t %d/%d \n", merge.getResultedMergeCommit(), counter.incrementAndGet(), merges.size());
                    processMerge(merge, rows);
                } catch (GitAPIException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
        ExcelWriter.writeExcel(excelOut, rows);
    }

    private void processMerge(MergeInfo merge, List<ExcelWriter.DatasetRow> rows) throws GitAPIException, IOException {
        String mergeCommit = merge.getResultedMergeCommit().getName();
        String p1 = merge.getCommit1().getName();
        String p2 = merge.getCommit2().getName();


        Map<String, ObjectId> objects = GitUtils.getObjectsFromCommit(mergeCommit, git);

        String newProjectName = projectName + "_" + mergeCommit.substring(0, 8);
        String newProjectPath = tempDir.resolve(newProjectName).toString();

        FileUtils.saveFilesFromObjectId(newProjectPath, objects, git);

        int javaFiles = merge.getConflictingFiles().size();

        MavenRunner maven = new MavenRunner(tempDir);
        maven.runWithoutCache(newProjectPath);


        CompilationResult compilationResult = new CompilationResult(maven.getLogDir().resolve(newProjectName + "_compilation").toFile());
        boolean compilationSuccess = compilationResult.getBuildStatus() == CompilationResult.Status.SUCCESS;

        TestTotal testTotal = new TestTotal(new File(newProjectPath));
        int runTests = testTotal.getRunNum();
        float time = testTotal.getElapsedTime();

        boolean testSuccess = runTests > 0;

        ExcelWriter.DatasetRow row = new ExcelWriter.DatasetRow(
                mergeCommit,
                p1, p2,
                runTests,
                merge.getConflictingFiles().size(),
                (int) merge.getConflictingFiles().keySet().stream()
                        .filter(f -> f.endsWith(".java")).count(),
                compilationSuccess,
                testSuccess,
                time,
                !compilationResult.getModuleResults().isEmpty()
        );

        rows.add(row);
    }


    private boolean hasJavaConflicts(MergeInfo merge) {
        return merge.getConflictingFiles()
                .keySet()
                .stream()
                .anyMatch(f -> f.endsWith(".java"));
    }
}
