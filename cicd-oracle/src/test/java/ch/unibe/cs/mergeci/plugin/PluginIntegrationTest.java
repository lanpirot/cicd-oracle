package ch.unibe.cs.mergeci.plugin;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import ch.unibe.cs.mergeci.runner.VariantProjectBuilder;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that creates a mock Maven repo with an active merge conflict,
 * then exercises the plugin's pipeline: in-core conflict analysis, variant generation,
 * variant materialization, and result collection — without touching the working tree.
 */
class PluginIntegrationTest extends BaseTest {

    @TempDir
    Path tempDir;

    private Path repoPath;
    private Git git;
    private String oursCommit;
    private String theirsCommit;

    @BeforeEach
    void createMockRepo() throws Exception {
        repoPath = tempDir.resolve("mockRepo");
        Files.createDirectories(repoPath);
        git = Git.init().setDirectory(repoPath.toFile()).call();
        git.getRepository().getConfig().setString("user", null, "email", "test@test.com");
        git.getRepository().getConfig().setString("user", null, "name", "Test");

        // Base commit
        writePom(repoPath);
        writeJava(repoPath, "Calculator.java",
                "package com.example;\n" +
                "public class Calculator {\n" +
                "    public int add(int a, int b) { return a + b; }\n" +
                "}\n");
        writeJava(repoPath, "CalculatorTest.java",
                "package com.example;\n" +
                "import org.junit.Test;\n" +
                "import static org.junit.Assert.*;\n" +
                "public class CalculatorTest {\n" +
                "    @Test public void testAdd() { assertEquals(5, new Calculator().add(2, 3)); }\n" +
                "}\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("initial").call();

        // Feature branch: add multiply
        git.checkout().setName("feature").setCreateBranch(true).call();
        writeJava(repoPath, "Calculator.java",
                "package com.example;\n" +
                "public class Calculator {\n" +
                "    public int add(int a, int b) { return a + b; }\n" +
                "    public int multiply(int a, int b) { return a * b; }\n" +
                "    public String describe() { return \"add, multiply\"; }\n" +
                "}\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("feature: multiply").call();
        theirsCommit = git.getRepository().resolve("HEAD").getName();

        // Main branch: add subtract (conflicts with feature)
        git.checkout().setName("main").call();
        writeJava(repoPath, "Calculator.java",
                "package com.example;\n" +
                "public class Calculator {\n" +
                "    public int add(int a, int b) { return a + b; }\n" +
                "    public int subtract(int a, int b) { return a - b; }\n" +
                "    public String describe() { return \"add, subtract\"; }\n" +
                "}\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("feature: subtract").call();
        oursCommit = git.getRepository().resolve("HEAD").getName();

        // Trigger merge conflict
        MergeResult mergeResult = git.merge()
                .include(git.getRepository().resolve("feature"))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .call();
        assertEquals(MergeResult.MergeStatus.CONFLICTING, mergeResult.getMergeStatus());
    }

    @Test
    void inCoreMergeDetectsConflicts() throws Exception {
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(git.getRepository(), true);
        merger.merge(git.getRepository().resolve(oursCommit),
                     git.getRepository().resolve(theirsCommit));

        Map<String, org.eclipse.jgit.merge.MergeResult<? extends Sequence>> results = merger.getMergeResults();
        assertFalse(results.isEmpty(), "Should detect conflicts");
        assertTrue(results.containsKey("src/main/java/com/example/Calculator.java"));
    }

    @Test
    void conflictFileMapBuiltCorrectly() throws Exception {
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(git.getRepository(), true);
        merger.merge(git.getRepository().resolve(oursCommit),
                     git.getRepository().resolve(theirsCommit));

        Map<String, ConflictFile> conflictFileMap = new LinkedHashMap<>();
        for (var entry : merger.getMergeResults().entrySet()) {
            conflictFileMap.put(entry.getKey(),
                    ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey()));
        }

        assertTrue(conflictFileMap.size() >= 1);
        ConflictFile cf = conflictFileMap.get("src/main/java/com/example/Calculator.java");
        assertNotNull(cf);

        int chunkCount = 0;
        for (IMergeBlock block : cf.getMergeBlocks()) {
            if (block instanceof ConflictBlock) chunkCount++;
        }
        assertTrue(chunkCount > 0, "Should have at least one conflict chunk");
    }

    @Test
    void nonConflictObjectsFromDirCache() throws Exception {
        Repository repo = git.getRepository();
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
        merger.merge(repo.resolve(oursCommit), repo.resolve(theirsCommit));

        Set<String> conflictPaths = merger.getMergeResults().keySet();
        Map<String, ObjectId> nonConflictObjects = new HashMap<>();
        DirCache dirCache = repo.readDirCache();
        try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.setRecursive(true);
            treeWalk.addTree(new DirCacheIterator(dirCache));
            while (treeWalk.next()) {
                if (!conflictPaths.contains(treeWalk.getPathString())) {
                    nonConflictObjects.put(treeWalk.getPathString(), treeWalk.getObjectId(0));
                }
            }
        }

        assertTrue(nonConflictObjects.containsKey("pom.xml"),
                "pom.xml should be a non-conflict object");
        assertFalse(nonConflictObjects.containsKey("src/main/java/com/example/Calculator.java"),
                "Calculator.java is conflicting, should not be in non-conflict objects");
    }

    @Test
    void variantBuildContextProducesVariants() throws Exception {
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(git.getRepository(), true);
        merger.merge(git.getRepository().resolve(oursCommit),
                     git.getRepository().resolve(theirsCommit));

        Map<String, ConflictFile> conflictFileMap = new LinkedHashMap<>();
        for (var entry : merger.getMergeResults().entrySet()) {
            conflictFileMap.put(entry.getKey(),
                    ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey()));
        }

        int totalChunks = 0;
        for (ConflictFile cf : conflictFileMap.values()) {
            for (IMergeBlock b : cf.getMergeBlocks()) {
                if (b instanceof ConflictBlock) totalChunks++;
            }
        }
        assertTrue(totalChunks > 0);

        // Simple generator: all OURS
        int finalTotalChunks = totalChunks;
        IVariantGenerator allOurs = () -> Optional.of(
                Collections.nCopies(finalTotalChunks, "OURS"));

        Map<String, ObjectId> nonConflict = getNonConflictFromDirCache(git, merger);

        VariantBuildContext context = new VariantBuildContext(
                repoPath, tempDir.resolve("projects"), "mockRepo", oursCommit,
                conflictFileMap, totalChunks, List.of(), allOurs,
                nonConflict, Map.of());

        Optional<VariantProject> variant = context.nextVariant();
        assertTrue(variant.isPresent());

        // Verify the variant's resolved files don't contain conflict markers
        for (ConflictFile cf : variant.get().getClasses()) {
            String content = cf.toString();
            assertFalse(content.contains("<<<<<<<"), "Should not contain conflict markers");
            assertFalse(content.contains(">>>>>>>"), "Should not contain conflict markers");
        }
    }

    @Test
    void variantMaterializesToDisk() throws Exception {
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(git.getRepository(), true);
        merger.merge(git.getRepository().resolve(oursCommit),
                     git.getRepository().resolve(theirsCommit));

        Map<String, ConflictFile> conflictFileMap = new LinkedHashMap<>();
        for (var entry : merger.getMergeResults().entrySet()) {
            conflictFileMap.put(entry.getKey(),
                    ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey()));
        }
        int totalChunks = 0;
        for (ConflictFile cf : conflictFileMap.values()) {
            for (IMergeBlock b : cf.getMergeBlocks()) {
                if (b instanceof ConflictBlock) totalChunks++;
            }
        }

        int finalTotalChunks = totalChunks;
        IVariantGenerator allTheirs = () -> Optional.of(
                Collections.nCopies(finalTotalChunks, "THEIRS"));

        Map<String, ObjectId> nonConflict = getNonConflictFromDirCache(git, merger);

        Path projectTempDir = tempDir.resolve("projects");
        VariantProjectBuilder builder = new VariantProjectBuilder(repoPath, tempDir, projectTempDir);
        VariantBuildContext context = new VariantBuildContext(
                repoPath, projectTempDir, "mockRepo", oursCommit,
                conflictFileMap, totalChunks, List.of(), allTheirs,
                nonConflict, Map.of());

        VariantProject variant = context.nextVariant().orElseThrow();
        Path variantPath = builder.buildVariant(context, variant, 1);

        // Verify the variant directory exists with key files
        assertTrue(Files.exists(variantPath.resolve("pom.xml")));
        assertTrue(Files.exists(variantPath.resolve("src/main/java/com/example/Calculator.java")));

        String content = Files.readString(variantPath.resolve("src/main/java/com/example/Calculator.java"));
        assertFalse(content.contains("<<<<<<<"));
        assertTrue(content.contains("multiply"), "THEIRS variant should contain multiply");
    }

    @Test
    void pinnedGeneratorOverridesChunks() throws Exception {
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(git.getRepository(), true);
        merger.merge(git.getRepository().resolve(oursCommit),
                     git.getRepository().resolve(theirsCommit));

        Map<String, ConflictFile> conflictFileMap = new LinkedHashMap<>();
        for (var entry : merger.getMergeResults().entrySet()) {
            conflictFileMap.put(entry.getKey(),
                    ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey()));
        }
        int totalChunks = 0;
        for (ConflictFile cf : conflictFileMap.values()) {
            for (IMergeBlock b : cf.getMergeBlocks()) {
                if (b instanceof ConflictBlock) totalChunks++;
            }
        }

        int finalTotalChunks = totalChunks;
        // Base generator: all OURS
        IVariantGenerator allOurs = () -> Optional.of(
                Collections.nCopies(finalTotalChunks, "OURS"));

        // Pin first chunk to THEIRS
        IVariantGenerator pinned = new PinnedChunkGeneratorTest.PinnedChunkGenerator(
                allOurs, Map.of(0, "THEIRS"));

        List<String> assignment = pinned.nextVariant().orElseThrow();
        assertEquals("THEIRS", assignment.get(0));
        for (int i = 1; i < assignment.size(); i++) {
            assertEquals("OURS", assignment.get(i));
        }
    }

    // --- helpers ---

    private Map<String, ObjectId> getNonConflictFromDirCache(Git git, ResolveMerger merger) throws Exception {
        Repository repo = git.getRepository();
        Set<String> conflictPaths = merger.getMergeResults().keySet();
        Map<String, ObjectId> result = new HashMap<>();
        DirCache dirCache = repo.readDirCache();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.setRecursive(true);
            tw.addTree(new DirCacheIterator(dirCache));
            while (tw.next()) {
                if (!conflictPaths.contains(tw.getPathString())) {
                    result.put(tw.getPathString(), tw.getObjectId(0));
                }
            }
        }
        return result;
    }

    private void writePom(Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>com.example</groupId>\n" +
                "    <artifactId>mock-merge</artifactId>\n" +
                "    <version>1.0-SNAPSHOT</version>\n" +
                "    <properties>\n" +
                "        <maven.compiler.source>17</maven.compiler.source>\n" +
                "        <maven.compiler.target>17</maven.compiler.target>\n" +
                "    </properties>\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>junit</groupId>\n" +
                "            <artifactId>junit</artifactId>\n" +
                "            <version>4.13.2</version>\n" +
                "            <scope>test</scope>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n" +
                "</project>\n");
    }

    private void writeJava(Path root, String fileName, String content) throws Exception {
        String subdir = fileName.endsWith("Test.java")
                ? "src/test/java/com/example"
                : "src/main/java/com/example";
        Path dir = root.resolve(subdir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), content);
    }
}
