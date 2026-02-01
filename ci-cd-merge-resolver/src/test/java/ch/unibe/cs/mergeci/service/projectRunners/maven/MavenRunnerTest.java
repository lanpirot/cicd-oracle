package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
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
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class MavenRunnerTest {

    @Test
    void run1() {
        MavenRunner mavenRunner = new MavenRunner();
//        mavenRunner.run(Set.of(),"temp\\jackson-databind_0", "temp\\jackson-databind_1");
        mavenRunner.run(Paths.get(AppConfig.TMP_DIR.getPath(), "/jackson-databind_0"),
                Paths.get(AppConfig.TMP_DIR.getPath(), "/jackson-databind_1"),
                Paths.get(AppConfig.TMP_DIR.getPath(), "/jackson-databind_2"),
                Paths.get(AppConfig.TMP_DIR.getPath(), "/jackson-databind_3"));
    }

    @Test
    void run() throws IOException, GitAPIException {
        FileUtils.deleteDirectory(AppConfig.TEST_TMP_DIR);
        Git git = GitUtils.getGit(new File(AppConfig.TEST_REPO_DIR,"myTest"));
        ResolveMerger merger = GitUtils.makeMerge("master","feature", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getConflictChunks(merger);

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(new File(AppConfig.TEST_REPO_DIR,"myTest").toPath(), AppConfig.TEST_TMP_DIR.toPath());
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);

        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);

        MavenRunner mavenRunner = new MavenRunner();
        mavenRunner.run(Paths.get(AppConfig.TMP_DIR.getPath(), "/airlift_0"), Paths.get(AppConfig.TMP_DIR.getPath(), "/airlift_1"));
    }

    @Test
    void injectCacheArtifact() throws IOException {
        MavenRunner mavenRunner = new MavenRunner();
        mavenRunner.injectCacheArtifact(Paths.get(AppConfig.TMP_DIR.getPath(), "/ripme_0"));
    }

    @Test
    void copyTarget() {
        MavenRunner mavenRunner = new MavenRunner();
//        mavenRunner.copyTarget("temp\\Activiti_0", "temp\\Activiti_1");
        mavenRunner.copyTarget(new File(AppConfig.TMP_DIR.getPath(), "/Activiti_target"), new File(AppConfig.TMP_DIR.getPath(), "/Activiti_0"));
    }
}