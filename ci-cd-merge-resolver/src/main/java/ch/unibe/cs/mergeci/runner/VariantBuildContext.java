package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.model.Project;
import lombok.Getter;
import org.eclipse.jgit.lib.ObjectId;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Context for building variant project directories on demand.
 * Contains all metadata needed to create variant directories just-in-time.
 */
@Getter
public class VariantBuildContext {
    private final Path repositoryPath;
    private final Path projectTempDir;
    private final String projectName;
    private final List<Project> projects;
    private final Map<String, ObjectId> nonConflictObjects;
    private final Map<String, ObjectId> mergeCommitObjects;
    private final List<Map<String, List<String>>> conflictPatterns;

    public VariantBuildContext(
            Path repositoryPath,
            Path projectTempDir,
            String projectName,
            List<Project> projects,
            Map<String, ObjectId> nonConflictObjects,
            Map<String, ObjectId> mergeCommitObjects,
            List<Map<String, List<String>>> conflictPatterns) {
        this.repositoryPath = repositoryPath;
        this.projectTempDir = projectTempDir;
        this.projectName = projectName;
        this.projects = projects;
        this.nonConflictObjects = nonConflictObjects;
        this.mergeCommitObjects = mergeCommitObjects;
        this.conflictPatterns = conflictPatterns;
    }

    /**
     * Get the number of variant projects (excluding main project).
     */
    public int getVariantCount() {
        return projects.size();
    }

    /**
     * Get a specific variant project by index.
     */
    public Project getVariant(int index) {
        if (index < 0 || index >= projects.size()) {
            throw new IndexOutOfBoundsException("Variant index out of bounds: " + index);
        }
        return projects.get(index);
    }
}
