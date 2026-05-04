package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestTotalPerModuleTest extends BaseTest {

    @Test
    void bucketsReportsByEnclosingModule(@TempDir Path root) throws IOException {
        // Multi-module: two modules each with their own pom.xml + surefire reports.
        writeMinimalPom(root, "p");
        writeMinimalPom(root.resolve("modA"), "modA");
        writeMinimalPom(root.resolve("modB"), "modB");
        writeReport(root.resolve("modA/target/surefire-reports/com.foo.AT.txt"),
                "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.0 s");
        writeReport(root.resolve("modB/target/surefire-reports/com.foo.BT.txt"),
                "Tests run: 6, Failures: 1, Errors: 0, Skipped: 1, Time elapsed: 2.5 s");
        writeReport(root.resolve("modB/target/failsafe-reports/com.foo.BIT.txt"),
                "Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.5 s");

        TestTotal tt = new TestTotal(root.toFile());

        // Project-wide totals unchanged
        assertEquals(12, tt.getRunNum());
        assertEquals(1, tt.getFailuresNum());
        assertEquals(1, tt.getErrorsNum());
        assertEquals(1, tt.getSkippedNum());

        // Per-module bucketing
        assertEquals(Set.of("modA", "modB"), tt.getPerModule().keySet());
        TestTotal.ModuleTotal a = tt.getPerModule().get("modA");
        TestTotal.ModuleTotal b = tt.getPerModule().get("modB");
        assertEquals(4, a.getRunNum());
        assertEquals(8, b.getRunNum());
        assertEquals(1, b.getFailuresNum());
        assertEquals(1, b.getErrorsNum());
        assertEquals(1, b.getSkippedNum());
    }

    @Test
    void singleModuleProject_bucketsUnderEmptyKey(@TempDir Path root) throws IOException {
        writeMinimalPom(root, "p");
        writeReport(root.resolve("target/surefire-reports/com.foo.T.txt"),
                "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s");

        TestTotal tt = new TestTotal(root.toFile());
        assertEquals(5, tt.getRunNum());
        assertEquals(Set.of(""), tt.getPerModule().keySet());
        assertEquals(5, tt.getPerModule().get("").getRunNum());
    }

    @Test
    void mergeWithDonor_inheritsSkippedModulesAndKeepsBuiltOnes(@TempDir Path root) throws IOException {
        // Variant only built modA → variant TestTotal has 4 tests in modA
        writeMinimalPom(root, "p");
        writeMinimalPom(root.resolve("modA"), "modA");
        writeReport(root.resolve("modA/target/surefire-reports/com.foo.AT.txt"),
                "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.0 s");
        TestTotal variant = new TestTotal(root.toFile());

        // Donor's per-module map (built everything)
        Map<String, TestTotal.ModuleTotal> donor = Map.of(
                "modA", new TestTotal.ModuleTotal(4, 0, 0, 0, 1.0f),
                "modB", new TestTotal.ModuleTotal(8, 1, 1, 1, 3.0f),
                "modC", new TestTotal.ModuleTotal(2, 0, 0, 0, 0.5f));

        TestTotal merged = TestTotal.mergeWithDonor(variant, donor, List.of("modB", "modC"));
        assertEquals(4 + 8 + 2, merged.getRunNum());
        assertEquals(1, merged.getFailuresNum());
        assertEquals(1, merged.getErrorsNum());
        assertEquals(1, merged.getSkippedNum());
        // Built modA must come from variant; skipped modB, modC inherited
        assertTrue(merged.getPerModule().containsKey("modA"));
        assertTrue(merged.getPerModule().containsKey("modB"));
        assertTrue(merged.getPerModule().containsKey("modC"));
    }

    @Test
    void mergeWithDonor_nullDonorMapReturnsCopy(@TempDir Path root) throws IOException {
        writeMinimalPom(root, "p");
        writeReport(root.resolve("target/surefire-reports/com.foo.T.txt"),
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s");
        TestTotal variant = new TestTotal(root.toFile());

        TestTotal merged = TestTotal.mergeWithDonor(variant, null, List.of("modX"));
        assertEquals(3, merged.getRunNum());
    }

    @Test
    void mergeWithDonor_skippedModuleAbsentFromDonor_silentlyIgnored(@TempDir Path root) throws IOException {
        writeMinimalPom(root, "p");
        writeReport(root.resolve("target/surefire-reports/com.foo.T.txt"),
                "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s");
        TestTotal variant = new TestTotal(root.toFile());

        Map<String, TestTotal.ModuleTotal> donor = Map.of("modA", new TestTotal.ModuleTotal(7, 0, 0, 0, 1f));
        TestTotal merged = TestTotal.mergeWithDonor(variant, donor, List.of("modZZZ"));
        assertEquals(3, merged.getRunNum()); // donor had no modZZZ entry
    }

    private static void writeReport(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body + "\n");
    }

    private static void writeMinimalPom(Path dir, String artifactId) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                "  <modelVersion>4.0.0</modelVersion>\n" +
                "  <groupId>g</groupId>\n" +
                "  <artifactId>" + artifactId + "</artifactId>\n" +
                "  <version>1</version>\n" +
                "</project>\n");
        // Don't bother with <module> wiring — TestTotal doesn't read it; per-module bucketing
        // only requires that a pom.xml sits at the directory we want as a key.
    }
}
