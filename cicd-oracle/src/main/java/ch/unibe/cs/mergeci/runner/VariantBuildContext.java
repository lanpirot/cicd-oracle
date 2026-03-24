package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import lombok.Getter;
import org.eclipse.jgit.lib.ObjectId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Context for building variant project directories on demand.
 * Contains all metadata needed to create variant directories just-in-time,
 * and generates variant patterns lazily (one at a time) so that the timeout
 * — not a hard count — is the natural stopping condition.
 */
@Getter
public class VariantBuildContext {
    private final Path repositoryPath;
    private final Path projectTempDir;
    private final String projectName;
    private final Map<String, ObjectId> nonConflictObjects;
    private final Map<String, ObjectId> mergeCommitObjects;

    // Variant generation state
    private final String mergeCommit;
    /** Optional generator injected by callers (e.g. MLARGenerator, HeuristicGenerator). */
    private final IVariantGenerator generator;
    /** Legacy: pre-loaded ML-AR predictions (used when generator is null). */
    private final List<List<String>> mlPredictions;
    private int mlPredictionIndex = 0;
    private final Map<String, ConflictFile> conflictFileMap;
    private final int totalChunks;

    // Populated lazily as nextVariant() is called; shared reference so callers
    // that hold this list see new entries added during execution.
    private final List<Map<String, List<String>>> conflictPatterns;

    public VariantBuildContext(
            Path repositoryPath,
            Path projectTempDir,
            String projectName,
            String mergeCommit,
            Map<String, ConflictFile> conflictFileMap,
            int totalChunks,
            List<List<String>> mlPredictions,
            Map<String, ObjectId> nonConflictObjects,
            Map<String, ObjectId> mergeCommitObjects) {
        this(repositoryPath, projectTempDir, projectName, mergeCommit, conflictFileMap, totalChunks,
                mlPredictions, null, nonConflictObjects, mergeCommitObjects);
    }

    /** Constructor with explicit generator (takes precedence over mlPredictions). */
    public VariantBuildContext(
            Path repositoryPath,
            Path projectTempDir,
            String projectName,
            String mergeCommit,
            Map<String, ConflictFile> conflictFileMap,
            int totalChunks,
            List<List<String>> mlPredictions,
            IVariantGenerator generator,
            Map<String, ObjectId> nonConflictObjects,
            Map<String, ObjectId> mergeCommitObjects) {
        this.repositoryPath = repositoryPath;
        this.projectTempDir = projectTempDir;
        this.projectName = projectName;
        this.mergeCommit = mergeCommit;
        this.conflictFileMap = conflictFileMap;
        this.totalChunks = totalChunks;
        this.mlPredictions = mlPredictions;
        this.generator = generator;
        this.nonConflictObjects = nonConflictObjects;
        this.mergeCommitObjects = mergeCommitObjects;
        this.conflictPatterns = new ArrayList<>();
    }

    /**
     * Return the next variant, or empty when the generator is exhausted.
     * If an {@link IVariantGenerator} was injected, it is used; otherwise falls back to the
     * pre-loaded {@code mlPredictions} list.
     * Also appends the variant's conflict patterns to {@link #conflictPatterns}.
     */
    public Optional<VariantProject> nextVariant() {
        Optional<List<String>> assignmentOpt = (generator != null)
                ? generator.nextVariant()
                : nextFromMlPredictions();
        if (assignmentOpt.isEmpty()) return Optional.empty();
        VariantProject project = buildProjectFromAssignment(assignmentOpt.get());
        conflictPatterns.add(project.extractPatterns());
        return Optional.of(project);
    }

    private Optional<List<String>> nextFromMlPredictions() {
        if (mlPredictionIndex >= mlPredictions.size()) return Optional.empty();
        return Optional.of(mlPredictions.get(mlPredictionIndex++));
    }

    /**
     * Eagerly generate up to {@code maxCount} variants (or until predictions are exhausted).
     * Used by parallel runners that need all project directories upfront.
     */
    public List<VariantProject> collectAllVariants(int maxCount) {
        List<VariantProject> all = new ArrayList<>();
        Optional<VariantProject> next;
        while (all.size() < maxCount && (next = nextVariant()).isPresent()) {
            all.add(next.get());
        }
        return all;
    }

    private VariantProject buildProjectFromAssignment(List<String> assignment) {
        List<ConflictFile> resolvedClasses = new ArrayList<>();
        int chunkIndex = 0;

        for (Map.Entry<String, ConflictFile> entry : conflictFileMap.entrySet()) {
            ConflictFile cf = entry.getValue();
            List<IMergeBlock> resolvedBlocks = new ArrayList<>();

            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    ConflictBlock clone = cb.clone();
                    clone.setPattern(PatternFactory.fromName(assignment.get(chunkIndex++)));
                    resolvedBlocks.add(clone);
                } else {
                    resolvedBlocks.add(block);
                }
            }

            ConflictFile resolvedCf = new ConflictFile();
            resolvedCf.setClassPath(cf.getClassPath());
            resolvedCf.setMergeBlocks(resolvedBlocks);
            resolvedClasses.add(resolvedCf);
        }

        VariantProject project = new VariantProject();
        project.setProjectPath(repositoryPath);
        project.setClasses(resolvedClasses);
        return project;
    }
}
