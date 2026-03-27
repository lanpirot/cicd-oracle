package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.util.FileUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

@Getter
@Setter
public class TestTotalXml {
    private int runNum;
    private int failuresNum;
    private int errorsNum;
    private int skippedNum;
    private float elapsedTime;

    private final File projectDir;

    public TestTotalXml(File projectDir) throws Exception {

        this.projectDir = projectDir;

        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/surefire-reports/TEST-*.xml");
        PathMatcher failsafeMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/failsafe-reports/TEST-*.xml");

        List<Path> paths = FileUtils.listFilesUsingFileWalk(projectDir.toPath());
        int counter = 0;

        for (Path file : paths) {
            if (pathMatcher.matches(file) || failsafeMatcher.matches(file)) {
                counter++;

                File xmlFile = file.toFile();
                TestMetrics metrics = parseSurefireXml(xmlFile);

                System.out.println(xmlFile + ": " + metrics.run);

                runNum += metrics.run;
                failuresNum += metrics.failures;
                errorsNum += metrics.errors;
                skippedNum += metrics.skipped;
                elapsedTime += metrics.time;
            }
        }

        System.out.println("XML files processed: " + counter);
    }

    private static class TestMetrics {
        int run;
        int failures;
        int errors;
        int skipped;
        float time;
    }

    private TestMetrics parseSurefireXml(File xmlFile) throws Exception {
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xmlFile);
        doc.getDocumentElement().normalize();

        Element testSuite = doc.getDocumentElement(); // <testsuite>

        TestMetrics m = new TestMetrics();
        m.run = parseIntAttr(testSuite, "tests");
        m.failures = parseIntAttr(testSuite, "failures");
        m.errors = parseIntAttr(testSuite, "errors");
        m.skipped = parseIntAttr(testSuite, "skipped");
        m.time = Float.parseFloat(testSuite.getAttribute("time"));

        return m;
    }

    /** Parse an integer attribute, tolerating locale-formatted thousands separators (e.g. "1,242"). */
    private static int parseIntAttr(Element el, String attr) {
        return Integer.parseInt(el.getAttribute(attr).replace(",", ""));
    }

    @Override
    public String toString() {
        return "TestTotalXml{" +
                "runNum=" + runNum +
                ", failuresNum=" + failuresNum +
                ", errorsNum=" + errorsNum +
                ", skippedNum=" + skippedNum +
                ", elapsedTime=" + elapsedTime +
                '}';
    }
}
