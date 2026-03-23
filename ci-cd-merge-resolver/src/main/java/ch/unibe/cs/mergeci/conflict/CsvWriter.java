package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import lombok.Builder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


public class CsvWriter {
    public static void writeCsv(Path outputFile, List<DatasetRow> rows) throws IOException {
        if (outputFile.getParent() != null) outputFile.getParent().toFile().mkdirs();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile.toFile()))) {
            // Header
            List<String> headers = new ArrayList<>();
            for (Utility.MERGECOLUMN c : Utility.MERGECOLUMN.values()) {
                headers.add(c.getColumnName());
            }
            writer.write(String.join(",", headers));
            writer.newLine();

            // Rows
            for (DatasetRow r : rows) {
                String[] fields = new String[Utility.MERGECOLUMN.values().length];
                fields[Utility.MERGECOLUMN.mergeCommit.getColumnNumber()] = r.mergeCommit();
                fields[Utility.MERGECOLUMN.parent1.getColumnNumber()] = r.parent1();
                fields[Utility.MERGECOLUMN.parent2.getColumnNumber()] = r.parent2();
                fields[Utility.MERGECOLUMN.numTests.getColumnNumber()] = String.valueOf(r.numTests());
                fields[Utility.MERGECOLUMN.numConflictingFiles.getColumnNumber()] = String.valueOf(r.numConflictingFiles());
                fields[Utility.MERGECOLUMN.numJavaFiles.getColumnNumber()] = String.valueOf(r.numJavaFiles());
                fields[Utility.MERGECOLUMN.compilationSuccess.getColumnNumber()] = String.valueOf(r.compilationSuccess());
                fields[Utility.MERGECOLUMN.testSuccess.getColumnNumber()] = String.valueOf(r.testSuccess());
                fields[Utility.MERGECOLUMN.elapsedTestTime.getColumnNumber()] = String.valueOf(r.elapsedTestTime());
                fields[Utility.MERGECOLUMN.isMultiModule.getColumnNumber()] = String.valueOf(r.isMultiModule());
                fields[Utility.MERGECOLUMN.numPassedTests.getColumnNumber()] = String.valueOf(r.numPassedTests());
                fields[Utility.MERGECOLUMN.compilationTime.getColumnNumber()] = String.valueOf(r.compilationTime());
                fields[Utility.MERGECOLUMN.testTime.getColumnNumber()] = String.valueOf(r.testTime());
                fields[Utility.MERGECOLUMN.normalizedElapsedTime.getColumnNumber()] = String.valueOf(r.normalizedElapsedTime());
                fields[Utility.MERGECOLUMN.numberOfModules.getColumnNumber()] = String.valueOf(r.numberOfModules());
                fields[Utility.MERGECOLUMN.modulesPassed.getColumnNumber()] = String.valueOf(r.modulesPassed());
                fields[Utility.MERGECOLUMN.hasTestConflict.getColumnNumber()] = String.valueOf(r.hasTestConflict());
                fields[Utility.MERGECOLUMN.baselineBroken.getColumnNumber()] = String.valueOf(r.baselineBroken());
                fields[Utility.MERGECOLUMN.mergeId.getColumnNumber()] = r.mergeId() != null ? r.mergeId() : "";
                writer.write(String.join(",", fields));
                writer.newLine();
            }
        }
    }

    public static void filterDatasets(File datasetsDir) throws IOException {
        File[] csvFiles = datasetsDir.listFiles(
                f -> f.isFile() && f.getName().endsWith(AppConfig.CSV)
        );
        if (csvFiles == null) return;

        for (File file : csvFiles) {
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    @Builder
    public record DatasetRow(
            String mergeCommit,
            String parent1,
            String parent2,
            int numTests,
            int numConflictingFiles,
            int numJavaFiles,
            boolean compilationSuccess,
            boolean testSuccess,
            float elapsedTestTime,
            boolean isMultiModule,
            int numPassedTests,
            float compilationTime,
            float testTime,
            float normalizedElapsedTime,
            int numberOfModules,
            int modulesPassed,
            boolean hasTestConflict,
            boolean baselineBroken,
            String mergeId
    ) {
    }
}
