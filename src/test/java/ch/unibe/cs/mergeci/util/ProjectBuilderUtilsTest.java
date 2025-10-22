package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import ch.unibe.cs.mergeci.model.patterns.TheirsPattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.ResolveMerger;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ProjectBuilderUtilsTest {

    @Test
    void getAllPossibleConflictResolution() throws IOException, GitAPIException {
        Git git = GitUtils.getGit("src/test/resources/test-merge-projects/myTest");
        ResolveMerger merger = GitUtils.makeMerge("","", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getConflictChunks(merger);
        Map.Entry<String, MergeResult<? extends Sequence>> entry = mergeResultMap.entrySet().iterator().next();
        ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
        List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
        List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
    }

    @Test
    void getProjects() throws IOException, GitAPIException {
        Git git = GitUtils.getGit("src/test/resources/test-merge-projects/myTest");
        ResolveMerger merger = GitUtils.makeMerge("","", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getConflictChunks(merger);

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils("src/test/resources/test-merge-projects/myTest","temp");
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);
    }

    @Test
    void saveProjects() throws GitAPIException, IOException, InterruptedException {
        FileUtils.deleteDirectory(new File("temp"));
        Git git = GitUtils.getGit("src/test/resources/test-merge-projects/jackson-databind");
        ResolveMerger merger = GitUtils.makeMerge("bf08f05406f90cd6a3e76e76687dfe45b22105d5","a36a049147c023becffbea2793042caef3ca3285", git);
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

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils("src/test/resources/test-merge-projects/jackson-databind","temp");
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);


        ObjectId branch1 = git.getRepository().resolve("bf08f05406f90cd6a3e76e76687dfe45b22105d5");
        ObjectId branch2 = git.getRepository().resolve("a36a049147c023becffbea2793042caef3ca3285");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);
    }
}