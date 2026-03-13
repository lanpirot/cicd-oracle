package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import lombok.Builder;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;


public class ExcelWriter {
    public static void writeExcel(Path outputFile, List<DatasetRow> rows) throws IOException {

        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("dataset");

        Row h = sheet.createRow(0);
        for (Utility.MERGECOLUMN c : Utility.MERGECOLUMN.values()){
            h.createCell(c.getColumnNumber()).setCellValue(c.getColumnName());
        }

        int rowIndex = 1;
        for (DatasetRow r : rows) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(Utility.MERGECOLUMN.mergeCommit.getColumnNumber()).setCellValue(r.mergeCommit());
            row.createCell(Utility.MERGECOLUMN.parent1.getColumnNumber()).setCellValue(r.parent1());
            row.createCell(Utility.MERGECOLUMN.parent2.getColumnNumber()).setCellValue(r.parent2());
            row.createCell(Utility.MERGECOLUMN.numTests.getColumnNumber()).setCellValue(r.numTests());
            row.createCell(Utility.MERGECOLUMN.numConflictingFiles.getColumnNumber()).setCellValue(r.numConflictingFiles());
            row.createCell(Utility.MERGECOLUMN.numJavaFiles.getColumnNumber()).setCellValue(r.numJavaFiles());
            row.createCell(Utility.MERGECOLUMN.compilationSuccess.getColumnNumber()).setCellValue(r.compilationSuccess());
            row.createCell(Utility.MERGECOLUMN.testSuccess.getColumnNumber()).setCellValue(r.testSuccess());
            row.createCell(Utility.MERGECOLUMN.elapsedTestTime.getColumnNumber()).setCellValue(r.elapsedTestTime());
            row.createCell(Utility.MERGECOLUMN.isMultiModule.getColumnNumber()).setCellValue(r.isMultiModule());
            row.createCell(Utility.MERGECOLUMN.numPassedTests.getColumnNumber()).setCellValue(r.numPassedTests());
            row.createCell(Utility.MERGECOLUMN.compilationTime.getColumnNumber()).setCellValue(r.compilationTime());
            row.createCell(Utility.MERGECOLUMN.testTime.getColumnNumber()).setCellValue(r.testTime());
            row.createCell(Utility.MERGECOLUMN.normalizedElapsedTime.getColumnNumber()).setCellValue(r.normalizedElapsedTime());
            row.createCell(Utility.MERGECOLUMN.numberOfModules.getColumnNumber()).setCellValue(r.numberOfModules());
            row.createCell(Utility.MERGECOLUMN.modulesPassed.getColumnNumber()).setCellValue(r.modulesPassed());
        }



        if (outputFile.getParent() != null) outputFile.getParent().toFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
            wb.write(fos);
        }
        wb.close();
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
            int modulesPassed
    ) {
    }

    public static void filterDatasetsByConflictingFiles(File datasetsDir)
            throws IOException {

        File[] excelFiles = datasetsDir.listFiles(
                f -> f.isFile() && f.getName().endsWith(AppConfig.XLSX)
        );

        if (excelFiles == null) return;

        for (File file : excelFiles) {
            Sheet sheet;
            try (Workbook wb = new XSSFWorkbook(file)) {
                sheet = wb.getSheetAt(0);
            } catch (InvalidFormatException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            Row header = sheet.getRow(0);

            try (Workbook newWb = new XSSFWorkbook()) {
                Sheet newSheet = newWb.createSheet();

                Row newHeader = newSheet.createRow(0);
                for (int i = 0; i < header.getLastCellNum(); i++) {
                    newHeader.createCell(i).setCellValue(
                            header.getCell(i).getStringCellValue()
                    );
                }

                int newRowIdx = 1;

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);

                    int numConflictingFiles = (int) row.getCell(4).getNumericCellValue();
                    if (numConflictingFiles > AppConfig.MAX_CONFLICT_CHUNKS) {
                        continue;
                    }

                    Row newRow = newSheet.createRow(newRowIdx++);
                    copyRow(row, newRow);
                }

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    newWb.write(fos);
                }
            }

        }
    }

    private static void copyRow(Row source, Row target) {
        for (int i = 0; i < source.getLastCellNum(); i++) {
            copyCell(source, target, i);
        }
    }

    private static void copyCell(Row sourceRow, Row targetRow, int cellIndex) {
        if (sourceRow.getCell(cellIndex) == null) return;

        switch (sourceRow.getCell(cellIndex).getCellType()) {
            case STRING -> targetRow.createCell(cellIndex)
                    .setCellValue(sourceRow.getCell(cellIndex).getStringCellValue());
            case NUMERIC -> targetRow.createCell(cellIndex)
                    .setCellValue(sourceRow.getCell(cellIndex).getNumericCellValue());
            case BOOLEAN -> targetRow.createCell(cellIndex)
                    .setCellValue(sourceRow.getCell(cellIndex).getBooleanCellValue());
            default -> targetRow.createCell(cellIndex)
                    .setCellValue(sourceRow.getCell(cellIndex).toString());
        }
    }
}
