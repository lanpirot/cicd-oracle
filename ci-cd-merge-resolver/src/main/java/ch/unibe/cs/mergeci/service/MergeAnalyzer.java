package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import ch.unibe.cs.mergeci.model.patterns.TheirsPattern;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtils;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import lombok.Getter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.ResolveMerger;


import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Getter
public class MergeAnalyzer {
    private final Path repositoryPath;
    private final Path tempDir;
    private final Path projectTempDir;
    private final MavenRunner mavenRunner;
    private final String projectName;

    public MergeAnalyzer(String repoPath, String tempDir) {
        this.repositoryPath = Paths.get(repoPath);
        this.tempDir = Paths.get(tempDir);
        this.mavenRunner = new MavenRunner(this.tempDir);
        this.projectName = Paths.get(repoPath).getFileName().toString();
        this.projectTempDir = this.tempDir.resolve("projects");
    }

    public void buildProjects(String commit1, String commit2, String mergeCommit) throws Exception {
        FileUtils.deleteDirectory(tempDir.toFile());
        Git git = GitUtils.getGit(repositoryPath.toFile());
        ResolveMerger merger = GitUtils.makeMerge(commit1, commit2, git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getConflictChunks(merger);

        System.out.println("conflicts :");
        mergeResultMap.keySet().forEach(System.out::println);


        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(repositoryPath.toString(), projectTempDir.toString());
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);


        ObjectId branch1 = git.getRepository().resolve(commit1);
        ObjectId branch2 = git.getRepository().resolve(commit2);
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);


        ///////COPY MERGE COMMIT/////////
        Map<String, ObjectId> objectsFromMergeCommit = GitUtils.getObjectsFromCommit(mergeCommit, git);
        String nameOfProject = repositoryPath.getFileName().toString();
        FileUtils.saveFilesFromObjectId(projectTempDir.resolve(nameOfProject).toString(), objectsFromMergeCommit, git);
    }

    public void runTests(){
        MavenRunner mavenRunner = new MavenRunner(tempDir);

        int numProjects = countProjects();
        List<Path> args = new ArrayList<>(countProjects());
        args.add(projectTempDir.resolve(projectName));
        for (int i = 0; i < numProjects-1; i++) {
            args.add(projectTempDir.resolve(projectName + "_" + i));
        }
        mavenRunner.runWithCacheMultithread(args.toArray(new Path[0]));
    }

    public Map<String, CompilationResult> collectCompilationResults() throws IOException {
        Map<String, CompilationResult> statistics = new TreeMap<>();
        int numProjects = countProjects();
        CompilationResult compResult = new CompilationResult(mavenRunner.getLogDir().resolve(projectName+"_compilation").toFile());
        statistics.put(projectName, compResult);
        for (int i = 0; i < numProjects-1; i++) {
            Path path = mavenRunner.getLogDir().resolve(projectName + "_" + i+"_compilation");
            compResult = new CompilationResult(path.toFile());
            statistics.put(projectName + "_" + i, compResult);
        }
        return statistics;
    }

    public Map<String, TestTotal> collectTestResults() throws IOException {
        Map<String, TestTotal> statistics = new TreeMap<>();
        int numProjects = countProjects();
        TestTotal testTotal = new TestTotal(projectTempDir.resolve(projectName).toFile());
        statistics.put(projectName, testTotal);
        for (int i = 0; i < numProjects-1; i++) {
            Path path = projectTempDir.resolve(projectName + "_" + i);
            testTotal = new TestTotal(path.toFile());
            statistics.put(projectName + "_" + i, testTotal);
        }
        return statistics;
    }

    public int countProjects(){
        return projectTempDir.toFile().list().length;
    }
}
