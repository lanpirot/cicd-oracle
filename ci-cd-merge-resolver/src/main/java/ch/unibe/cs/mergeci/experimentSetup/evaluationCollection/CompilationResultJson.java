package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.service.MergeAnalyzer;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CompilationResultJson {
    public static void makeAnalysis(File dataset, String repoPath, File Output) throws Exception {
        List<MergeOutputJSON> merges = new ArrayList<>();
        MergeOutputJSON mergeOutputJSON = new MergeOutputJSON();

        try (FileInputStream file = new FileInputStream(dataset);
             Workbook workbook = new XSSFWorkbook(file);) {


            Sheet sheet = workbook.getSheetAt(0);
            int i = 0;//header skipper
            for (Row row : sheet) {
                if (i++ == 0) continue;

                mergeOutputJSON.setMergeCommit(row.getCell(0).getStringCellValue());
                mergeOutputJSON.setParent1(row.getCell(1).getStringCellValue());
                mergeOutputJSON.setParent2(row.getCell(2).getStringCellValue());

                mergeOutputJSON.getTestResults().setRunNum((int) row.getCell(3).getNumericCellValue());
                mergeOutputJSON.setNumConflictChunks((int) row.getCell(4).getNumericCellValue());
                mergeOutputJSON.setNumJavaConflicts((int) row.getCell(5).getNumericCellValue());


                mergeOutputJSON.getTestResults().setElapsedTime((float) row.getCell(8).getNumericCellValue());

                ////////////RUN RESOLUTION////////////////////////

                String mergeHash = mergeOutputJSON.getMergeCommit();
                String parent1Hash = mergeOutputJSON.getParent1();
                String parent2Hash = mergeOutputJSON.getParent2();

                Instant start = Instant.now();
                MergeAnalyzer mergeAnalyzer = new MergeAnalyzer(repoPath, "temp");
                mergeAnalyzer.buildProjects(parent1Hash, parent2Hash, mergeHash);
                mergeAnalyzer.runTests();
                Instant finish = Instant.now();
                long timeElapsed = Duration.between(start, finish).toSeconds();
                mergeOutputJSON.setVariantsExecutionTime(timeElapsed);

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

                    variants.add(variant);
                }

                mergeOutputJSON.setModulesResults(variants);
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
}
