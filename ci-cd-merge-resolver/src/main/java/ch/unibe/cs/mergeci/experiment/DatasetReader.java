package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.util.Utility;
import lombok.Getter;
import lombok.Setter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads merge datasets from Excel files.
 * Encapsulates the logic for parsing Excel merge data into structured objects.
 */
public class DatasetReader {

    /**
     * Read merge information from an Excel dataset file.
     *
     * @param excelFile Path to the Excel file containing merge data
     * @return List of merge information objects
     * @throws IOException if the file cannot be read
     */
    public List<MergeInfo> readMergeDataset(Path excelFile) throws IOException {
        List<MergeInfo> merges = new ArrayList<>();

        try (FileInputStream file = new FileInputStream(excelFile.toFile());
             Workbook workbook = new XSSFWorkbook(file)) {

            Sheet sheet = workbook.getSheetAt(0);
            int rowIndex = 0;

            for (Row row : sheet) {
                // Skip header row
                if (rowIndex++ == 0) {
                    continue;
                }

                MergeInfo info = new MergeInfo();
                info.setMergeCommit(row.getCell(Utility.MERGECOLUMN.mergeCommit.getColumnNumber()).getStringCellValue());
                info.setParent1(row.getCell(Utility.MERGECOLUMN.parent1.getColumnNumber()).getStringCellValue());
                info.setParent2(row.getCell(Utility.MERGECOLUMN.parent2.getColumnNumber()).getStringCellValue());
                info.setNumConflictFiles((int) row.getCell(Utility.MERGECOLUMN.numConflictingFiles.getColumnNumber()).getNumericCellValue());
                info.setNumJavaFiles((int) row.getCell(Utility.MERGECOLUMN.numJavaFiles.getColumnNumber()).getNumericCellValue());
                info.setMultiModule(row.getCell(Utility.MERGECOLUMN.isMultiModule.getColumnNumber()).getBooleanCellValue());
                info.setNormalizedElapsedTime((float) row.getCell(Utility.MERGECOLUMN.normalizedElapsedTime.getColumnNumber()).getNumericCellValue());
                info.setHasTestConflict(row.getCell(Utility.MERGECOLUMN.hasTestConflict.getColumnNumber()).getBooleanCellValue());

                merges.add(info);
            }
        }

        return merges;
    }

    /**
     * Data class holding merge information read from Excel.
     */
    @Getter
    @Setter
    public static class MergeInfo {
        private String mergeCommit;
        private String parent1;
        private String parent2;
        private int numConflictFiles;
        private int numJavaFiles;
        private boolean multiModule;
        private float normalizedElapsedTime;
        private boolean hasTestConflict;

        /**
         * Get a short version of the merge commit hash (first 8 characters).
         */
        public String getShortCommit() {
            return mergeCommit.substring(0, Math.min(8, mergeCommit.length()));
        }
    }
}
