package ch.unibe.cs.mergeci.service.projectRunners.maven;

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

        List<Path> paths = FileUtils.listFilesUsingFileWalk(projectDir.getPath());
        int counter = 0;

        for (Path file : paths) {
            if (pathMatcher.matches(file)) {
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
        m.run = Integer.parseInt(testSuite.getAttribute("tests"));
        m.failures = Integer.parseInt(testSuite.getAttribute("failures"));
        m.errors = Integer.parseInt(testSuite.getAttribute("errors"));
        m.skipped = Integer.parseInt(testSuite.getAttribute("skipped"));
        m.time = Float.parseFloat(testSuite.getAttribute("time"));

        return m;
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

