package org.example.demo.cicdMergeTool.service;

import org.example.demo.cicdMergeTool.model.Project;
import org.example.demo.cicdMergeTool.model.ProjectClass;
import org.example.demo.cicdMergeTool.model.patterns.IPattern;
import org.example.demo.cicdMergeTool.model.patterns.OursPattern;
import org.example.demo.cicdMergeTool.model.patterns.TheirsPattern;
import org.example.demo.cicdMergeTool.service.projectRunners.maven.CompilationResult;
import org.example.demo.cicdMergeTool.service.projectRunners.maven.MavenRunner;
import org.example.demo.cicdMergeTool.service.projectRunners.maven.TestTotal;
import org.example.demo.cicdMergeTool.util.MyFileUtils;
import org.example.demo.cicdMergeTool.util.GitUtils;
import org.example.demo.cicdMergeTool.util.ProjectBuilderUtils;
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
import java.nio.file.Paths;
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

    public MergeAnalyzer(File repoPath, String tempDir) {
        this.repositoryPath = repoPath.toPath();
        this.tempDir = Paths.get(tempDir);
        this.mavenRunner = new MavenRunner(this.tempDir);
        this.projectName = repositoryPath.getFileName().toString();
        this.projectTempDir = this.tempDir.resolve("projects");
        this.logDir = this.tempDir.resolve("log");
        conflictPatterns = new ArrayList<>();
    }

    public void buildProjects(String commit1, String commit2, String mergeCommit) throws Exception {
        MyFileUtils.deleteDirectory(tempDir.toFile());
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
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(repositoryPath, projectTempDir);
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);

        projects.forEach(x -> conflictPatterns.add(x.extractPatterns()));

        ObjectId branch1 = git.getRepository().resolve(commit1);
        ObjectId branch2 = git.getRepository().resolve(commit2);
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);


        ///////COPY MERGE COMMIT/////////
        Map<String, ObjectId> objectsFromMergeCommit = GitUtils.getObjectsFromCommit(mergeCommit, git);
        String nameOfProject = repositoryPath.getFileName().toString();
        MyFileUtils.saveFilesFromObjectId(projectTempDir.resolve(nameOfProject), objectsFromMergeCommit, git);
    }

    public RunExecutionTIme runTests(IRunner runner) {
        MavenRunner mavenRunner = new MavenRunner(logDir, false);

        int numProjects = countProjects();
        List<Path> args = new ArrayList<>(countProjects());
        args.add(projectTempDir.resolve(projectName));
        for (int i = 0; i < numProjects - 1; i++) {
            args.add(projectTempDir.resolve(projectName + "_" + i));
        }

        return runner.run(args.get(0), args.subList(1, args.size()), false);
    }

    public Map<String, CompilationResult> collectCompilationResults() throws IOException {
        Map<String, CompilationResult> statistics = new TreeMap<>();
        int numProjects = countProjects();
        CompilationResult compResult = new CompilationResult(logDir.resolve(projectName + "_compilation").toFile());
        statistics.put(projectName, compResult);
        for (int i = 0; i < numProjects - 1; i++) {
            Path path = logDir.resolve(projectName + "_" + i + "_compilation");
            compResult = new CompilationResult(path.toFile());
            statistics.put(projectName + "_" + i, compResult);
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
