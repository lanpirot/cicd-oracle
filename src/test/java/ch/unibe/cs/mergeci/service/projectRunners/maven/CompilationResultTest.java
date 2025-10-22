package ch.unibe.cs.mergeci.service.projectRunners.maven;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CompilationResultTest {
    @Test
    void test() {
        Pattern p = Pattern.compile("\\[INFO\\]\\s(\\S+)\\s\\.+\\s(SUCCESS|FAILURE)\\s\\[\\s{2}(\\d+(.\\d+)?)\\ss\\]");
        Matcher m = p.matcher("""
                [INFO] ------------------------------------------------------------------------
                [INFO] Reactor Summary for atmosphere-project 3.0.14:
                [INFO]
                [INFO] atmosphere-buildtools .............................. SUCCESS [  0.524 s]
                [INFO] atmosphere-project ................................. SUCCESS [  1.059 s]
                [INFO] atmosphere-runtime ................................. SUCCESS [  4.768 s]
                [INFO] atmosphere-modules ................................. SUCCESS [  0.038 s]
                [INFO] atmosphere-runtime-libs ............................ SUCCESS [  0.735 s]
                [INFO] atmosphere-assembly ................................ SUCCESS [  0.031 s]
                [INFO] ------------------------------------------------------------------------
                [INFO] BUILD SUCCESS
                [INFO] ------------------------------------------------------------------------
                [INFO] Total time:  8.171 s
                [INFO] Finished at: 2025-10-22T11:06:10+02:00
                """);

        while (m.find()) {
            String moduleName = m.group(1);
            CompilationResult.Status status = CompilationResult.Status.valueOf(m.group(2));
            float timeElapsed = Float.parseFloat(m.group(3));

            CompilationResult.ModuleResult moduleResult = CompilationResult.ModuleResult.builder()
                    .moduleName(moduleName)
                    .status(status)
                    .timeElapsed(timeElapsed)
                    .build();
        }
    }

}