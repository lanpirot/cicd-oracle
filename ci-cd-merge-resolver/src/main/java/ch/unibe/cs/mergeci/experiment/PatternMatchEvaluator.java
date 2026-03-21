package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import ch.unibe.cs.mergeci.model.patterns.PatternHeuristics;
import ch.unibe.cs.mergeci.model.patterns.PatternStrategy;
import ch.unibe.cs.mergeci.model.patterns.StrategySelector;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Cross-validation evaluator for pattern heuristics (RQ1).
 * <p>
 * For each fold k (0–9) evaluates four strategies on every held-out merge:
 * <ul>
 *   <li><b>HEURISTIC</b> — StrategySelector with per-chunk-count heuristics</li>
 *   <li><b>RANDOM</b>    — uniform i.i.d. draw from all 16 valid patterns per chunk</li>
 *   <li><b>GLOBAL_UNIFORM</b> — Phase 1: one uniform all-X variant per global-row entry
 *       (NON entry replaced by one GLOBAL i.i.d. draw); Phase 2: GLOBAL i.i.d.</li>
 *   <li><b>GLOBAL</b>    — each chunk drawn independently from the global row distribution
 *       (NON filtered out), no shuffle</li>
 * </ul>
 * Output: {@code cv_results.csv} in the same directory.
 */
public class PatternMatchEvaluator {

    /** All 16 valid single-chunk patterns (4 atomics + 6 ordered 2-combos + 6 ordered 3-combos). */
    static final List<String> ALL_16_PATTERNS = List.of(
        "OURS", "THEIRS", "BASE", "EMPTY",
        "OURSTHEIRS", "THEIRSOURS", "OURSBASE", "BASEOURS", "THEIRSBASE", "BASETHEIRS",
        "OURSTHEIRSBASE", "OURSBASETHEIRS", "THEIRSOURSBASE", "THEIRSBASEOURS",
        "BASEOURSTHEIRS", "BASETHEIRSOURS"
    );

    enum Mode { HEURISTIC, RANDOM, GLOBAL_UNIFORM, GLOBAL, ML_AUTOREGRESSIVE }

    record EvalRow(String mergeId, int numChunks, String[] groundTruth) {}

    record Result(int minDist, int rankOfClosest, int variantsGenerated) {}

    public static void main(String[] args) throws Exception {
        for (String name : ALL_16_PATTERNS) {
            PatternFactory.fromName(name);
        }
        System.out.println("All 16 patterns validated.");

        Path resourceDir = args.length > 0
            ? Path.of(args[0])
            : Path.of("src/main/resources/pattern-heuristics");

        int variantCap = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        System.out.println("Variant cap: " + variantCap);

        Path outputCsv        = resourceDir.resolve("cv_results.csv");
        Path trajectorycsv    = resourceDir.resolve("cv_trajectory.csv");

        try (PrintWriter writer     = new PrintWriter(new FileWriter(outputCsv.toFile()));
             PrintWriter trajWriter = new PrintWriter(new FileWriter(trajectorycsv.toFile()))) {
            writer.println("fold,merge_id,num_chunks,mode,min_distance,rank_of_closest,variants_generated");
            trajWriter.println("fold,merge_id,num_chunks,mode,attempt,distance");

            for (int fold = 0; fold < 10; fold++) {
                Path heuristicsFile = resourceDir.resolve(
                    "learnt_historical_pattern_distribution_train" + fold + ".csv");
                Path evalFile = resourceDir.resolve("evaluation_fold" + fold + ".csv");

                if (!Files.exists(heuristicsFile) || !Files.exists(evalFile)) {
                    System.err.println("Fold " + fold + " files not found, skipping.");
                    continue;
                }

                System.out.println("Processing fold " + fold + "...");
                PatternHeuristics heuristics = PatternHeuristics.loadFromFile(heuristicsFile);
                List<EvalRow> evalRows = loadEvalRows(evalFile);
                System.out.println("  " + evalRows.size() + " eval merges.");

                Path mlPredFile = resourceDir.resolve("autoregressive_predictions_fold" + fold + ".csv");
                Map<String, List<List<String>>> mlPredictions =
                    Files.exists(mlPredFile) ? loadMlPredictions(mlPredFile) : Map.of();
                if (!mlPredictions.isEmpty()) {
                    System.out.println("  Loaded ML predictions for " + mlPredictions.size() + " merges.");
                }

                for (EvalRow row : evalRows) {
                    for (Mode mode : Mode.values()) {
                        if (mode == Mode.ML_AUTOREGRESSIVE && mlPredictions.isEmpty()) continue;
                        Result result = evaluate(row, mode, heuristics, variantCap, fold, trajWriter,
                                                 mlPredictions);
                        writer.printf("%d,%s,%d,%s,%d,%d,%d%n",
                            fold, row.mergeId(), row.numChunks(), mode,
                            result.minDist(), result.rankOfClosest(), result.variantsGenerated());
                    }
                }
            }
        }
        System.out.println("Written: " + outputCsv);
        System.out.println("Written: " + trajectorycsv);
        generateLatexTables(resourceDir, outputCsv, variantCap);
        generateHammingProgressPlot(resourceDir, trajectorycsv, outputCsv);
    }

    static void generateHammingProgressPlot(Path resourceDir, Path trajPath, Path resultsPath) {
        Path script = resourceDir.resolve("plot_hamming_progress.py");
        ProcessBuilder pb = new ProcessBuilder(
            "python3", script.toAbsolutePath().toString(),
            trajPath.toAbsolutePath().toString(),
            resultsPath.toAbsolutePath().toString()
        );
        pb.inheritIO();
        try {
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                System.err.println("plot_hamming_progress.py exited with code " + exit);
            }
        } catch (Exception e) {
            System.err.println("Failed to run plot_hamming_progress.py: " + e.getMessage());
        }
    }

    static void generateLatexTables(Path resourceDir, Path csvPath, int variantCap) {
        Path script = resourceDir.resolve("generate_rq1_table.py");
        ProcessBuilder pb = new ProcessBuilder(
            "python3", script.toAbsolutePath().toString(),
            csvPath.toAbsolutePath().toString(),
            String.valueOf(variantCap)
        );
        pb.inheritIO();
        try {
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                System.err.println("generate_rq1_table.py exited with code " + exit);
            }
        } catch (Exception e) {
            System.err.println("Failed to run generate_rq1_table.py: " + e.getMessage());
        }
    }

    static List<EvalRow> loadEvalRows(Path evalFile) throws IOException {
        List<EvalRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(evalFile)) {
            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", 3);
                if (parts.length < 3) continue;
                String mergeId = parts[0].trim();
                int numChunks = Integer.parseInt(parts[1].trim());
                String[] groundTruth = parts[2].trim().split("\\|");
                rows.add(new EvalRow(mergeId, numChunks, groundTruth));
            }
        }
        return rows;
    }

    static Result evaluate(EvalRow row, Mode mode, PatternHeuristics heuristics, int variantCap,
                           int fold, PrintWriter trajWriter,
                           Map<String, List<List<String>>> mlPredictions) {
        int n = row.numChunks();
        String[] groundTruth = row.groundTruth();

        long maxPossible = 1;
        for (int i = 0; i < n && maxPossible <= variantCap; i++) maxPossible *= 16;
        int maxVariants = (int) Math.min(variantCap, maxPossible);

        int minDist = n;
        int rankOfClosest = -1;
        int variantsGenerated = 0;
        Random random = new Random(42L);

        // Record initial state: no attempts made yet, distance = num_chunks
        trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, 0, n);

        switch (mode) {

            case HEURISTIC -> {
                StrategySelector selector = new StrategySelector(heuristics, random);
                while (variantsGenerated < maxVariants && minDist > 0) {
                    if (selector.allStrategiesExhausted(n)) break;
                    PatternStrategy strategy = selector.selectStrategy(n);
                    if (strategy == null) break;
                    List<String> assignment = selector.generateAssignment(strategy, n);
                    if (assignment == null) { selector.recordOuterFailure(); continue; }
                    variantsGenerated++;
                    int dist = hammingDistance(assignment, groundTruth);
                    if (dist < minDist) {
                        minDist = dist; rankOfClosest = variantsGenerated;
                        trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, variantsGenerated, dist);
                    }
                }
            }

            case RANDOM -> {
                if (n <= 2) {
                    List<List<String>> all = enumerateAllCombinations(n);
                    Collections.shuffle(all, random);
                    for (List<String> assignment : all) {
                        if (variantsGenerated >= maxVariants || minDist == 0) break;
                        variantsGenerated++;
                        int dist = hammingDistance(assignment, groundTruth);
                        if (dist < minDist) {
                            minDist = dist; rankOfClosest = variantsGenerated;
                            trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, variantsGenerated, dist);
                        }
                    }
                } else {
                    Set<String> tried = new HashSet<>();
                    while (variantsGenerated < maxVariants && minDist > 0) {
                        List<String> assignment = null;
                        for (int retry = 0; retry < 200; retry++) {
                            List<String> c = randomVariant(n, random);
                            if (tried.add(String.join(",", c))) { assignment = c; break; }
                        }
                        if (assignment == null) break;
                        variantsGenerated++;
                        int dist = hammingDistance(assignment, groundTruth);
                        if (dist < minDist) {
                            minDist = dist; rankOfClosest = variantsGenerated;
                            trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, variantsGenerated, dist);
                        }
                    }
                }
            }

            case GLOBAL_UNIFORM -> {
                List<PatternStrategy> globalRow = heuristics.getGlobalStrategies();
                Set<String> tried = new HashSet<>();

                // Phase 1: one variant per global-row entry in frequency order.
                // NON entry → emit one GLOBAL i.i.d. draw at that position.
                for (PatternStrategy gs : globalRow) {
                    if (variantsGenerated >= maxVariants || minDist == 0) break;
                    List<String> assignment;
                    if (isNonStrategy(gs)) {
                        assignment = sampleGlobalIid(n, globalRow, random);
                    } else {
                        String pat = gs.getSubPatterns().getFirst().getPattern();
                        assignment = new ArrayList<>(Collections.nCopies(n, pat));
                    }
                    if (tried.add(String.join(",", assignment))) {
                        variantsGenerated++;
                        int dist = hammingDistance(assignment, groundTruth);
                        if (dist < minDist) {
                            minDist = dist; rankOfClosest = variantsGenerated;
                            trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, variantsGenerated, dist);
                        }
                    }
                }

                // Phase 2: fall back to GLOBAL i.i.d.
                while (variantsGenerated < maxVariants && minDist > 0) {
                    List<String> assignment = null;
                    for (int retry = 0; retry < 200; retry++) {
                        List<String> c = sampleGlobalIid(n, globalRow, random);
                        if (tried.add(String.join(",", c))) { assignment = c; break; }
                    }
                    if (assignment == null) break;
                    variantsGenerated++;
                    int dist = hammingDistance(assignment, groundTruth);
                    if (dist < minDist) {
                        minDist = dist; rankOfClosest = variantsGenerated;
                        trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, variantsGenerated, dist);
                    }
                }
            }

            case GLOBAL -> {
                List<PatternStrategy> globalRow = heuristics.getGlobalStrategies();
                Set<String> tried = new HashSet<>();
                while (variantsGenerated < maxVariants && minDist > 0) {
                    List<String> assignment = null;
                    for (int retry = 0; retry < 200; retry++) {
                        List<String> c = sampleGlobalIid(n, globalRow, random);
                        if (tried.add(String.join(",", c))) { assignment = c; break; }
                    }
                    if (assignment == null) break;
                    variantsGenerated++;
                    int dist = hammingDistance(assignment, groundTruth);
                    if (dist < minDist) {
                        minDist = dist; rankOfClosest = variantsGenerated;
                        trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, variantsGenerated, dist);
                    }
                }
            }

            case ML_AUTOREGRESSIVE -> {
                List<List<String>> preds = mlPredictions.getOrDefault(row.mergeId(), List.of());
                for (List<String> assignment : preds) {
                    if (variantsGenerated >= maxVariants || minDist == 0) break;
                    variantsGenerated++;
                    int dist = hammingDistance(assignment, groundTruth);
                    if (dist < minDist) {
                        minDist = dist; rankOfClosest = variantsGenerated;
                        trajWriter.printf("%d,%s,%d,%s,%d,%d%n", fold, row.mergeId(), n, mode, variantsGenerated, dist);
                    }
                }
            }
        }

        return new Result(minDist, rankOfClosest, variantsGenerated);
    }

    /**
     * Sample one assignment where each of the {@code n} chunks is drawn <em>independently</em>
     * from the global-row distribution (NON filtered out). No shuffle — positional independence
     * is achieved by independent draws.
     */
    static List<String> sampleGlobalIid(int n, List<PatternStrategy> globalRow, Random random) {
        List<PatternStrategy> nonNon = globalRow.stream()
            .filter(s -> !isNonStrategy(s))
            .toList();
        if (nonNon.isEmpty()) return new ArrayList<>(Collections.nCopies(n, "OURS"));

        double totalWeight = nonNon.stream().mapToDouble(PatternStrategy::getWeight).sum();
        List<String> assignment = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double r = random.nextDouble() * totalWeight;
            double cum = 0;
            String chosen = nonNon.getLast().getSubPatterns().getFirst().getPattern();
            for (PatternStrategy s : nonNon) {
                cum += s.getWeight();
                if (r < cum) { chosen = s.getSubPatterns().getFirst().getPattern(); break; }
            }
            assignment.add(PatternFactory.sampleOrdering(chosen, random));
        }
        return assignment;
    }

    /** True iff this strategy is 100% the NON pseudo-pattern. */
    static boolean isNonStrategy(PatternStrategy s) {
        return s.getSubPatterns().size() == 1
            && "NON".equals(s.getSubPatterns().getFirst().getPattern());
    }

    /**
     * Load autoregressive_predictions_fold{k}.csv.
     * Format: merge_id,sequence  where sequence is pipe-separated patterns.
     * Returns merge_id → ordered list of variant assignments (each a List&lt;String&gt;).
     */
    static Map<String, List<List<String>>> loadMlPredictions(Path predFile) throws IOException {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(predFile)) {
            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                int comma = line.indexOf(',');
                if (comma < 0) continue;
                String mergeId = line.substring(0, comma).trim();
                String seqPart = line.substring(comma + 1).trim();
                List<String> assignment = Arrays.asList(seqPart.split("\\|", -1));
                result.computeIfAbsent(mergeId, k -> new ArrayList<>()).add(assignment);
            }
        }
        return result;
    }

    static List<String> randomVariant(int numChunks, Random random) {
        List<String> a = new ArrayList<>(numChunks);
        for (int i = 0; i < numChunks; i++) a.add(ALL_16_PATTERNS.get(random.nextInt(16)));
        return a;
    }

    static List<List<String>> enumerateAllCombinations(int n) {
        List<List<String>> result = new ArrayList<>();
        enumerateHelper(n, new ArrayList<>(), result);
        return result;
    }

    private static void enumerateHelper(int remaining, List<String> current,
                                        List<List<String>> result) {
        if (remaining == 0) { result.add(new ArrayList<>(current)); return; }
        for (String p : ALL_16_PATTERNS) {
            current.add(p);
            enumerateHelper(remaining - 1, current, result);
            current.removeLast();
        }
    }

    static int hammingDistance(List<String> generated, String[] groundTruth) {
        int dist = 0;
        int len = Math.min(generated.size(), groundTruth.length);
        for (int i = 0; i < len; i++) if (!generated.get(i).equals(groundTruth[i])) dist++;
        dist += Math.abs(generated.size() - groundTruth.length);
        return dist;
    }
}
