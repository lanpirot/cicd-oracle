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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MavenRunnerTest {

    @Test
    void run1() {
        MavenRunner mavenRunner = new MavenRunner();
//        mavenRunner.run(Set.of(),"temp\\jackson-databind_0", "temp\\jackson-databind_1");
        mavenRunner.run_cache_parallel(AppConfig.TMP_DIR.resolve(AppConfig.jacksonDatabind + "_0"),
                        AppConfig.TMP_DIR.resolve(AppConfig.jacksonDatabind + "_1"),
                        AppConfig.TMP_DIR.resolve(AppConfig.jacksonDatabind + "_2"),
                        AppConfig.TMP_DIR.resolve(AppConfig.jacksonDatabind + "_3"));
    }

    @Test
    void run() throws IOException, GitAPIException {
        FileUtils.deleteDirectory(AppConfig.TEST_TMP_DIR.toFile());
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ResolveMerger merger = GitUtils.makeMerge("master","feature", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);

        Map<String, List<ProjectClass>> mapClasses = new HashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass projectClass = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IPattern> patterns = List.of(new OursPattern(), new TheirsPattern());
            List<ProjectClass> projectClasses = ProjectBuilderUtils.getAllPossibleConflictResolution(projectClass, patterns);
            mapClasses.put(entry.getKey(), projectClasses);
        }

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest), AppConfig.TEST_TMP_DIR);
        List<Project> projects = projectBuilderUtils.getProjects(mapClasses);

        ObjectId branch1 = git.getRepository().resolve("master");
        ObjectId branch2 = git.getRepository().resolve("feature");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects2(merger, branch1, branch2, git);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);

        MavenRunner mavenRunner = new MavenRunner();
        //TODO: currently broken; needs some setup to create actual airlift_0, airlift_1 folders
        mavenRunner.run_cache_parallel(AppConfig.TMP_DIR.resolve(AppConfig.airlift+"_0"),
                        AppConfig.TMP_DIR.resolve(AppConfig.airlift+"_1"));
    }

    @Test
    void injectCacheArtifact() throws IOException {
        MavenRunner mavenRunner = new MavenRunner();
        mavenRunner.injectCacheArtifact(AppConfig.TMP_DIR.resolve(AppConfig.ripme+"_0"));
    }

    @Test
    void copyTarget() {
        MavenRunner mavenRunner = new MavenRunner();
        //TODO: currently broken, needs some setup to create actual _target or target folders, and an Activiti_0 folder
        mavenRunner.copyTarget(AppConfig.TMP_DIR.resolve(AppConfig.Activiti+"_target").toFile(), AppConfig.TMP_DIR.resolve(AppConfig.Activiti+"_0").toFile());
    }
}