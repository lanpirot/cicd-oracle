package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.Project;
import ch.unibe.cs.mergeci.model.ProjectClass;
import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class ProjectBuilderUtilsTest extends BaseTest {

    @Test
    void saveProjects() throws GitAPIException, IOException, InterruptedException {
        Git git = GitUtils.getGit(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        ResolveMerger merger = GitUtils.makeMerge("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61", "ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca", git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);

        System.out.println("conflicts :");
        mergeResultMap.keySet().forEach(System.out::println);

        assertFalse(mergeResultMap.isEmpty(), "Should have at least one conflicting file");

        // Build one variant manually: apply OURS to all conflict blocks
        Random random = new Random(42);
        List<ProjectClass> resolvedClasses = new ArrayList<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            ProjectClass pc = ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey());
            List<IMergeBlock> resolvedBlocks = new ArrayList<>();
            for (IMergeBlock block : pc.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    ConflictBlock clone = cb.clone();
                    clone.setPattern(PatternFactory.fromName("OURS"));
                    resolvedBlocks.add(clone);
                } else {
                    resolvedBlocks.add(block);
                }
            }
            ProjectClass resolvedPc = new ProjectClass();
            resolvedPc.setClassPath(pc.getClassPath());
            resolvedPc.setMergeBlocks(resolvedBlocks);
            resolvedClasses.add(resolvedPc);
        }

        Project project = new Project();
        project.setClasses(resolvedClasses);
        project.setProjectPath(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest));
        List<Project> projects = List.of(project);

        ObjectId branch1 = git.getRepository().resolve("26fcd8abe1e9a9ed95af8f4a9c853ae14cb50a61");
        ObjectId branch2 = git.getRepository().resolve("ed4809f3570ef0a9213ffdde4e4e04dfe3e334ca");
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);

        assertNotNull(nonConflictObjects, "Should get non-conflict objects");
        assertFalse(nonConflictObjects.isEmpty(), "Should have some non-conflict objects to save");

        ProjectBuilderUtils projectBuilderUtils = new ProjectBuilderUtils(AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest), AppConfig.TEST_TMP_DIR);
        projectBuilderUtils.saveProjects(projects, nonConflictObjects);

        Path tempDir = AppConfig.TEST_TMP_DIR.resolve(AppConfig.myTest + "_0");
        assertTrue(Files.exists(tempDir), "First project variant directory should be created");

        System.out.println("✓ Successfully saved " + projects.size() + " project variant(s) to " + tempDir);
    }
}
