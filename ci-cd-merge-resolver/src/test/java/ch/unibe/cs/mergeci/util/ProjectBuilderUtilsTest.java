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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectBuilderUtilsTest {

    @Test
    void getAllPossibleConflictResolution() throws IOException, GitAPIException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ResolveMerger merger = GitUtils.makeMerge("master","feature", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);
        Map.Entry<String, MergeResult<? extends Sequence>> entry = mergeResultMap.entrySet().iterator().next();
        ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
        List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
        List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
    }

    @Test
    void getProjects() throws IOException, GitAPIException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ResolveMerger merger = GitUtils.makeMerge("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61","ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);

        // Verify we have conflict chunks to work with
        assertFalse(mergeResultMap.isEmpty(), "Should have at least one conflicting file");

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            assertNotNull(projectClass, "ProjectClass should be created for each conflict");
            
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            assertFalse(projectClasses.isEmpty(), "Should generate at least one project class per pattern");
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest), AppConfig.TEST_TMP_DIR);
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);
        
        // Verify projects were generated
        assertFalse(projects.isEmpty(), "Should generate at least one project variant");
        
        // Verify each project has valid structure
        for (Project project : projects) {
            assertNotNull(project.getClasses(), "Project should have classes");
            assertFalse(project.getClasses().isEmpty(), "Project should have at least one class");
            
            for (ProjectClass projectClass : project.getClasses()) {
                assertNotNull(projectClass.getClassPath(), "ProjectClass should have a path");
                assertNotNull(projectClass.getMergeBlocks(), "ProjectClass should have merge blocks");
                assertFalse(projectClass.getMergeBlocks().isEmpty(), "ProjectClass should have at least one merge block");
            }
        }
        
        System.out.println("✓ Successfully generated " + projects.size() + " project variants");
    }

    @Test
    void saveProjects() throws GitAPIException, IOException, InterruptedException {
        FileUtils.deleteDirectory(AppConfig.TEST_TMP_DIR.toFile());
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ResolveMerger merger = GitUtils.makeMerge("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61","ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);

        System.out.println("conflicts :");
        mergeResultMap.keySet().forEach(System.out::println);


        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest), AppConfig.TEST_TMP_DIR);
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);


        ObjectId branch1 = git.getRepository().resolve("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61");
        ObjectId branch2 = git.getRepository().resolve("ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        
        // Verify we have non-conflict objects
        assertNotNull(nonConflictObjects, "Should get non-conflict objects");
        assertFalse(nonConflictObjects.isEmpty(), "Should have some non-conflict objects to save");
        
        // Verify projects exist before saving
        assertNotNull(projects, "Projects list should not be null");
        assertFalse(projects.isEmpty(), "Should have projects to save");
        
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);
        
        // Verify projects were saved by checking temp directory
        Path tempDir = AppConfig.TEST_TMP_DIR.resolve(AppConfig.myTest + "_0");
        assertTrue(Files.exists(tempDir), "First project variant directory should be created");
        
        System.out.println("✓ Successfully saved " + projects.size() + " project variants to " + tempDir);
    }
}