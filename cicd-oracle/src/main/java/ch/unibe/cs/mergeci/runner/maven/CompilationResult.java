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



    /** Test factory — avoids writing log files to disk. */
    public static CompilationResult forTest(Status buildStatus, List<ModuleResult> moduleResults) {
        return new CompilationResult(buildStatus, moduleResults, 0f);
    }

    private CompilationResult(Status buildStatus, List<ModuleResult> moduleResults, float totalTime) {
        this.buildStatus = buildStatus;
        this.moduleResults = moduleResults != null ? moduleResults : new ArrayList<>();
        this.totalTime = totalTime;
    }

    public CompilationResult(Path testResultFile) throws IOException {
        moduleResults = new ArrayList<>();

        String string = new String(Files.readAllBytes(testResultFile));
        string = string.replaceAll("\u001B\\[[;\\d]*m", ""); //clean ANSI color codes from Maven output
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
        }

        Pattern buildStatusPattern = Pattern.compile(BUILD_STATUS_REGEX);
        Matcher buildMatcher = buildStatusPattern.matcher(buildStatusString);
        if (buildMatcher.find()) {
            this.buildStatus = Status.valueOf(buildMatcher.group(1));
        } else {
            // No BUILD SUCCESS/FAILURE/TIMEOUT line found — process exited abnormally
            // (e.g. POM resolution error, missing project, process interrupted).
            // Real timeouts are detected via the "[INFO] BUILD TIMEOUT" sentinel written
            // by MavenProcessExecutor.handleTimeout(), so this path is never a timeout.
            this.totalTime = 0;
            this.buildStatus = null;
            return;
        }


        Pattern totalTimePAttern = Pattern.compile(TOTAL_TIME_REGEX);
        Matcher totalTimeMatcher = totalTimePAttern.matcher(buildStatusString);
        this.totalTime = totalTimeMatcher.find()
                ? parseTime(totalTimeMatcher.group(1), totalTimeMatcher.group(2))
                : 0;
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

    public enum Status {
        SUCCESS,
        FAILURE,
        SKIPPED,
        TIMEOUT
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
