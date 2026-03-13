package ch.unibe.cs.mergeci.model.patterns;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Loads and provides access to pattern heuristics from CSV file.
 * The CSV contains success rate statistics for different resolution patterns
 * based on the number of conflict chunks.
 */
public class PatternHeuristics {
    private final Map<Integer, List<PatternStrategy>> strategiesByChunkCount;
    private List<PatternStrategy> globalStrategies;

    public PatternHeuristics() {
        this.strategiesByChunkCount = new HashMap<>();
    }

    /**
     * Load pattern heuristics from CSV file.
     *
     * @param csvPath Path to CSV file in resources (e.g., "pattern-heuristics/relative_numbers_summary.csv")
     * @return Loaded PatternHeuristics
     * @throws IOException if file cannot be read
     */
    public static PatternHeuristics loadFromResource(String csvPath) throws IOException {
        PatternHeuristics heuristics = new PatternHeuristics();

        try (InputStream is = PatternHeuristics.class.getClassLoader().getResourceAsStream(csvPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            if (is == null) {
                throw new IOException("Resource not found: " + csvPath);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                parseLine(line, heuristics);
            }
        }

        return heuristics;
    }

    private static void parseLine(String line, PatternHeuristics heuristics) {
        // Format: "chunkCount,weight1*(pattern1)|weight2*(pattern2)|..."
        String[] parts = line.split(",", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CSV line format: " + line);
        }

        int chunkCount = Integer.parseInt(parts[0].trim());
        String strategiesStr = parts[1].trim();

        // Parse strategies separated by |
        String[] strategyTokens = strategiesStr.split("\\|");
        List<PatternStrategy> strategies = new ArrayList<>();

        for (String token : strategyTokens) {
            try {
                PatternStrategy strategy = PatternStrategy.parse(token.trim());
                strategies.add(strategy);
            } catch (Exception e) {
                System.err.println("Warning: Failed to parse strategy: " + token);
                System.err.println("  Error: " + e.getMessage());
            }
        }

        // Row 0 is global statistics
        if (chunkCount == 0) {
            heuristics.globalStrategies = strategies;
        } else {
            heuristics.strategiesByChunkCount.put(chunkCount, strategies);
        }
    }

    /**
     * Get strategies for a specific number of conflict chunks.
     * Falls back to global strategies if specific count not found.
     *
     * @param chunkCount Number of conflict chunks
     * @return List of strategies sorted by weight (descending)
     */
    public List<PatternStrategy> getStrategies(int chunkCount) {
        List<PatternStrategy> strategies = strategiesByChunkCount.get(chunkCount);

        if (strategies == null) {
            // Fallback to global distribution
            strategies = globalStrategies;
        }

        return strategies != null ? strategies : Collections.emptyList();
    }

    /**
     * Get global strategies (row 0 - aggregate across all chunk counts).
     */
    public List<PatternStrategy> getGlobalStrategies() {
        return globalStrategies != null ? globalStrategies : Collections.emptyList();
    }

    /**
     * Check if heuristics are loaded for a specific chunk count.
     */
    public boolean hasStrategiesFor(int chunkCount) {
        return strategiesByChunkCount.containsKey(chunkCount);
    }

    /**
     * Get all chunk counts that have specific heuristics.
     */
    public Set<Integer> getAvailableChunkCounts() {
        return strategiesByChunkCount.keySet();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PatternHeuristics:\n");
        sb.append("  Global strategies: ").append(globalStrategies != null ? globalStrategies.size() : 0).append("\n");
        sb.append("  Chunk-specific strategies: ").append(strategiesByChunkCount.size()).append(" counts\n");
        sb.append("  Available counts: ").append(getAvailableChunkCounts()).append("\n");
        return sb.toString();
    }
}
