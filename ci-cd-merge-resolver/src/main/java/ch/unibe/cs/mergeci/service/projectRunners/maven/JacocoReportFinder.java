package ch.unibe.cs.mergeci.service.projectRunners.maven;

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
        List<Path> jacocoReports = new ArrayList<>();
        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (PATH_MATCHER.matches(file)) {
                        System.out.println("Found jacoco report: " + file);
                        jacocoReports.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setValidating(false);
        documentBuilderFactory.setNamespaceAware(true);
        try {
            documentBuilderFactory.setFeature("http://xml.org/sax/features/namespaces", false);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/validation", false);
            documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
            documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        int coveredLines = 0;
        int missedLines = 0;
        int coveredInstructions = 0;
        int missedInstructions = 0;

        try {
            for (Path path : jacocoReports) {
                DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();

                Document doc = builder.parse(path.toFile());
                doc.getDocumentElement().normalize();
                NodeList nodeList = doc.getDocumentElement().getElementsByTagName("class");
                for (int i = 0; i < nodeList.getLength(); i++) {
                    NodeList methods = nodeList.item(i).getChildNodes();
                    for (int j = 0; j < methods.getLength(); j++) {
                        NodeList counters = methods.item(j).getChildNodes();
                        for (int k = 0; k < counters.getLength(); k++) {
                            switch (counters.item(k).getAttributes().getNamedItem("type").getNodeValue()) {
                                case "INSTRUCTION" -> {
                                    coveredInstructions += Integer.parseInt(counters.item(k).getAttributes().getNamedItem("covered").getNodeValue());
                                    missedInstructions += Integer.parseInt(counters.item(k).getAttributes().getNamedItem("missed").getNodeValue());
                                }
                                case "LINE" -> {
                                    coveredLines += Integer.parseInt(counters.item(k).getAttributes().getNamedItem("covered").getNodeValue());
                                    missedLines += Integer.parseInt(counters.item(k).getAttributes().getNamedItem("missed").getNodeValue());
                                }
                            }
                        }
                    }
                }
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

    public static record CoverageDTO(float instructionCoverage, float lineCoverage) {
    }
}
