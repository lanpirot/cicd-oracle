package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.util.FileUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TestTotal {
    private int runNum;
    private int failuresNum;
    private int errorsNum;
    private int skippedNum;
    private float elapsedTime;

    /** True only when at least one surefire/failsafe report was successfully parsed.
     *  False means no test reports were found — distinguishable from "ran 0 tests". */
    private boolean hasData;

    /** Per-module breakdown keyed by module directory relative to {@code projectDir}
     *  (POSIX-style, e.g. {@code "para-server"}). Empty for the JSON-deserialized form;
     *  populated only when constructed from a fresh build's project tree. */
    @JsonIgnore
    private Map<String, ModuleTotal> perModule = new LinkedHashMap<>();

    @JsonIgnore
    private File projectDir;

    @JsonProperty
    public int getPassedTests() {
        return runNum - failuresNum - errorsNum - skippedNum;
    }

    public TestTotal() {
    }

    /** Copy of another instance, useful when inheriting cached-but-not-rerun results from a donor. */
    public static TestTotal copyOf(TestTotal other) {
        TestTotal copy = new TestTotal();
        copy.runNum      = other.runNum;
        copy.failuresNum = other.failuresNum;
        copy.errorsNum   = other.errorsNum;
        copy.skippedNum  = other.skippedNum;
        copy.elapsedTime = other.elapsedTime;
        copy.hasData     = other.hasData;
        copy.perModule   = new LinkedHashMap<>(other.perModule);
        return copy;
    }

    /** Per-module aggregated counters. Mirrors the project-wide fields. */
    @Getter
    @Setter
    public static final class ModuleTotal {
        private int runNum;
        private int failuresNum;
        private int errorsNum;
        private int skippedNum;
        private float elapsedTime;
        public ModuleTotal() {}
        public ModuleTotal(int run, int failures, int errors, int skipped, float elapsed) {
            this.runNum = run; this.failuresNum = failures; this.errorsNum = errors;
            this.skippedNum = skipped; this.elapsedTime = elapsed;
        }
        void add(TestResult r) {
            runNum      += r.getRunNum();
            failuresNum += r.getFailuresNum();
            errorsNum   += r.getErrorsNum();
            skippedNum  += r.getSkippedNum();
            elapsedTime += r.getElapsedTime();
        }
    }

    /**
     * Combine a pruned-variant TestTotal with the donor's per-module breakdown:
     * built modules use the variant's counts, skipped modules inherit the donor's.
     *
     * @param variant       the just-built variant's TestTotal (only contains built modules)
     * @param donorPerModule donor's per-module breakdown — must cover every module the donor saw
     * @param skippedModules modules the variant did NOT build (inherited from donor)
     * @return a new TestTotal whose project-wide fields = variant's fields + donor's fields for {@code skippedModules}
     */
    public static TestTotal mergeWithDonor(TestTotal variant,
                                            Map<String, ModuleTotal> donorPerModule,
                                            Collection<String> skippedModules) {
        TestTotal merged = copyOf(variant);
        if (donorPerModule == null) return merged;
        Set<String> skipped = Set.copyOf(skippedModules);
        for (String m : skipped) {
            ModuleTotal d = donorPerModule.get(m);
            if (d == null) continue;
            merged.runNum      += d.runNum;
            merged.failuresNum += d.failuresNum;
            merged.errorsNum   += d.errorsNum;
            merged.skippedNum  += d.skippedNum;
            merged.elapsedTime += d.elapsedTime;
            if (d.runNum > 0 || d.failuresNum > 0 || d.errorsNum > 0 || d.skippedNum > 0) {
                merged.hasData = true;
            }
            merged.perModule.put(m, copyModule(d));
        }
        return merged;
    }

    private static ModuleTotal copyModule(ModuleTotal m) {
        return new ModuleTotal(m.runNum, m.failuresNum, m.errorsNum, m.skippedNum, m.elapsedTime);
    }

    public TestTotal(File projectDir) {

        this.projectDir = projectDir;

        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/surefire-reports/*.txt");
        PathMatcher failsafeMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/failsafe-reports/*.txt");

        List<Path> paths = null;
        try {
            try {
                paths = FileUtils.listFilesUsingFileWalk(projectDir.toPath());
            } catch (java.nio.file.NoSuchFileException missing) {
                // The variant's working tree (usually a fuse-overlayfs mount) is gone
                // before we got to read surefire reports — most often a fuse-overlayfs
                // daemon exit under tmpfs/RAM pressure on highly parallel runs. The
                // CompilationResult was already parsed from the engineLogDir log file
                // (which lives outside the mount), so we can still rank this variant by
                // its module count; treat tests as "no data" rather than failing the
                // whole worker and losing the compilation outcome too.
                hasData = false;
                paths = java.util.Collections.emptyList();
            }


        Path projectRoot = projectDir.toPath().toAbsolutePath().normalize();
        for (Path file : paths) {
            if (pathMatcher.matches(file) || failsafeMatcher.matches(file)) {
                TestResult testResult = TestResult.createTestResultFromFile(file.toFile());
                if (testResult == null) {continue;}
                runNum += testResult.getRunNum();
                failuresNum += testResult.getFailuresNum();
                errorsNum += testResult.getErrorsNum();
                skippedNum += testResult.getSkippedNum();
                elapsedTime += testResult.getElapsedTime();
                hasData = true;
                String module = enclosingModule(projectRoot, file);
                perModule.computeIfAbsent(module, k -> new ModuleTotal()).add(testResult);
            }
        }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // JUnit 4 test suites (e.g. PrimarySuite) report 0 tests in the .txt wrapper but the
        // actual counts are in the XML file.  Fall back to XML when .txt files gave us nothing.
        if (runNum == 0 && hasData) {
            try {
                TestTotalXml xml = new TestTotalXml(projectDir);
                if (xml.getRunNum() > 0) {
                    runNum      = xml.getRunNum();
                    failuresNum = xml.getFailuresNum();
                    errorsNum   = xml.getErrorsNum();
                    skippedNum  = xml.getSkippedNum();
                    elapsedTime = xml.getElapsedTime();
                }
            } catch (Exception ignored) {
                // XML fallback is best-effort; keep the txt-based (zero) result on failure
            }
        }
    }

    /**
     * Walk up from a surefire/failsafe report toward {@code projectRoot}, returning the
     * relative directory of the nearest enclosing {@code pom.xml} (POSIX-style separators).
     * Empty string means "the root pom is the only enclosing pom" (single-module project, or
     * an aggregator-attached report). Used to bucket per-module test counts.
     */
    static String enclosingModule(Path projectRoot, Path reportFile) {
        Path p = reportFile.toAbsolutePath().normalize().getParent();
        while (p != null && p.startsWith(projectRoot)) {
            if (Files.isRegularFile(p.resolve("pom.xml"))) {
                String rel = projectRoot.relativize(p).toString().replace('\\', '/');
                return rel;
            }
            if (p.equals(projectRoot)) break;
            p = p.getParent();
        }
        return "";
    }

    @Override
    public String toString() {
        return "TestTotal{" +
                "runNum=" + runNum +
                ", failuresNum=" + failuresNum +
                ", errorsNum=" + errorsNum +
                ", skippedNum=" + skippedNum +
                ", elapsedTime=" + elapsedTime +
                '}';
    }
}
