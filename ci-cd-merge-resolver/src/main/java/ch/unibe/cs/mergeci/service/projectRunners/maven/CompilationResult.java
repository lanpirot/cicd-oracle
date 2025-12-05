package ch.unibe.cs.mergeci.service.projectRunners.maven;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Getter
public class CompilationResult {
    private final List<ModuleResult> moduleResults;
    private final Status buildStatus;
    private final float totalTime;
    private final static String MODULE_REGEX = "\\[INFO\\]\\s(.+)\\s\\.+\\s(SUCCESS|FAILURE|SKIPPED)\\s(\\[\\s*([\\d.:]+) (min|s|ms)\\])?";
    private final static String BUILD_STATUS_REGEX = "\\[INFO\\]\\sBUILD\\s(SUCCESS|FAILURE)";
    private final static String TOTAL_TIME_REGEX = "\\[INFO\\] Total time:  ([\\d.:]+) (min|s|ms)";

    public CompilationResult(File testResultFile) throws IOException {
        moduleResults = new ArrayList<>();

        String string = new String(Files.readAllBytes(testResultFile.toPath()));
        Pattern p = Pattern.compile(MODULE_REGEX);
        Matcher m = p.matcher(string);

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

        Pattern buildStatusPattern = Pattern.compile(BUILD_STATUS_REGEX);
        Matcher buildMatcher = buildStatusPattern.matcher(string);
        buildMatcher.find();
        this.buildStatus = Status.valueOf(buildMatcher.group(1));

        Pattern totalTimePAttern = Pattern.compile(TOTAL_TIME_REGEX);
        Matcher totalTimeMatcher = totalTimePAttern.matcher(string);
        totalTimeMatcher.find();
        this.totalTime = parseTime(totalTimeMatcher.group(1), totalTimeMatcher.group(2));
    }

    private int getNumberOfModules() {
        return moduleResults.size();
    }

    private int getNumberOfSuccessfulModules() {
        return (int) moduleResults.stream().filter(x -> x.status == Status.SUCCESS).count();
    }

    @AllArgsConstructor
    @Builder
    @Getter
    public static class ModuleResult {
        private final String moduleName;
        private final Status status;
        private final float timeElapsed;

        @Override
        public String toString() {
            return String.format("ModuleResult{%1$-65s| %2$-20s| %3$-20s}",
                    "moduleName=" + moduleName, "status= " + status, "timeElapsed= " + timeElapsed);
        }
    }

    public enum Status {
        SUCCESS,
        FAILURE,
        SKIPPED
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
                        joined -> joined.isEmpty() ? "[]" : "\n" + joined+"\n"
                ));

        return "CompilationResult{" +
                "moduleResults=" + modulesStr +
                ", buildStatus=" + buildStatus +
                ", totalTime=" + totalTime +
                "}\n";
    }
}
