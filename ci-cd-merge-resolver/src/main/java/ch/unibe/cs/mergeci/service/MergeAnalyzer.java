package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtils;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.ResolveMerger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Getter
@Setter
public class MergeAnalyzer {
    private final Path repositoryPath;
    private final Path tempDir;
    private final Path projectTempDir;
    private final MavenRunner mavenRunner;
    private final String projectName;
    private final Path logDir;
    private List<Map<String, List<String>>> conflictPatterns;
    private boolean isVerbose = false;

    public MergeAnalyzer(Path repoPath, Path tempDir, Path projectTempDir) {
        this.repositoryPath = repoPath;
        this.tempDir = tempDir;
        this.mavenRunner = new MavenRunner(this.tempDir);
        this.projectName = repositoryPath.toFile().getName();
        this.projectTempDir = projectTempDir;
        this.logDir = tempDir.resolve("log");
        conflictPatterns = new ArrayList<>();
    }

    /**
     * Prepare variant metadata without writing to disk.
     * Returns a context that can be used to build variants on demand.
     *
     * @param commit1 First parent commit
     * @param commit2 Second parent commit
     * @param mergeCommit The merge commit (human resolution)
     * @return VariantBuildContext containing all data needed to build variants
     */
    public VariantBuildContext prepareVariants(String commit1, String commit2, String mergeCommit) throws Exception {
        Git git = GitUtils.getGit(repositoryPath);
        ResolveMerger merger = GitUtils.makeMerge(commit1, commit2, git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);

        if (isVerbose) {
            System.out.println("conflicts :");
            mergeResultMap.keySet().forEach(System.out::println);
        }

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = AppConfig.patterns;
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(repositoryPath, projectTempDir);
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);

        List<Map<String, List<String>>> patterns = new ArrayList<>();
        projects.forEach(x -> patterns.add(x.extractPatterns()));

        ObjectId branch1 = git.getRepository().resolve(commit1);
        ObjectId branch2 = git.getRepository().resolve(commit2);
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);

        Map<String, ObjectId> mergeCommitObjects = GitUtils.getObjectsFromCommit(mergeCommit, git);

        return new VariantBuildContext(
                repositoryPath,
                projectTempDir,
                projectName,
                projects,
                nonConflictObjects,
                mergeCommitObjects,
                patterns
        );
    }

    /**
     * Build the main project directory (human-resolved merge).
     *
     * @param context Variant build context
     * @return Path to the main project directory
     */
    public Path buildMainProject(VariantBuildContext context) throws IOException {
        Path mainProjectPath = projectTempDir.resolve(projectName);
        Git git = GitUtils.getGit(repositoryPath);
        FileUtils.saveFilesFromObjectId(mainProjectPath, context.getMergeCommitObjects(), git);
        return mainProjectPath;
    }

    /**
     * Build a specific variant directory.
     *
     * @param context Variant build context
     * @param variantIndex Index of the variant to build (0-based)
     * @return Path to the variant directory
     */
    public Path buildVariant(VariantBuildContext context, int variantIndex) throws IOException {
        if (variantIndex < 0 || variantIndex >= context.getVariantCount()) {
            throw new IllegalArgumentException("Invalid variant index: " + variantIndex);
        }

        Path variantPath = projectTempDir.resolve(projectName + "_" + variantIndex);
        Project project = context.getVariant(variantIndex);

        Git git = GitUtils.getGit(repositoryPath);
        FileUtils.saveFilesFromObjectId(variantPath, context.getNonConflictObjects(), git);

        for (ProjectClass projectClass : project.getClasses()) {
            File filepath = variantPath.resolve(projectClass.getClassPath().toString()).toFile();
            if (filepath.getParentFile() != null) {
                filepath.getParentFile().mkdirs();
            }
            try (java.io.OutputStream out = new java.io.FileOutputStream(filepath)) {
                out.write(projectClass.toString().getBytes());
            }
        }

        return variantPath;
    }

    /**
     * Legacy method for backward compatibility.
     * Builds all projects at once (old behavior).
     */
    @Deprecated
    public void buildProjects(String commit1, String commit2, String mergeCommit) throws Exception {
        VariantBuildContext context = prepareVariants(commit1, commit2, mergeCommit);
        conflictPatterns = new ArrayList<>(context.getConflictPatterns());

        // Build main project
        buildMainProject(context);

        // Build all variants
        for (int i = 0; i < context.getVariantCount(); i++) {
            buildVariant(context, i);
        }
    }

    /**
     * Run tests with just-in-time directory creation and immediate cleanup.
     * Variant directories are built on-demand and deleted immediately after testing.
     *
     * @param context Variant build context
     * @param runner Just-in-time runner
     * @return Execution time statistics
     */
    public RunExecutionTIme runTestsJustInTime(VariantBuildContext context, IJustInTimeRunner runner) throws Exception {
        // Populate conflictPatterns for backward compatibility with result collectors
        this.conflictPatterns = new ArrayList<>(context.getConflictPatterns());
        return runner.run(context, this);
    }

    /**
     * Legacy method: Run tests on pre-existing directories.
     * Used when all directories are built upfront (old behavior).
     */
    public RunExecutionTIme runTests(IRunner runner) {
        MavenRunner mavenRunner = new MavenRunner(logDir);

        int numProjects = countProjects();
        List<Path> args = new ArrayList<>(numProjects);
        for (int i = 0; i < numProjects - 1; i++) {
            args.add(projectTempDir.resolve(projectName + "_" + i));
        }

        return runner.run(projectTempDir.resolve(projectName), args, false);
    }

    /**
     * Collect compilation result from a single project directory.
     *
     * @param projectKey Project key (e.g., "projectName" or "projectName_0")
     * @return CompilationResult or null if log doesn't exist
     */
    public CompilationResult collectCompilationResult(String projectKey) throws IOException {
        Path logPath = logDir.resolve(projectKey + "_compilation");
        if (logPath.toFile().exists()) {
            return new CompilationResult(logPath);
        }
        return null;
    }

    /**
     * Collect test result from a single project directory.
     *
     * @param projectKey Project key (e.g., "projectName" or "projectName_0")
     * @param projectPath Path to the project directory
     * @return TestTotal for the project
     */
    public TestTotal collectTestResult(String projectKey, Path projectPath) {
        return new TestTotal(projectPath.toFile());
    }

    public Map<String, CompilationResult> collectCompilationResults() throws IOException {
        Map<String, CompilationResult> statistics = new TreeMap<>();
        int numProjects = countProjects();

        Path mainLogPath = logDir.resolve(projectName + "_compilation");
        if (mainLogPath.toFile().exists()) {
            CompilationResult compResult = new CompilationResult(mainLogPath);
            statistics.put(projectName, compResult);
        }

        for (int i = 0; i < numProjects - 1; i++) {
            Path path = logDir.resolve(projectName + "_" + i + "_compilation");
            if (path.toFile().exists()) {
                CompilationResult compResult = new CompilationResult(path);
                statistics.put(projectName + "_" + i, compResult);
            }
        }
        return statistics;
    }

    public Map<String, TestTotal> collectTestResults() {
        Map<String, TestTotal> statistics = new TreeMap<>();
        int numProjects = countProjects();
        TestTotal testTotal = new TestTotal(projectTempDir.resolve(projectName).toFile());
        statistics.put(projectName, testTotal);
        for (int i = 0; i < numProjects - 1; i++) {
            Path path = projectTempDir.resolve(projectName + "_" + i);
            testTotal = new TestTotal(path.toFile());
            statistics.put(projectName + "_" + i, testTotal);
        }
        return statistics;
    }

    public int countProjects() {
        return projectTempDir.toFile().list().length;
    }
}
