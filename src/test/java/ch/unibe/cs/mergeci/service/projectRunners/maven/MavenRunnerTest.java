package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.model.patterns.OursPattern;
import ch.unibe.cs.mergeci.model.patterns.TheirsPattern;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtils;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MavenRunnerTest {

    @Test
    void run1() {
        MavenRunner mavenRunner = new MavenRunner();
        mavenRunner.run(Set.of(),"temp\\jitwatch_0", "temp\\jitwatch_1");
    }

    @Test
    void run() throws IOException, GitAPIException {
        FileUtils.deleteDirectory(new File("temp"));
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

        Git git = gitUtils.getGit();
        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects2(merger, branch1, branch2, gitUtils.getGit());
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);

        MavenRunner mavenRunner = new MavenRunner();
        mavenRunner.run(mergeResultMap.keySet(),"temp\\airlift_0", "temp\\airlift_1");
    }

    @Test
    void injectCacheArtifact() throws IOException {
        MavenRunner mavenRunner = new MavenRunner();
        mavenRunner.injectCacheArtifact("temp\\ripme_0");
    }

    @Test
    void copyTarget() {
        MavenRunner mavenRunner = new MavenRunner();
//        mavenRunner.copyTarget("temp\\Activiti_0", "temp\\Activiti_1");
        mavenRunner.copyTarget("temp\\Activiti_target", "temp\\Activiti_0");
    }
}