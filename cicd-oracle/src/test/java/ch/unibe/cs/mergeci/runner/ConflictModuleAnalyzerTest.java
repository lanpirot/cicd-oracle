package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.runner.ConflictModuleAnalyzer.AffectedModules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConflictModuleAnalyzerTest extends BaseTest {

    @Test
    void noRootPom_returnsAll(@TempDir Path root) {
        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("src/main/java/Foo.java"));
        assertTrue(am.isAll());
    }

    @Test
    void conflictInRootPom_returnsAll(@TempDir Path root) throws IOException {
        writePom(root, "g", "p", null, List.of("a"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("pom.xml"));
        assertTrue(am.isAll());
    }

    @Test
    void conflictInModulePom_returnsAll(@TempDir Path root) throws IOException {
        writePom(root, "g", "p", null, List.of("a"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("a/pom.xml"));
        assertTrue(am.isAll());
    }

    @Test
    void conflictOutsideAnyModule_returnsAll(@TempDir Path root) throws IOException {
        writePom(root, "g", "p", null, List.of("a"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/readme.md"), "x");
        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("docs/readme.md"));
        assertTrue(am.isAll());
    }

    @Test
    void singleModule_noDownstream(@TempDir Path root) throws IOException {
        // 3 leaf modules a, b, c — no edges between them
        writePom(root, "g", "p", null, List.of("a", "b", "c"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        writePom(root.resolve("b"), "g", "b", "g:p", List.of(), Map.of());
        writePom(root.resolve("c"), "g", "c", "g:p", List.of(), Map.of());
        Files.createDirectories(root.resolve("a/src/main/java"));
        Files.writeString(root.resolve("a/src/main/java/X.java"), "class X{}");

        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("a/src/main/java/X.java"));
        assertFalse(am.isAll());
        assertEquals(List.of("a"), am.modules());
    }

    @Test
    void downstreamClosure_propagatesThroughGraph(@TempDir Path root) throws IOException {
        // a ← b ← c    (c depends on b, b depends on a). Conflict in a → {a,b,c}.
        writePom(root, "g", "p", null, List.of("a", "b", "c"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        writePom(root.resolve("b"), "g", "b", "g:p", List.of(), Map.of("g:a", "1"));
        writePom(root.resolve("c"), "g", "c", "g:p", List.of(), Map.of("g:b", "1"));
        Files.createDirectories(root.resolve("a/src/main/java"));
        Files.writeString(root.resolve("a/src/main/java/X.java"), "class X{}");

        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("a/src/main/java/X.java"));
        assertFalse(am.isAll());
        assertEquals(Set.of("a", "b", "c"), Set.copyOf(am.modules()));
    }

    @Test
    void downstreamClosure_unrelatedSiblingNotIncluded(@TempDir Path root) throws IOException {
        // a, b independent leaves; c depends on a. Conflict in a → {a,c}, b excluded.
        writePom(root, "g", "p", null, List.of("a", "b", "c"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        writePom(root.resolve("b"), "g", "b", "g:p", List.of(), Map.of());
        writePom(root.resolve("c"), "g", "c", "g:p", List.of(), Map.of("g:a", "1"));
        Files.createDirectories(root.resolve("a/src/main/java"));
        Files.writeString(root.resolve("a/src/main/java/X.java"), "class X{}");

        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("a/src/main/java/X.java"));
        assertEquals(Set.of("a", "c"), Set.copyOf(am.modules()));
    }

    @Test
    void nestedModule_deepestPomWins(@TempDir Path root) throws IOException {
        // a/sub is a nested module. Conflict in a/sub/.../X.java → owner = "a/sub".
        writePom(root, "g", "p", null, List.of("a"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of("sub"), Map.of());
        writePom(root.resolve("a/sub"), "g", "asub", "g:a", List.of(), Map.of());
        Files.createDirectories(root.resolve("a/sub/src/main/java"));
        Files.writeString(root.resolve("a/sub/src/main/java/X.java"), "class X{}");

        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("a/sub/src/main/java/X.java"));
        assertEquals(List.of("a/sub"), am.modules());
    }

    @Test
    void deletedFile_pathStillResolvesToOwningModule(@TempDir Path root) throws IOException {
        // The file does not exist on disk, but its directory's pom does.
        writePom(root, "g", "p", null, List.of("a"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        Files.createDirectories(root.resolve("a/src/main/java"));
        // do NOT create the file
        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("a/src/main/java/Gone.java"));
        assertEquals(List.of("a"), am.modules());
    }

    @Test
    void dependencyManagementIsIgnored_noEdgeCreated(@TempDir Path root) throws IOException {
        // c lists g:a only under <dependencyManagement>, not <dependencies>.
        writePom(root, "g", "p", null, List.of("a", "c"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        writePomWithDepMgmt(root.resolve("c"), "g", "c", "g:p", Map.of("g:a", "1"));
        Files.createDirectories(root.resolve("a/src/main/java"));
        Files.writeString(root.resolve("a/src/main/java/X.java"), "class X{}");

        AffectedModules am = ConflictModuleAnalyzer.analyze(root, List.of("a/src/main/java/X.java"));
        // c should NOT be pulled in via dependencyManagement
        assertEquals(List.of("a"), am.modules());
    }

    @Test
    void multipleConflictFiles_unionThenClose(@TempDir Path root) throws IOException {
        // a, b independent leaves; c depends on b. Conflicts in a and b → {a,b,c}.
        writePom(root, "g", "p", null, List.of("a", "b", "c"), Map.of());
        writePom(root.resolve("a"), "g", "a", "g:p", List.of(), Map.of());
        writePom(root.resolve("b"), "g", "b", "g:p", List.of(), Map.of());
        writePom(root.resolve("c"), "g", "c", "g:p", List.of(), Map.of("g:b", "1"));
        Files.createDirectories(root.resolve("a/src/main/java"));
        Files.createDirectories(root.resolve("b/src/main/java"));
        Files.writeString(root.resolve("a/src/main/java/X.java"), "class X{}");
        Files.writeString(root.resolve("b/src/main/java/Y.java"), "class Y{}");

        AffectedModules am = ConflictModuleAnalyzer.analyze(root,
                List.of("a/src/main/java/X.java", "b/src/main/java/Y.java"));
        assertEquals(Set.of("a", "b", "c"), Set.copyOf(am.modules()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Write a minimal {@code pom.xml} under {@code dir}.
     *
     * @param parentGa  null for root, "g:p" for a child of group g + parent artifact p (no version)
     * @param submods   names of child {@code <module>} dirs
     * @param deps      gaKey ("g:a") → version, written as {@code <dependencies>} entries
     */
    private static void writePom(Path dir, String groupId, String artifactId, String parentGa,
                                  List<String> submods, Map<String, String> deps) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n")
          .append("  <modelVersion>4.0.0</modelVersion>\n");
        if (parentGa != null) {
            String[] ga = parentGa.split(":");
            sb.append("  <parent><groupId>").append(ga[0]).append("</groupId><artifactId>")
              .append(ga[1]).append("</artifactId><version>1</version></parent>\n");
        }
        if (groupId != null) sb.append("  <groupId>").append(groupId).append("</groupId>\n");
        sb.append("  <artifactId>").append(artifactId).append("</artifactId>\n");
        if (parentGa == null) sb.append("  <version>1</version>\n");
        if (!submods.isEmpty()) {
            sb.append("  <modules>\n");
            for (String m : submods) sb.append("    <module>").append(m).append("</module>\n");
            sb.append("  </modules>\n");
        }
        if (!deps.isEmpty()) {
            sb.append("  <dependencies>\n");
            for (Map.Entry<String, String> e : deps.entrySet()) {
                String[] ga = e.getKey().split(":");
                sb.append("    <dependency><groupId>").append(ga[0]).append("</groupId><artifactId>")
                  .append(ga[1]).append("</artifactId><version>").append(e.getValue()).append("</version></dependency>\n");
            }
            sb.append("  </dependencies>\n");
        }
        sb.append("</project>\n");
        Files.writeString(dir.resolve("pom.xml"), sb.toString());
    }

    /** Write a pom whose only intra-project edge candidates live under {@code <dependencyManagement>}. */
    private static void writePomWithDepMgmt(Path dir, String groupId, String artifactId, String parentGa,
                                             Map<String, String> deps) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n")
          .append("  <modelVersion>4.0.0</modelVersion>\n");
        String[] pga = parentGa.split(":");
        sb.append("  <parent><groupId>").append(pga[0]).append("</groupId><artifactId>")
          .append(pga[1]).append("</artifactId><version>1</version></parent>\n")
          .append("  <artifactId>").append(artifactId).append("</artifactId>\n")
          .append("  <dependencyManagement><dependencies>\n");
        for (Map.Entry<String, String> e : deps.entrySet()) {
            String[] ga = e.getKey().split(":");
            sb.append("    <dependency><groupId>").append(ga[0]).append("</groupId><artifactId>")
              .append(ga[1]).append("</artifactId><version>").append(e.getValue()).append("</version></dependency>\n");
        }
        sb.append("  </dependencies></dependencyManagement>\n</project>\n");
        Files.writeString(dir.resolve("pom.xml"), sb.toString());
    }
}
