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
        GitUtils gitUtils = new GitUtils(new File("src/test/resources/test-merge-projects/myTest"));
        ResolveMerger merger = gitUtils.makeMerge("","");
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = gitUtils.getConflictChunks(merger);
        Map.Entry<String, MergeResult<? extends Sequence>> entry = mergeResultMap.entrySet().iterator().next();
        ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
        List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
        List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
    }

    @Test
    void getProjects() throws IOException, GitAPIException {
        GitUtils gitUtils = new GitUtils(new File("src/test/resources/test-merge-projects/myTest"));
        ResolveMerger merger = gitUtils.makeMerge("","");
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = gitUtils.getConflictChunks(merger);

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils("src/test/resources/test-merge-projects/myTest");
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);
    }

    @Test
    void saveProjects() throws GitAPIException, IOException, InterruptedException {
         FileUtils.deleteDirectory(new File("temp"));
        GitUtils gitUtils = new GitUtils(new File("src/test/resources/test-merge-projects/jitwatch"));
        ResolveMerger merger = gitUtils.makeMerge("13712a9a18f116b52d7a866f9c9b86c9c4bc5421","ba9368ad7bf677457ed110b000fe3716973bf200");
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = gitUtils.getConflictChunks(merger);

        System.out.println("conflicts :");
        mergeResultMap.keySet().forEach(System.out::println);


        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils("src/test/resources/test-merge-projects/jitwatch");
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);

        Git git = gitUtils.getGit();
        ObjectId branch1 = git.getRepository().resolve("13712a9a18f116b52d7a866f9c9b86c9c4bc5421");
        ObjectId branch2 = git.getRepository().resolve("ba9368ad7bf677457ed110b000fe3716973bf200");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(gitUtils.getGit(), branch1, branch2);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);
    }
}