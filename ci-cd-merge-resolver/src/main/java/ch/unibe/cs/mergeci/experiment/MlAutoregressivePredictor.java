package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Drives ML-AR inference for one cross-validation fold and exposes per-merge variant predictions.
 *
 * <p>If the prediction file for the requested fold does not yet exist, this class invokes
 * {@code train_autoregressive_model.py}: with {@code --inference-only} when a trained checkpoint
 * is already present, or with full training otherwise. This makes {@code ML_AUTOREGRESSIVE} a
 * first-class evaluation mode that requires no manual pre-computation step.
 */
public class MlAutoregressivePredictor {

    private static final String SCRIPT_NAME = "train_autoregressive_model.py";

    private final Map<String, List<List<String>>> predictions;

    private MlAutoregressivePredictor(Map<String, List<List<String>>> predictions) {
        this.predictions = predictions;
    }

    /**
     * Build a predictor for {@code fold}, running Python inference if the prediction file is
     * absent.
     *
     * @param scriptDir  directory that contains the Python scripts
     * @param fold       fold index (0–9)
     * @param variantCap maximum number of variants to generate per merge
     */
    public static MlAutoregressivePredictor forFold(Path scriptDir, int fold, int variantCap)
            throws IOException {
        Files.createDirectories(AppConfig.RQ1_PREDICTIONS_DIR);
        Path predFile = AppConfig.RQ1_PREDICTIONS_DIR.resolve("autoregressive_predictions_fold" + fold + ".csv");
        if (!Files.exists(predFile)) {
            runPythonInference(scriptDir, fold, variantCap);
        }
        if (!Files.exists(predFile)) {
            return new MlAutoregressivePredictor(Map.of());
        }
        return new MlAutoregressivePredictor(loadPredictions(predFile));
    }

    /** {@code true} if predictions were loaded successfully. */
    public boolean isAvailable() {
        return !predictions.isEmpty();
    }

    /** Number of merges for which predictions are available. */
    public int mergeCount() {
        return predictions.size();
    }

    /**
     * Return the ordered list of variant assignments predicted for {@code mergeId},
     * or an empty list if no predictions are available for that merge.
     */
    public List<List<String>> getPredictions(String mergeId) {
        return predictions.getOrDefault(mergeId, List.of());
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void runPythonInference(Path scriptDir, int fold, int variantCap) {
        Path script = scriptDir.resolve(SCRIPT_NAME);
        if (!Files.exists(script)) {
            System.err.println("ML script not found, skipping ML_AUTOREGRESSIVE for fold "
                    + fold + ": " + script);
            return;
        }

        String pythonExe = resolvePythonExecutable(scriptDir);

        try { Files.createDirectories(AppConfig.RQ1_CHECKPOINTS_DIR); } catch (Exception ignored) {}
        Path checkpoint = AppConfig.RQ1_CHECKPOINTS_DIR.resolve("autoregressive_model_fold" + fold + ".pt");
        List<String> cmd = new ArrayList<>(List.of(
                pythonExe, script.toAbsolutePath().toString(),
                "--folds",            String.valueOf(fold),
                "--data-dir",         AppConfig.RQ1_DIR.toAbsolutePath().toString(),
                "--cv-folds-dir",     AppConfig.RQ1_CV_FOLDS_DIR.toAbsolutePath().toString(),
                "--checkpoints-dir",  AppConfig.RQ1_CHECKPOINTS_DIR.toAbsolutePath().toString(),
                "--predictions-dir",  AppConfig.RQ1_PREDICTIONS_DIR.toAbsolutePath().toString(),
                "--variants",         String.valueOf(variantCap)
        ));
        if (Files.exists(checkpoint)) {
            cmd.add("--inference-only");
        }

        Path predFile = AppConfig.RQ1_PREDICTIONS_DIR.resolve("autoregressive_predictions_fold" + fold + ".csv");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        try {
            int exit = pb.start().waitFor();
            if (exit != 0) {
                System.err.println("ML inference exited with code " + exit + " for fold " + fold);
                try { Files.deleteIfExists(predFile); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("Failed to run ML inference for fold " + fold + ": " + e.getMessage());
            try { Files.deleteIfExists(predFile); } catch (Exception ignored) {}
        }
    }

    /**
     * Resolve the Python executable: prefer the project venv, fall back to {@code python3}.
     * The venv lives one level above the inner project root, so relative to
     * {@code src/main/resources/pattern-heuristics} it is four directories up then {@code .venv}.
     */
    private static String resolvePythonExecutable(Path scriptDir) {
        Path venv = scriptDir.resolve("../../../../../.venv/bin/python3").normalize();
        return Files.exists(venv) ? venv.toAbsolutePath().toString() : "python3";
    }

    /**
     * Load {@code autoregressive_predictions_fold{k}.csv}.
     * Format: {@code merge_id,sequence} where sequence is pipe-separated patterns.
     *
     * @return merge_id → ordered list of variant assignments (each a {@code List<String>})
     */
    static Map<String, List<List<String>>> loadPredictions(Path predFile) throws IOException {
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
}
