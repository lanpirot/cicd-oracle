package org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven;

import org.example.cicdmergeoracle.cicdMergeTool.model.Project;
import org.example.cicdmergeoracle.cicdMergeTool.model.ProjectClass;
import org.example.cicdmergeoracle.cicdMergeTool.model.patterns.IPattern;
import org.example.cicdmergeoracle.cicdMergeTool.util.MyFileUtils;
import org.example.cicdmergeoracle.cicdMergeTool.util.GitUtils;
import org.example.cicdmergeoracle.cicdMergeTool.util.ProjectBuilderUtils;
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
public class ConflictResolutionService {
    private final Path repositoryPath;
    private final Path tempDir;
    private final Path projectTempDir;
    private final MavenRunner mavenRunner;
    private final String projectName;
    private final Path logDir;
    private List<Map<String, List<String>>> conflictPatterns = new ArrayList<>();
    private boolean isVerbose = false;
    private final ResolutionResultDTO resolutionResultDTO;
    private final List<IPattern> resolutionPatterns;

    public ConflictResolutionService(File repoPath, Path tempDir, ResolutionResultDTO resolutionResultDTO, List<IPattern> resolutionPatterns) {
        this.repositoryPath = repoPath.toPath();
        this.tempDir = tempDir;
        this.mavenRunner = new MavenRunner(this.tempDir);
        this.projectName = repositoryPath.getFileName().toString();
        this.projectTempDir = this.tempDir.resolve("projects");
        this.logDir = this.tempDir.resolve("log");
        this.resolutionResultDTO = resolutionResultDTO;
        this.resolutionPatterns = resolutionPatterns;
    }

    public void buildProjects(String commit1, String commit2) throws Exception {
        MyFileUtils.deleteDirectory(tempDir.toFile());
        System.out.printf("TempDir %s%n", tempDir.toAbsolutePath().toString());
        Git git = GitUtils.getGit(repositoryPath.toFile());
        ResolveMerger merger = GitUtils.makeMerge(commit1, commit2, git);

        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getConflictChunks(merger);

        if (isVerbose) {
            System.out.println("conflicts :");
            mergeResultMap.keySet().forEach(System.out::println);
        }

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());

            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, resolutionPatterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(repositoryPath, projectTempDir);
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);

        projects.forEach(x -> conflictPatterns.add(x.extractPatterns()));
        for (int i = 0; i < projects.size(); i++) {
            ResolutionResultDTO.Variant variant = new ResolutionResultDTO.Variant();
            variant.setName(projects.get(i).getProjectPath().getFileName() + "_" + i);
            variant.setPath(projectTempDir.resolve(repositoryPath.getFileName().getFileName() + "_" + i));
            resolutionResultDTO.addVariant(variant);
            variant.setResolutionPatterns(conflictPatterns.get(i));
            variant.setStatus(ResolutionResultDTO.variantStatus.CREATING);
        }

        ObjectId branch1 = git.getRepository().resolve(commit1);
        ObjectId branch2 = git.getRepository().resolve(commit2);
//        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjectsFromCurrentMerge(git);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);

        for (ResolutionResultDTO.Variant variant : resolutionResultDTO.getVariants()) {
            variant.setStatus(ResolutionResultDTO.variantStatus.CREATED);
        }
    }

    public void runTests(boolean isUdeMavenDaemon, boolean isUseCacheOptimization) {
        MavenRunner mavenRunner = new MavenRunner(logDir, isUdeMavenDaemon);

        int numProjects = countProjects();
        List<Path> args = new ArrayList<>(countProjects());
        for (int i = 0; i < numProjects; i++) {
            String variantName = projectName + "_" + i;
            args.add(projectTempDir.resolve(variantName));

            ResolutionResultDTO.Variant variant = resolutionResultDTO.getVariantByName(variantName);
            variant.setStatus(ResolutionResultDTO.variantStatus.TESTING);
        }

        if (isUseCacheOptimization) {
            mavenRunner.runWithCacheMultithread(args.toArray(Path[]::new));
        } else {
            mavenRunner.runWithoutCacheMultithread(args.toArray(Path[]::new));
        }

        return;
    }

    public Map<String, CompilationResult> collectCompilationResults() throws IOException {
        Map<String, CompilationResult> statistics = new TreeMap<>();
        int numProjects = countProjects();
        for (int i = 0; i < numProjects; i++) {
            Path path = logDir.resolve(projectName + "_" + i + "_compilation");
            CompilationResult compResult = new CompilationResult(path.toFile());
            statistics.put(projectName + "_" + i, compResult);
        }
        return statistics;
    }

    public Map<String, TestTotal> collectTestResults() {
        Map<String, TestTotal> statistics = new TreeMap<>();
        int numProjects = countProjects();

        for (int i = 0; i < numProjects; i++) {
            Path path = projectTempDir.resolve(projectName + "_" + i);
            TestTotal testTotal = new TestTotal(path.toFile());
            statistics.put(projectName + "_" + i, testTotal);
        }
        return statistics;
    }

    public int countProjects() {
        return projectTempDir.toFile().list().length;
    }
}
