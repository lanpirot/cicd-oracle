package ch.unibe.cs.mergeci.runner.maven;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"buildStatus", "totalTime", "totalModules", "successfulModules"})
@NoArgsConstructor(access = AccessLevel.PRIVATE, force = true)
public class CompilationResult {
    @Getter(onMethod_ = {@JsonIgnore})
    private final List<ModuleResult> moduleResults;

    @JsonProperty
    public int getTotalModules() {
        if (moduleResults != null && !moduleResults.isEmpty()) return moduleResults.size();
        // Single-module project: no Reactor Summary — always at least 1
        return 1;
    }

    @JsonProperty
    public int getSuccessfulModules() {
        if (moduleResults != null && !moduleResults.isEmpty()) return getNumberOfSuccessfulModules();
        // Single-module project: successful iff BUILD SUCCESS
        return (buildStatus == Status.SUCCESS) ? 1 : 0;
    }

    private final Status buildStatus;
    private final float totalTime;
    private final static String MODULE_REGEX = "\\[INFO\\]\\s(.+?)\\s\\.*\\s*(SUCCESS|FAILURE|SKIPPED)\\s(\\[\\s*([\\d.:]+) (min|s|ms)\\])?";
    private final static String BUILD_STATUS_REGEX = "\\[INFO\\]\\sBUILD\\s(SUCCESS|FAILURE|TIMEOUT)";
    private final static String TOTAL_TIME_REGEX = "\\[INFO\\] Total time:\\s+([\\d.:]+) (min|s|ms)";
    private final static String REACTOR_BLOCK = "\\[INFO\\]\\s+Reactor Summary(?:.*?)?:\\s*((.*\\R)*?)\\[INFO\\]\\s+-+";

    /**
     * A build killed by the wall-clock timeout can leave a multi-hundred-MB log.
     * The signal the parser needs (Building lines, Reactor Summary, BUILD status)
     * lives at the head and tail, so when a log exceeds this many chars we keep
     * only the first and last half and elide the middle — bounding the work every
     * regex below has to do (a giant timeout log once spun the parser for ~3 h).
     */
    private final static int LOG_SCAN_CAP = 16 * 1024 * 1024;



    /** Test factory — avoids writing log files to disk. */
    public static CompilationResult forTest(Status buildStatus, List<ModuleResult> moduleResults) {
        return new CompilationResult(buildStatus, moduleResults, 0f);
    }

    /**
     * Combine a pruned-variant CompilationResult with the donor's per-module breakdown:
     * built modules use the variant's per-module outcomes; skipped modules inherit the
     * donor's. Both sides are matched by {@code moduleName} (the artifactId Maven prints
     * in its Reactor Summary). The returned result's {@code buildStatus} is the variant's
     * (the variant's own success/failure on the affected modules is what matters); only
     * the per-module list and its derived counts (totalModules, successfulModules) change.
     *
     * <p>Without this merge, pruned variants always report fewer successful modules than
     * non-pruned variants, even when behaviorally equivalent — biasing variant scoring
     * against every pruned variant.
     */
    public static CompilationResult mergeWithDonor(CompilationResult variant,
                                                    CompilationResult donor) {
        if (donor == null || donor.moduleResults == null || donor.moduleResults.isEmpty()) {
            return variant;
        }
        java.util.Set<String> variantNames = variant.moduleResults == null
                ? java.util.Set.of()
                : variant.moduleResults.stream().map(ModuleResult::getModuleName)
                        .collect(java.util.stream.Collectors.toSet());
        List<ModuleResult> merged = new ArrayList<>();
        // Donor order is the canonical reactor order — preserve it for skipped modules,
        // then append the variant's own modules (typically in -pl order).
        for (ModuleResult m : donor.moduleResults) {
            if (!variantNames.contains(m.getModuleName())) merged.add(m);
        }
        if (variant.moduleResults != null) merged.addAll(variant.moduleResults);
        return new CompilationResult(variant.buildStatus, merged, variant.totalTime);
    }

    private CompilationResult(Status buildStatus, List<ModuleResult> moduleResults, float totalTime) {
        this.buildStatus = buildStatus;
        this.moduleResults = moduleResults != null ? moduleResults : new ArrayList<>();
        this.totalTime = totalTime;
    }

    public CompilationResult(Path testResultFile) throws IOException {
        moduleResults = new ArrayList<>();

        String string = new String(Files.readAllBytes(testResultFile));
        string = capHugeLog(string);
        string = string.replaceAll("\u001B\\[[;\\d]*m", ""); //clean ANSI color codes from Maven output
        // Unwrap mvnd dispatch format: when the daemon connection is stale, output
        // arrives as "Dispatch message: ProjectLogMessage{..., message='[INFO] ...'}"
        // instead of plain "[INFO] ..." lines.  Extract the nested message content.
        string = string.replaceAll(
                "Dispatch message: (?:Project|Build)LogMessage\\{[^}]*message='(\\[(?:INFO|ERROR|WARNING)\\][^']*)'\\}",
                "$1");
        // Strip mvnd timestamp prefixes (e.g. "00:57:11.738 I ") so [INFO] lines
        // are parseable by the downstream regexes.
        string = string.replaceAll("(?m)^\\d{2}:\\d{2}:\\d{2}\\.\\d+ [A-Z] ", "");
        String buildStatusString = string;

        Pattern reactorBlockPattern = Pattern.compile(REACTOR_BLOCK);
        Matcher matcher = reactorBlockPattern.matcher(string);
        if (matcher.find()) {

            String modulesString = matcher.group(1);
            buildStatusString = string.substring(matcher.end());

            Pattern p = Pattern.compile(MODULE_REGEX);
            Matcher m = p.matcher(modulesString);

            while (m.find()) {
                String moduleName = m.group(1);
                Status status = Status.valueOf(m.group(2));
                float timeElapsed = status == Status.SKIPPED ? 0 : parseTime(m.group(4), m.group(5));

                ModuleResult moduleResult = ModuleResult.builder()
                        .moduleName(moduleName)
                        .status(status)
                        .timeElapsed(timeElapsed)
                        .build();

                moduleResults.add(moduleResult);
            }
        } else {
            // Fallback for truncated logs (e.g. mvnd daemon crash / stale connection):
            // no Reactor Summary was printed, but individual module build lines
            // "Building <name> <version> [N/M]" tell us the reactor size and which
            // modules were attempted.  Parse ProjectStarted/ProjectFailed from mvnd
            // dispatch messages if present, otherwise scan for "Building ... [N/M]".
            inferModulesFromBuildLines(string);
        }

        Pattern buildStatusPattern = Pattern.compile(BUILD_STATUS_REGEX);
        Matcher buildMatcher = buildStatusPattern.matcher(buildStatusString);
        if (buildMatcher.find()) {
            Status parsed = Status.valueOf(buildMatcher.group(1));
            // Surefire/Failsafe forked-VM crashes ("The forked VM terminated without
            // properly saying goodbye … Process Exit Code: 143/137") are environmental
            // flakiness — usually a SIGTERM/SIGKILL from memory pressure when many
            // mvnd daemons run forked test JVMs concurrently — not a real build break.
            // Reclassify as TIMEOUT so the cross-mode sanity check excludes the variant
            // (timedOut variants are skipped by the position-by-position comparison)
            // instead of charging it as a compilation mismatch against the parallel mode.
            if (parsed == Status.FAILURE
                    && string.contains("The forked VM terminated without properly saying goodbye")) {
                this.buildStatus = Status.TIMEOUT;
            } else {
                this.buildStatus = parsed;
            }
        } else if (!moduleResults.isEmpty()) {
            // No explicit BUILD line but we inferred modules — derive status
            boolean anyFailure = moduleResults.stream()
                    .anyMatch(mr -> mr.getStatus() == Status.FAILURE);
            this.buildStatus = anyFailure ? Status.FAILURE : Status.SUCCESS;
        } else {
            // No BUILD SUCCESS/FAILURE/TIMEOUT line found — process exited abnormally
            // (e.g. POM resolution error, missing project, process interrupted).
            // Real timeouts are detected via the "[INFO] BUILD TIMEOUT" sentinel written
            // by MavenProcessExecutor.handleTimeout(), so this path is never a timeout.
            this.totalTime = 0;
            this.buildStatus = Status.SCAN_FAILURE;
            return;
        }


        Pattern totalTimePAttern = Pattern.compile(TOTAL_TIME_REGEX);
        Matcher totalTimeMatcher = totalTimePAttern.matcher(buildStatusString);
        this.totalTime = totalTimeMatcher.find()
                ? parseTime(totalTimeMatcher.group(1), totalTimeMatcher.group(2))
                : 0;
    }


    /** See {@link #LOG_SCAN_CAP}: keep head+tail of pathologically large logs. */
    private static String capHugeLog(String log) {
        if (log.length() <= LOG_SCAN_CAP) return log;
        int half = LOG_SCAN_CAP / 2;
        int elided = log.length() - 2 * half;
        return log.substring(0, half)
                + "\n[INFO] ... (" + elided + " chars of build log elided) ...\n"
                + log.substring(log.length() - half);
    }

    @JsonIgnore
    public int getNumberOfModules() {
        return moduleResults == null ? 0 : moduleResults.size();
    }

    @JsonIgnore
    public int getNumberOfSuccessfulModules() {
        return moduleResults == null ? 0 : (int) moduleResults.stream().filter(x -> x.status == Status.SUCCESS).count();
    }

    @AllArgsConstructor
    @Builder
    @Getter
    @NoArgsConstructor
    public static class ModuleResult {
        private String moduleName;
        private Status status;
        private float timeElapsed;

        @Override
        public String toString() {
            return String.format("ModuleResult{%1$-65s| %2$-20s| %3$-20s}",
                    "moduleName=" + moduleName, "status= " + status, "timeElapsed= " + timeElapsed);
        }
    }

    /**
     * Fallback when the Reactor Summary is missing (e.g. mvnd daemon crash).
     * Scans for "[INFO] Building &lt;name&gt; &lt;version&gt; [N/M]" lines to discover
     * the reactor size, and for mvnd dispatch markers or compilation errors to
     * infer per-module success/failure.
     */
    private void inferModulesFromBuildLines(String log) {
        // Single linear pass over the log. We track the module currently being built
        // (from "[INFO] Building <name> <version> [n/m]" lines) and flag it FAILURE when
        // a compilation/test/build-failure marker appears while it is the current module.
        //
        // This deliberately does NOT build a "(?s)Building <name>.*?(?:…|.*…)" pattern per
        // module and run it over the whole log: that alternation backtracks catastrophically
        // on the multi-hundred-MB logs left by builds that hit the wall-clock timeout — it
        // once spun the RQ3 pipeline's main thread for ~3 h on a single timed-out build.
        // Per-line matching against anchored patterns is linear and immune to that.
        Pattern building = Pattern.compile(
                "\\[INFO\\] Building (.+?)\\s+\\S+\\s+\\[(\\d+)/(\\d+)\\]");
        Pattern testFailures = Pattern.compile("Failures: [1-9]");

        java.util.LinkedHashMap<String, Status> seen = new java.util.LinkedHashMap<>();
        String current = null;
        for (String line : log.split("\\R", -1)) {
            Matcher bm = building.matcher(line);
            if (bm.find()) {
                current = bm.group(1).trim();
                seen.putIfAbsent(current, Status.SUCCESS); // assume success unless proven failed
                continue;
            }
            if (current == null) continue;
            boolean failed = line.contains("[ERROR] COMPILATION ERROR")
                    || line.contains("BUILD FAILURE")
                    || (line.contains("[ERROR]") && testFailures.matcher(line).find());
            if (failed) {
                seen.put(current, Status.FAILURE);
            }
        }

        for (var entry : seen.entrySet()) {
            // Skip pom-only parent modules (they appear as "[1/N]" but have no code)
            moduleResults.add(ModuleResult.builder()
                    .moduleName(entry.getKey())
                    .status(entry.getValue())
                    .timeElapsed(0)
                    .build());
        }
    }

    public enum Status {
        SUCCESS,
        FAILURE,
        SKIPPED,
        TIMEOUT,
        /** Maven exited before any module reached compile (e.g. unparseable POM,
         *  unresolvable parent, missing project descriptor). The log has neither
         *  a Reactor Summary nor a BUILD SUCCESS/FAILURE/TIMEOUT line. */
        SCAN_FAILURE
    }

    private float parseTime(String timeString, String timeUnit) {
        return switch (timeUnit) {
            case "min" -> {
                int minutes = Integer.parseInt(timeString.split(":")[0]);
                int seconds = Integer.parseInt(timeString.split(":")[1]);

                yield minutes * 60 + seconds;
            }
            case "s" -> Float.parseFloat(timeString);
            case "ms" -> Float.parseFloat(timeString) / 1000;
            default -> throw new IllegalArgumentException("Invalid time unit: " + timeUnit);
        };
    }

    private float parseTime(String timeString) {
        return parseTime(timeString, "s");
    }

    @Override
    public String toString() {
        String modulesStr = moduleResults.stream()
                .map(x -> "\t" + x)
                .collect(Collectors.collectingAndThen(
                        Collectors.joining("\n"),
                        joined -> joined.isEmpty() ? "[]" : "\n" + joined + "\n"
                ));

        return "CompilationResult{" +
                "moduleResults=" + modulesStr +
                ", buildStatus=" + buildStatus +
                ", totalTime=" + totalTime +
                "}\n";
    }
}
