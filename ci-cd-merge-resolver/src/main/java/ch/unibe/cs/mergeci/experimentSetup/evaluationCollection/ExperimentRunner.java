package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.service.MavenExecutionFactory;
import ch.unibe.cs.mergeci.service.MergeAnalyzer;
import ch.unibe.cs.mergeci.service.RunExecutionTIme;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.GitUtils;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ExperimentRunner {
    private final File datasetsDir;
    private final File repoDatasetsFile;
    private final File tempDir;

    public ExperimentRunner(File datasetsDir, File repoDatasetsFile, File tempDir) {
        this.datasetsDir = datasetsDir;
        this.repoDatasetsFile = repoDatasetsFile;
        this.tempDir = tempDir;
    }

    public void runTests(File outputDir) throws Exception {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File[] xlsxDataset = getFilesFromDir(datasetsDir);

        for (File dataset : xlsxDataset) {
            String nameOfOutputFIle = Files.getNameWithoutExtension(dataset.getName()) + ".json";
            if (Arrays.stream(outputDir.listFiles()).anyMatch(f -> f.getName().equals(nameOfOutputFIle))) {
                System.out.printf("File %s already exists. Skipping...\n", nameOfOutputFIle);
                continue;
            }
            String repoName = Files.getNameWithoutExtension(dataset.getName());

            File repoPath = tempDir.toPath().resolve(repoName).toFile();
            if (!repoPath.exists()) {
                GitUtils.cloneRepo(tempDir.toPath().resolve(repoName), getRepoUrl(dataset));
            }

            makeAnalysisByDataset(dataset, repoPath, new File(outputDir, nameOfOutputFIle));
            RepositoryCache.clear();
            WindowCache.reconfigure(new WindowCacheConfig());
            FileUtils.deleteDirectory(repoPath);
        }

    }

    private File[] getFilesFromDir(File dir) {
        return dir.listFiles();
    }

    private String getRepoUrl(File dataset) {

        try (FileInputStream file = new FileInputStream(repoDatasetsFile); Workbook workbook = new XSSFWorkbook(file);) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i < sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);

                String repoName = row.getCell(0).getStringCellValue().split("/")[1].trim();
                if (repoName.equals(Files.getNameWithoutExtension(dataset.getName()))) {
                    return row.getCell(1).getStringCellValue().trim();
                }

            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        return null;
    }

    public static void makeAnalysisByDataset(File dataset, File repoPath, File Output) throws Exception {
        List<MergeOutputJSON> merges = new ArrayList<>();


        try (FileInputStream file = new FileInputStream(dataset);
             Workbook workbook = new XSSFWorkbook(file);) {


            Sheet sheet = workbook.getSheetAt(0);
            int i = 0;
            for (Row row : sheet) {
                MergeOutputJSON mergeOutputJSON = new MergeOutputJSON();
                if (i++ == 0) continue;
                System.out.printf("Start processing %d/%d of %s \n", i-1, sheet.getLastRowNum(), dataset.getName());

                mergeOutputJSON.setMergeCommit(row.getCell(0).getStringCellValue());
                mergeOutputJSON.setParent1(row.getCell(1).getStringCellValue());
                mergeOutputJSON.setParent2(row.getCell(2).getStringCellValue());

//                mergeOutputJSON.getTestResults().setRunNum((int) row.getCell(3).getNumericCellValue());

                int numConflictChunks = countNumberOfConflictChunks(repoPath, mergeOutputJSON.getParent1(), mergeOutputJSON.getParent2());
                if (numConflictChunks > 6) {
                    System.out.printf("To many conflict chunks: %d > 6 \n", numConflictChunks);
                    continue;
                }

                mergeOutputJSON.setNumConflictChunks(numConflictChunks);
                mergeOutputJSON.setNumConflictFiles((int) row.getCell(4).getNumericCellValue());
                mergeOutputJSON.setNumJavaConflictFiles((int) row.getCell(5).getNumericCellValue());


//                mergeOutputJSON.getTestResults().setElapsedTime((float) row.getCell(8).getNumericCellValue());
                mergeOutputJSON.setIsMultiModule(row.getCell(9).getBooleanCellValue());

                ////////////RUN RESOLUTION////////////////////////

                String mergeHash = mergeOutputJSON.getMergeCommit();
                String parent1Hash = mergeOutputJSON.getParent1();
                String parent2Hash = mergeOutputJSON.getParent2();

                FileUtils.deleteDirectory(new File("temp"));
                Instant start = Instant.now();
                MergeAnalyzer mergeAnalyzer = new MergeAnalyzer(repoPath, "temp");
                mergeAnalyzer.buildProjects(parent1Hash, parent2Hash, mergeHash);
                RunExecutionTIme runExecutionTIme = mergeAnalyzer.runTests(new MavenExecutionFactory(mergeAnalyzer.getLogDir()).createMavenRunner());

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
        allMergesJSON.setMerges(merges);

        ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.writeValue(Output, allMergesJSON);
    }

    private static int countNumberOfConflictChunks(File repo, String parent1, String parent2) {
        try (Git git = GitUtils.getGit(repo);) {
            Map<String, Integer> map = GitUtils.countConflictChunks(parent1, parent2, git);
            return map.values().stream().mapToInt(Integer::intValue).sum();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
