package ch.unibe.cs.mergeci.service.projectRunners.maven;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
@RequiredArgsConstructor
@Builder
public class TestResult {
    private final int runNum;
    private final int failuresNum;
    private final int errorsNum;
    private final int skippedNum;
    private final float elapsedTime;

    public int getPassedNum() {
        return runNum - failuresNum - errorsNum - skippedNum;
    }


    public static TestResult createTestResultFromFile(File testResultFile) throws IOException {
        TestResult testResult = null;

        String string = Files.readAllLines(testResultFile.toPath()).getLast();
        Pattern p = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+), Time elapsed: (\\d+(.\\d+)?).*");
        Matcher m = p.matcher(string);

        if (m.find()) {
            int runNum = Integer.parseInt(m.group(1));
            int failuresNum = Integer.parseInt(m.group(2));
            int errorsNum = Integer.parseInt(m.group(3));
            int skippedNum = Integer.parseInt(m.group(4));
            float timeElapsed = Float.parseFloat(m.group(5));

            testResult = new TestResult(runNum, failuresNum, errorsNum, skippedNum, timeElapsed);
        }

        return testResult;
    }
}
