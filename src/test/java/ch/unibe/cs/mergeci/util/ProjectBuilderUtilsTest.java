package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import ch.unibe.cs.mergeci.model.patterns.TheirsPattern;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.merge.MergeResult;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ProjectBuilderUtilsTest {

    @Test
    void getAllPossibleConflictResolution() throws IOException, GitAPIException {
        GitUtils gitUtils = new GitUtils(new File("src/test/resources/test-merge-projects/myTest"));
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = gitUtils.getConflictChunks("", "");
        Map.Entry<String, MergeResult<? extends Sequence>> entry = mergeResultMap.entrySet().iterator().next();
        ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
        List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
        List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
    }

    @Test
    void getProjects() throws IOException, GitAPIException {
        GitUtils gitUtils = new GitUtils(new File("src/test/resources/test-merge-projects/myTest"));
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = gitUtils.getConflictChunks("", "");

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
    void saveProjects() throws GitAPIException, IOException {
        GitUtils gitUtils = new GitUtils(new File("src/test/resources/test-merge-projects/myTest"));
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = gitUtils.getConflictChunks("", "");

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils("src/test/resources/test-merge-projects/myTest");
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);
        projectBuilderUtils.saveProjects(projects);
    }
}