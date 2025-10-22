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

@Getter
public class CompilationResult {
/*    private final List<ModuleResult> moduleResults;
    private final Status buildStatus;
    private final float totalTime;
    private final static String MODULE_REGEX = "\\[INFO\\]\\s(\\S+)\\s\\.+\\s(SUCCESS|FAILURE)\\s\\[\\s{2}(\\d+(.\\d+)?)\\ss\\]";
    private final static String BUILD_STATUS_REGEX = "\\[INFO\\] BUILD (SUCCESS|FAILURE)";
    private final static String TOTAL_TIME_REGEX = "\\[INFO\\] Total time:  (\\d+(.\\d+)?)";

    public CompilationResult(File testResultFile) throws IOException {
        moduleResults = new ArrayList<>();

        String string = Files.readAllLines(testResultFile.toPath()).getLast();
        Pattern p = Pattern.compile(MODULE_REGEX);
        Matcher m = p.matcher(string);

        while (m.find()) {
            String moduleName = m.group(1);
            Status status = Status.valueOf(m.group(2));
            float timeElapsed = Float.parseFloat(m.group(3));

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
        this.totalTime = Float.parseFloat(totalTimeMatcher.group(1));
    }

    private int getNumberOfModules() {
        return moduleResults.size();
    }

    private int getNumberOfSuccessfulModules() {
        return (int)moduleResults.stream().filter(x->x.status == Status.SUCCESS).count();
    }

    @AllArgsConstructor
    @ToString
    @Builder
    public static class ModuleResult {
        private final String moduleName;
        private final Status status;
        private final float timeElapsed;
    }

    public enum Status {
        SUCCESS,
        FAILURE
    }*/
}
