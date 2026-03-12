package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.MavenExecutionFactory;
import ch.unibe.cs.mergeci.service.MergeAnalyzer;
import ch.unibe.cs.mergeci.service.RunExecutionTIme;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.RepositoryStatus;
import ch.unibe.cs.mergeci.util.Utility;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ResolutionVariantRunner {
    private final Path datasetsDir;
    private final Path repoDatasetsFile;
    private final Path tempDir;
    private final RepositoryManager repoManager;

    public ResolutionVariantRunner(Path datasetsDir, Path repoDatasetsFile, Path tempDir) {
        this.datasetsDir = datasetsDir;
        this.repoDatasetsFile = repoDatasetsFile;
        this.tempDir = tempDir;
        this.repoManager = new RepositoryManager(tempDir);
    }

    public void runTests(Path outputDir, boolean isParallel, boolean isCache) throws Exception {
        // Handle FRESH_RUN mode - clean experiment output directory
        if (AppConfig.isFreshRun() && outputDir.toFile().exists()) {
            System.out.println("FRESH_RUN enabled: Cleaning experiment directory: " + outputDir);
            FileUtils.deleteDirectory(outputDir.toFile());
        }

        if (!outputDir.toFile().exists()) {
            outputDir.toFile().mkdirs();
        }

        File[] xlsxDataset = getFilesFromDir(datasetsDir);

        if (xlsxDataset == null) {return;}

        for (File dataset : xlsxDataset) {
            String repoUrl = getRepoUrl(dataset);
            String nameOfOutputFIle = Files.getNameWithoutExtension(dataset.getName()) + AppConfig.JSON;

            // Skip if already processed (unless FRESH_RUN, which already cleaned the directory)
            if (!AppConfig.isFreshRun() &&
                outputDir.toFile().listFiles() != null &&
                Arrays.stream(outputDir.toFile().listFiles()).anyMatch(f -> f.getName().equals(nameOfOutputFIle))) {
                System.out.printf("File %s already exists. Skipping...\n", nameOfOutputFIle);
                continue;
            }
            String repoName = Files.getNameWithoutExtension(dataset.getName());

            Path repoPath;
            try {
                repoPath = repoManager.getRepositoryPath(repoName, repoUrl);
            } catch (IOException e) {
                System.err.println("Skipping repository " + repoName + ": " + e.getMessage());
                continue;
            }

            try {
                makeAnalysisByDataset(dataset.toPath(), repoPath, outputDir.resolve(nameOfOutputFIle), isParallel, isCache);
                // Mark as successful only if analysis completes without major issues
                repoManager.markRepositorySuccess(repoName);
            } catch (Exception e) {
                System.err.println("Analysis failed for repository " + repoName + ": " + e.getMessage());
                // Don't mark as rejected - we might want to retry later
                throw e;
            } finally {
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
                // NOTE: No longer delete the repository directory!
            }
        }
    }

    private File[] getFilesFromDir(Path dir) {
        return dir.toFile().listFiles();
    }

    private String getRepoUrl(File dataset) {

        try (FileInputStream file = new FileInputStream(repoDatasetsFile.toFile()); Workbook workbook = new XSSFWorkbook(file);) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);

                String repoName = row.getCell(Utility.PROJECTCOLUMN.repoName.getColumnNumber()).getStringCellValue().split("/")[1].trim();
                if (repoName.equals(Files.getNameWithoutExtension(dataset.getName()))) {
                    return row.getCell(Utility.PROJECTCOLUMN.repoURL.getColumnNumber()).getStringCellValue().trim();
                }

            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        return null;
    }

    public static void makeAnalysisByDataset(Path dataset, Path repoPath, Path Output, boolean isParallel, boolean isCache) throws Exception {
        List<MergeOutputJSON> merges = new ArrayList<>();


        try (FileInputStream file = new FileInputStream(dataset.toFile());
             Workbook workbook = new XSSFWorkbook(file);) {


            Sheet sheet = workbook.getSheetAt(0);
            int i = 0;
            for (Row row : sheet) {
                MergeOutputJSON mergeOutputJSON = new MergeOutputJSON();
                if (i++ == 0) continue;
                System.out.printf("Start processing %d/%d of %s \n", i - 1, sheet.getLastRowNum(), dataset.getFileName().toString());

                mergeOutputJSON.setMergeCommit(row.getCell(Utility.MERGECOLUMN.mergeCommit.getColumnNumber()).getStringCellValue());
                mergeOutputJSON.setParent1(row.getCell(Utility.MERGECOLUMN.parent1.getColumnNumber()).getStringCellValue());
                mergeOutputJSON.setParent2(row.getCell(Utility.MERGECOLUMN.parent2.getColumnNumber()).getStringCellValue());

//                mergeOutputJSON.getTestResults().setRunNum((int) row.getCell(3).getNumericCellValue());

                int numConflictChunks = countNumberOfConflictChunks(repoPath, mergeOutputJSON.getParent1(), mergeOutputJSON.getParent2());
                if (numConflictChunks > AppConfig.MAX_CONFLICT_CHUNKS) {
                    System.out.printf("Too many conflict chunks: %d > %d \n", numConflictChunks, AppConfig.MAX_CONFLICT_CHUNKS);
                    continue;
                }

                mergeOutputJSON.setNumConflictChunks(numConflictChunks);
                mergeOutputJSON.setNumConflictFiles((int) row.getCell(Utility.MERGECOLUMN.numConflictingFiles.getColumnNumber()).getNumericCellValue());
                mergeOutputJSON.setNumJavaConflictFiles((int) row.getCell(Utility.MERGECOLUMN.numJavaFiles.getColumnNumber()).getNumericCellValue());


//                mergeOutputJSON.getTestResults().setElapsedTime((float) row.getCell(8).getNumericCellValue());
                mergeOutputJSON.setIsMultiModule(row.getCell(Utility.MERGECOLUMN.isMultiModule.getColumnNumber()).getBooleanCellValue());

                ////////////RUN RESOLUTION////////////////////////

                String mergeHash = mergeOutputJSON.getMergeCommit();
                String parent1Hash = mergeOutputJSON.getParent1();
                String parent2Hash = mergeOutputJSON.getParent2();

                FileUtils.deleteDirectory(AppConfig.TMP_PROJECT_DIR.toFile());
                Instant start = Instant.now();
                MergeAnalyzer mergeAnalyzer = new MergeAnalyzer(repoPath, AppConfig.TMP_DIR, AppConfig.TMP_PROJECT_DIR);
                mergeAnalyzer.buildProjects(parent1Hash, parent2Hash, mergeHash);
                RunExecutionTIme runExecutionTIme;

                runExecutionTIme = mergeAnalyzer.runTests(new MavenExecutionFactory(mergeAnalyzer.getLogDir()).createMavenRunner(isParallel, isCache));

                Instant finish = Instant.now();
                long timeElapsed = Duration.between(start, finish).toSeconds();
                mergeOutputJSON.setTotalExecutionTime(timeElapsed);

                System.out.println("Compilation result:");
                Map<String, CompilationResult> compilationResultMap = mergeAnalyzer.collectCompilationResults();
//                compilationResultMap.forEach((k, v) -> {
//                    System.out.println(k + ": " + v);
//                });


                System.out.println("\n\nTesting result:");
                Map<String, TestTotal> testTotalMap = mergeAnalyzer.collectTestResults();
                testTotalMap.forEach((k, v) -> {
                    System.out.println(k + ": " + v);
                });

                String projectName = mergeAnalyzer.getProjectName();
                mergeOutputJSON.setTestResults(testTotalMap.get(projectName));
                mergeOutputJSON.setCompilationResult(compilationResultMap.get(projectName));

                List<MergeOutputJSON.Variant> variants = new ArrayList<>(compilationResultMap.size());
                for (Map.Entry<String, CompilationResult> entry : compilationResultMap.entrySet()) {
                    if (entry.getKey().equals(projectName)) continue;
                    MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
                    variant.setVariantName(entry.getKey());
                    variant.setCompilationResult(entry.getValue());
                    variant.setTestResults(testTotalMap.get(entry.getKey()));

                    variant.setConflictPatterns(mergeAnalyzer.getConflictPatterns().get(variants.size()));

                    variants.add(variant);
                }

                MergeOutputJSON.VariantsExecution variantsExecution = new MergeOutputJSON.VariantsExecution(variants);
                variantsExecution.setExecutionTimeSeconds(runExecutionTIme.getVariantsExecutionTime().getSeconds());
                mergeOutputJSON.setVariantsExecution(variantsExecution);
                merges.add(mergeOutputJSON);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        AllMergesJSON allMergesJSON = new AllMergesJSON();
        allMergesJSON.setProjectName(Files.getNameWithoutExtension(dataset.getFileName().toString()));
        allMergesJSON.setMerges(merges);

        ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.writeValue(Output.toFile(), allMergesJSON);
    }

    private static int countNumberOfConflictChunks(Path repo, String parent1, String parent2) {
        try (Git git = GitUtils.getGit(repo);) {
            Map<String, Integer> map = GitUtils.countConflictChunks(parent1, parent2, git);
            return map.values().stream().mapToInt(Integer::intValue).sum();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
