package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.util.FileUtils;
import lombok.Builder;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFCell;


public class ExcelWriter {
    public static void writeExcel(File outputFile, List<DatasetRow> rows) throws IOException {

        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("dataset");

        String[] headers = {
                "mergeCommit", "parent1", "parent2",
                "numTests", "numConflictingFiles", "numJavaFiles",
                "compilationSuccess", "testSuccess", "elapsedTestTime", "isMultiModule"
        };

        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
        }

        int rowIndex = 1;
        for (DatasetRow r : rows) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(r.mergeCommit());
            row.createCell(1).setCellValue(r.parent1());
            row.createCell(2).setCellValue(r.parent2());
            row.createCell(3).setCellValue(r.numTests());
            row.createCell(4).setCellValue(r.numConflictingFiles());
            row.createCell(5).setCellValue(r.numJavaFiles());
            row.createCell(6).setCellValue(r.compilationSuccess());
            row.createCell(7).setCellValue(r.testSuccess());
            row.createCell(8).setCellValue(r.elapsedTestTime());
            row.createCell(9).setCellValue(r.isMultiModule());
        }

        if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
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
            boolean isMultiModule
    ) {
    }

    public static void filterDatasetsByConflictingFiles(File datasetsDir)
            throws IOException {

        File[] excelFiles = datasetsDir.listFiles(
                f -> f.isFile() && f.getName().endsWith(".xlsx")
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
                    if (numConflictingFiles > 6) {

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
