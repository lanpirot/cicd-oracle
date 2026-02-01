package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.config.AppConfig;
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
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ProjectBuilderUtilsTest {

    @Test
    void getAllPossibleConflictResolution() throws IOException, GitAPIException {
        Git git = GitUtils.getGit(new File(AppConfig.TEST_REPO_DIR, "myTest"));
        ResolveMerger merger = GitUtils.makeMerge("master","feature", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getConflictChunks(merger);
        Map.Entry<String, MergeResult<? extends Sequence>> entry = mergeResultMap.entrySet().iterator().next();
        ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
        List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
        List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
    }

    @Test
    void getProjects() throws IOException, GitAPIException {
        Git git = GitUtils.getGit(new File(AppConfig.TEST_REPO_DIR, "myTest"));
        ResolveMerger merger = GitUtils.makeMerge("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61","ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getConflictChunks(merger);

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(new File(AppConfig.TEST_REPO_DIR, "myTest").toPath(), AppConfig.TEST_TMP_DIR.toPath());
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);
    }

    @Test
    void saveProjects() throws GitAPIException, IOException, InterruptedException {
        FileUtils.deleteDirectory(AppConfig.TEST_TMP_DIR);
        Git git = GitUtils.getGit(new File(AppConfig.TEST_REPO_DIR, "myTest"));
        ResolveMerger merger = GitUtils.makeMerge("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61","ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca", git);
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

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(new File(AppConfig.TEST_REPO_DIR, "myTest").toPath(), AppConfig.TEST_TMP_DIR.toPath());
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);


        ObjectId branch1 = git.getRepository().resolve("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61");
        ObjectId branch2 = git.getRepository().resolve("ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);
    }
}