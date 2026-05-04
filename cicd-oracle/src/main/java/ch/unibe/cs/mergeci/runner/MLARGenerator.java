package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Variant generator backed by the ML-AR autoregressive model (RQ1).
 *
 * <p>On first use, spawns a Python subprocess running {@code predict_mlar.py} once and
 * streams JSON-line variant assignments from its stdout. Each line has the form:
 * {@code {"assignment":["OURS","THEIRS",...]}}
 *
 * <p>The subprocess is started lazily on the first {@link #nextVariant()} call and
 * is killed (if still running) when the generator is closed.
 *
 * <p>Stdout is drained by a background pump thread into a bounded queue; {@code nextVariant()}
 * polls with a {@link #STALL_TIMEOUT_SECONDS} timeout. On timeout we treat the generator as
 * exhausted (kill the subprocess, return empty) so the engine's deadline check can fire and
 * the variant loop can exit cleanly. Without this, an autoregressive sample that takes
 * minutes to produce would park the engine main thread in a blocking read forever — workers
 * drain, no new variants dispatch, the deadline never gets checked, and the run hangs past
 * the 300s budget (observed on guoguibing/librec, 4 chunks, after 268 variants).
 */
public class MLARGenerator implements IVariantGenerator, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_VARIANTS = 1000;
    private static final int STALL_TIMEOUT_SECONDS = 60;
    private static final String EOF_SENTINEL = "\0EOF\0";

    private final String mergeId;
    private final int numChunks;
    private final int maxVariants;

    private Process subprocess;
    private LinkedBlockingQueue<String> stdoutQueue;
    private Thread stdoutPump;
    private boolean exhausted = false;

    public MLARGenerator(String mergeId, int numChunks) {
        this(mergeId, numChunks, DEFAULT_VARIANTS);
    }

    public MLARGenerator(String mergeId, int numChunks, int maxVariants) {
        this.mergeId = mergeId;
        this.numChunks = numChunks;
        this.maxVariants = maxVariants;
    }

    @Override
    public Optional<List<String>> nextVariant() {
        if (exhausted) return Optional.empty();

        try {
            if (subprocess == null) {
                startSubprocess();
            }
            String line = stdoutQueue.poll(STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (line == null) {
                System.err.printf("MLARGenerator: predict_mlar.py produced no variant within %ds for merge_id=%s"
                        + " — treating generator as exhausted and killing the subprocess%n",
                        STALL_TIMEOUT_SECONDS, mergeId);
                exhausted = true;
                if (subprocess.isAlive()) subprocess.destroyForcibly();
                return Optional.empty();
            }
            if (EOF_SENTINEL.equals(line)) {
                exhausted = true;
                try {
                    int exitCode = subprocess.waitFor();
                    if (exitCode != 0) {
                        System.err.printf("MLARGenerator: predict_mlar.py exited with code %d for merge_id=%s"
                                + " — zero variants produced (see [predict_mlar] lines above for details)%n",
                                exitCode, mergeId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(line);
            JsonNode assignmentNode = node.get("assignment");
            if (assignmentNode == null || !assignmentNode.isArray()) {
                exhausted = true;
                return Optional.empty();
            }
            List<String> assignment = new ArrayList<>(assignmentNode.size());
            for (JsonNode pattern : assignmentNode) {
                assignment.add(pattern.asText());
            }
            return Optional.of(assignment);
        } catch (IOException e) {
            System.err.println("MLARGenerator: subprocess read error: " + e.getMessage());
            exhausted = true;
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exhausted = true;
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        if (subprocess != null && subprocess.isAlive()) {
            subprocess.destroyForcibly();
        }
    }

    private void startSubprocess() throws IOException {
        Path scriptPath = AppConfig.RQ1_SCRIPTS_DIR.resolve("predict_mlar.py");
        ProcessBuilder pb = new ProcessBuilder(
                AppConfig.PYTHON_EXECUTABLE, scriptPath.toString(),
                "--merge-id", mergeId,
                "--num-chunks", String.valueOf(numChunks),
                "--variants", String.valueOf(maxVariants),
                "--checkpoints-dir", AppConfig.RQ1_CHECKPOINTS_DIR.toString()
        );
        pb.redirectErrorStream(false);
        subprocess = pb.start();

        stdoutQueue = new LinkedBlockingQueue<>();
        stdoutPump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(subprocess.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    stdoutQueue.put(line);
                }
            } catch (IOException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try { stdoutQueue.put(EOF_SENTINEL); } catch (InterruptedException ignored) {}
            }
        }, "mlar-stdout-pump-" + mergeId);
        stdoutPump.setDaemon(true);
        stdoutPump.start();

        Thread stderrReader = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(subprocess.getErrorStream()))) {
                String line;
                while ((line = err.readLine()) != null) {
                    System.err.println("[predict_mlar] " + line);
                }
            } catch (IOException ignored) {}
        }, "mlar-stderr-pump-" + mergeId);
        stderrReader.setDaemon(true);
        stderrReader.start();
    }
}
