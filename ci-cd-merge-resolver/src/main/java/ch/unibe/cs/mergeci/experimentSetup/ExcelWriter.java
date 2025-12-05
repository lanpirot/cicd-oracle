package ch.unibe.cs.mergeci.experimentSetup;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class ExcelWriter {
    public static void writeExcel(String outputFile, List<DatasetRow> rows) throws IOException {

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

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            wb.write(fos);
        }
        wb.close();
    }

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
    ) {}
}
