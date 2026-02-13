package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.Utility;
import ch.unibe.cs.mergeci.util.model.MergeInfo;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DatasetCollector {
    private final Path projectPath;
    private final Path tempPath;
    private final String projectName;
    private final int maxConflictMerges;

    public DatasetCollector(Path projectPath, Path tempPath, int maxConflictMerges) throws IOException {
        this.projectPath = projectPath;
        this.tempPath = tempPath;
        this.projectName = projectPath.toFile().getName();
        this.maxConflictMerges = maxConflictMerges;
    }

    public void collectDataset(Path excelOutFile) throws Exception {
        List<MergeInfo> merges = Collections.emptyList();

        try (Git git = GitUtils.getGit(projectPath)) {
            merges = GitUtils.getConflictCommits(maxConflictMerges, git);
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<ExcelWriter.DatasetRow> rows = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger counter = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

        FileUtils.deleteDirectory(tempPath.toFile());

        for (MergeInfo merge : merges) {


            if (!hasJavaConflicts(merge)) {
                System.out.println("Skip merge (no Java conflicts): " + merge.getResultedMergeCommit().getName());
                counter.incrementAndGet();
                continue;
            }

            List<MergeInfo> finalMerges = merges;

            pool.submit(() -> {
                int taskID =  counter.incrementAndGet();
                try {
                    System.out.printf("Processing merge: %s \t %d/%d \n", merge.getResultedMergeCommit(), taskID, finalMerges.size());
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
                System.out.printf("Processing merge: %s \t %d/%d FINISH\n", merge.getResultedMergeCommit(), taskID, finalMerges.size());
            });
        }
        Utility.shutdownAndAwaitTermination(pool);

        if (rows.isEmpty()) {
            System.out.printf("Project %s has no successful merges! \n", projectName);
            return;
        }
        ExcelWriter.writeExcel(excelOutFile, rows);
    }

    private void processMerge(MergeInfo merge, List<ExcelWriter.DatasetRow> rows) throws GitAPIException, IOException {
        String mergeCommit = merge.getResultedMergeCommit().getName();
        String p1 = merge.getCommit1().getName();
        String p2 = merge.getCommit2().getName();

        String newProjectName = projectName + "_" + mergeCommit.substring(0, AppConfig.HASH_PREFIX_LENGTH);
        Path newProjectPath = tempPath.resolve(newProjectName);

        //copy all files from old projectPath to newProjectPath
        try {
            FileUtils.copyDirectoryCompatibilityMode(projectPath.toFile(), newProjectPath.toFile());
        } catch(IOException e){
            System.err.println("Error copying folder: " + e.getMessage());
        }
        //then checkout the mergeCommit
        CheckoutCommand checkout = GitUtils.getGit(newProjectPath).checkout();
        checkout.setName(mergeCommit).setForced(true).call();

        int javaFiles = merge.getConflictingFiles().size();

        MavenRunner maven = new MavenRunner(tempPath);
        maven.run_no_optimization(newProjectPath);


        CompilationResult compilationResult = new CompilationResult(maven.getLogDir().resolve(newProjectName + "_compilation"));
        boolean compilationSuccess = compilationResult.getBuildStatus() == CompilationResult.Status.SUCCESS;

        TestTotal testTotal = new TestTotal(newProjectPath.toFile());
        int runTests = testTotal.getRunNum();
        float time = testTotal.getElapsedTime();

        if (runTests == 0) {
            System.out.println("No tests run");
            return; //there should be some tests in the repository, otherwise we skip
        };
        boolean testSuccess = runTests > 0;

        ExcelWriter.DatasetRow row = new ExcelWriter.DatasetRow(
                mergeCommit,
                p1, p2,
                runTests,
                merge.getConflictingFiles().size(),
                (int) merge.getConflictingFiles().keySet().stream()
                        .filter(f -> f.endsWith(AppConfig.JAVA)).count(),
                compilationSuccess,
                testSuccess,
                time,
                !compilationResult.getModuleResults().isEmpty()
        );

        ExcelWriter.DatasetRow row2 = ExcelWriter.DatasetRow.builder()
                .mergeCommit(mergeCommit)
                .parent1(p1)
                .parent2(p2)
                .numTests(runTests)
                .numConflictingFiles(merge.getConflictingFiles().size())
                .numJavaFiles((int) merge.getConflictingFiles().keySet().stream()
                        .filter(f -> f.endsWith(AppConfig.JAVA)).count())
                .compilationSuccess(compilationSuccess)
                .testSuccess(testSuccess)
                .elapsedTestTime(time)
                .isMultiModule(!compilationResult.getModuleResults().isEmpty())
                .build();

        rows.add(row);
    }


    private boolean hasJavaConflicts(MergeInfo merge) {
        return merge.getConflictingFiles()
                .keySet()
                .stream()
                .anyMatch(f -> f.endsWith(AppConfig.JAVA));
    }
}
