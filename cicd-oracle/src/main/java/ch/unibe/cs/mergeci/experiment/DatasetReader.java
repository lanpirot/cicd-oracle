package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.util.Utility;
import lombok.Getter;
import lombok.Setter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads merge datasets from CSV files.
 * Encapsulates the logic for parsing CSV merge data into structured objects.
 */
public class DatasetReader {

    /**
     * Read merge information from a CSV dataset file.
     *
     * @param csvFile Path to the CSV file containing merge data
     * @return List of merge information objects
     * @throws IOException if the file cannot be read
     */
    public List<MergeInfo> readMergeDataset(Path csvFile) throws IOException {
        List<MergeInfo> merges = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile.toFile()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; } // skip header
                if (line.isBlank()) continue;

                String[] fields = parseCsvLine(line);

                MergeInfo info = new MergeInfo();
                info.setMergeCommit(fields[Utility.MERGECOLUMN.mergeCommit.getColumnNumber()]);
                info.setParent1(fields[Utility.MERGECOLUMN.parent1.getColumnNumber()]);
                info.setParent2(fields[Utility.MERGECOLUMN.parent2.getColumnNumber()]);
                info.setNumConflictFiles(Integer.parseInt(fields[Utility.MERGECOLUMN.numConflictingFiles.getColumnNumber()]));
                info.setNumJavaFiles(Integer.parseInt(fields[Utility.MERGECOLUMN.numJavaFiles.getColumnNumber()]));
                info.setMultiModule(Boolean.parseBoolean(fields[Utility.MERGECOLUMN.isMultiModule.getColumnNumber()]));
                info.setNormalizedElapsedTime(Float.parseFloat(fields[Utility.MERGECOLUMN.normalizedElapsedTime.getColumnNumber()]));
                info.setHasTestConflict(Boolean.parseBoolean(fields[Utility.MERGECOLUMN.hasTestConflict.getColumnNumber()]));
                // Defensive: older CSVs without this column default to false
                int brokenCol = Utility.MERGECOLUMN.baselineBroken.getColumnNumber();
                info.setBaselineBroken(fields.length > brokenCol && Boolean.parseBoolean(fields[brokenCol]));
                int mergeIdCol = Utility.MERGECOLUMN.mergeId.getColumnNumber();
                info.setMergeId(fields.length > mergeIdCol ? fields[mergeIdCol].trim() : null);

                merges.add(info);
            }
        }

        return merges;
    }

    private static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(field.toString());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        result.add(field.toString());
        return result.toArray(new String[0]);
    }

    /**
     * Data class holding merge information read from CSV.
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
        private boolean baselineBroken;
        private String mergeId;
        private String remoteUrl;    // only populated when read from Java_chunks.csv
        private String projectName;  // only populated when read from Java_chunks.csv

        /**
         * Get a short version of the merge commit hash (first 8 characters).
         */
        public String getShortCommit() {
            return mergeCommit.substring(0, Math.min(8, mergeCommit.length()));
        }
    }
}
