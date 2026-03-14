package ch.unibe.cs.mergeci.repoCollection;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class RepoCollectorTest extends BaseTest {

    @BeforeEach
    void createTestExcelIfMissing() throws Exception {
        if (!Files.exists(AppConfig.TEST_INPUT_PROJECT_XLSX)) {
            Files.createDirectories(AppConfig.TEST_INPUT_PROJECT_XLSX.getParent());

            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Projects");

                // Header row
                Row headerRow = sheet.createRow(0);
                headerRow.createCell(0).setCellValue("Repository");
                headerRow.createCell(1).setCellValue("URL");

                // Data row - use myTest
                Row dataRow = sheet.createRow(1);
                dataRow.createCell(0).setCellValue("test/myTest");
                String localRepoPath = AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest).toAbsolutePath().toString();
                dataRow.createCell(1).setCellValue("file://" + localRepoPath);

                try (FileOutputStream fos = new FileOutputStream(AppConfig.TEST_INPUT_PROJECT_XLSX.toFile())) {
                    workbook.write(fos);
                }
            }
        }
    }

    @Test
    void processExcel() throws Exception {
        // Use TEST_TMP_DIR/repos for cloning to ensure clean state between test runs
        RepoCollector repoCollector = new RepoCollector(
            AppConfig.TEST_TMP_DIR.resolve("repos"),
            AppConfig.TEST_TMP_DIR,
            AppConfig.TEST_DATASET_DIR
        );

        // Verify input file exists
        assertTrue(Files.exists(AppConfig.TEST_INPUT_PROJECT_XLSX),
            "Input Excel file should exist");

        repoCollector.processExcel(AppConfig.TEST_INPUT_PROJECT_XLSX);

        // Verify processing completed - dataset directory should exist and contain files
        assertTrue(Files.exists(AppConfig.TEST_DATASET_DIR),
            "Dataset directory should exist after processing");
    }
}
