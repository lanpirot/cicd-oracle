package ch.unibe.cs.mergeci.runner.maven;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class JacocoReportFinder {
    private static final PathMatcher PATH_MATCHER = FileSystems.getDefault().getPathMatcher("glob:" +
            "**/target/site/jacoco/jacoco.xml");

    public static CoverageDTO getCoverageResults(Path projectPath, List<String> conflictJavaFiles) {
        List<Path> jacocoReports = findJacocoReports(projectPath);
        DocumentBuilderFactory factory = createXmlDocumentBuilderFactory();

        int coveredLines = 0;
        int missedLines = 0;
        int coveredInstructions = 0;
        int missedInstructions = 0;

        try {
            for (Path path : jacocoReports) {
                Document doc = factory.newDocumentBuilder().parse(path.toFile());
                doc.getDocumentElement().normalize();
                int[] counts = accumulateCountsFromDocument(doc);
                coveredInstructions += counts[0];
                missedInstructions  += counts[1];
                coveredLines        += counts[2];
                missedLines         += counts[3];
            }
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } catch (IOException | SAXException e) {
            throw new RuntimeException(e);
        }

        return new CoverageDTO(
                (float) coveredInstructions / (coveredInstructions + missedInstructions),
                (float) coveredLines / (coveredLines + missedLines));
    }

    private static List<Path> findJacocoReports(Path projectPath) {
        List<Path> reports = new ArrayList<>();
        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (PATH_MATCHER.matches(file)) {
                        reports.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return reports;
    }

    private static DocumentBuilderFactory createXmlDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://xml.org/sax/features/namespaces", false);
            factory.setFeature("http://xml.org/sax/features/validation", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        return factory;
    }

    /** Returns [coveredInstructions, missedInstructions, coveredLines, missedLines]. */
    private static int[] accumulateCountsFromDocument(Document doc) {
        int[] counts = new int[4];
        NodeList nodeList = doc.getDocumentElement().getElementsByTagName("class");
        for (int i = 0; i < nodeList.getLength(); i++) {
            NodeList methods = nodeList.item(i).getChildNodes();
            for (int j = 0; j < methods.getLength(); j++) {
                NodeList counters = methods.item(j).getChildNodes();
                for (int k = 0; k < counters.getLength(); k++) {
                    var attrs = counters.item(k).getAttributes();
                    if (attrs == null || attrs.getNamedItem("type") == null) continue;
                    int covered = Integer.parseInt(attrs.getNamedItem("covered").getNodeValue());
                    int missed  = Integer.parseInt(attrs.getNamedItem("missed").getNodeValue());
                    switch (attrs.getNamedItem("type").getNodeValue()) {
                        case "INSTRUCTION" -> { counts[0] += covered; counts[1] += missed; }
                        case "LINE"        -> { counts[2] += covered; counts[3] += missed; }
                    }
                }
            }
        }
        return counts;
    }

    public static record CoverageDTO(float instructionCoverage, float lineCoverage) {
    }
}
