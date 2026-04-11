package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.VariantDedup;
import ch.unibe.cs.mergeci.runner.VariantLifecycleListener;
import ch.unibe.cs.mergeci.runner.VariantOutcome;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.example.cicdmergeoracle.cicdMergeTool.model.BlockGroup;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Plugin-side adapter for {@link VariantLifecycleListener}.
 * Handles manual pins, dedup, scoring, test failure extraction,
 * and dispatches results to the Swing EDT.
 */
class PluginLifecycleListener implements VariantLifecycleListener {
    private final OracleSession session;
    private final Map<String, Double> globalWeightMap;
    private final Consumer<VariantResult> onVariantComplete;
    private final Consumer<Integer> onInFlightChanged;
    private final Consumer<Boolean> onRunFinished;
    private final Set<List<String>> seenEffective = new HashSet<>();

    PluginLifecycleListener(OracleSession session,
                             Map<String, Double> globalWeightMap,
                             Consumer<VariantResult> onVariantComplete,
                             Consumer<Integer> onInFlightChanged,
                             Consumer<Boolean> onRunFinished) {
        this.session = session;
        this.globalWeightMap = globalWeightMap;
        this.onVariantComplete = onVariantComplete;
        this.onInFlightChanged = onInFlightChanged;
        this.onRunFinished = onRunFinished;
    }

    @Override
    public VariantProject transformVariant(VariantProject variant) {
        Map<Integer, String> manualTexts = session.getManualTexts();
        if (manualTexts.isEmpty()) return variant;

        Map<Integer, BlockGroup> groupMap = session.getBlockGroupMap();
        int globalIdx = 0;

        for (ConflictFile cf : variant.getClasses()) {
            ManualPinOverlay.Result r = ManualPinOverlay.apply(
                    cf.getMergeBlocks(), globalIdx, manualTexts, groupMap, null);
            cf.setMergeBlocks(r.blocks());
            globalIdx = r.globalIdx();
        }
        return variant;
    }

    @Override
    public boolean beforeSubmit(VariantProject variant, int variantIndex) {
        List<String> effective = VariantDedup.computeEffectiveAssignment(
                variant, session.getManualTexts(), session.getManualVersionSnapshot());
        return seenEffective.add(effective);
    }

    @Override
    public void onInFlightChanged(int count) {
        SwingUtilities.invokeLater(() -> onInFlightChanged.accept(count));
    }

    @Override
    public void onVariantComplete(VariantOutcome outcome) {
        CompilationResult cr = outcome.compilationResult();
        TestTotal tt = outcome.testTotal();
        Map<String, List<String>> patterns = outcome.patternAssignment();

        List<String> failedModules = extractFailedModules(cr);
        List<String> testFailures = outcome.testsRan()
                ? extractTestFailures(outcome.variantPath()) : List.of();

        double simplicity;
        try {
            simplicity = VariantScore.computeSimplicityScore(patterns, globalWeightMap);
        } catch (IllegalStateException e) {
            simplicity = 0.0;
        }
        VariantScore score = VariantScore.of(cr, tt, simplicity)
                .map(s -> new VariantScore(s.successfulModules(), s.passedTests(),
                        s.simplicityScore(), outcome.variantIndex()))
                .orElse(null);

        Map<Integer, Integer> manualVers = session.getManualVersionSnapshot();
        VariantResult result = new VariantResult(
                outcome.variantIndex(), patterns, cr, tt, score,
                outcome.elapsed(), outcome.logFile(), manualVers,
                failedModules, testFailures, !outcome.testsRan());

        SwingUtilities.invokeLater(() -> onVariantComplete.accept(result));
    }

    @Override
    public void onRunFinished(boolean exhausted) {
        SwingUtilities.invokeLater(() -> onRunFinished.accept(exhausted));
    }

    /** Extract names of modules that did not succeed (FAILURE, SKIPPED, TIMEOUT). */
    static List<String> extractFailedModules(CompilationResult cr) {
        if (cr == null || cr.getModuleResults() == null) return List.of();
        List<String> failed = new ArrayList<>();
        for (CompilationResult.ModuleResult mr : cr.getModuleResults()) {
            if (mr.getStatus() != CompilationResult.Status.SUCCESS) {
                failed.add(mr.getModuleName() + " (" + mr.getStatus() + ")");
            }
        }
        return failed;
    }

    /** Parse surefire/failsafe XMLs for individual test failures. */
    static List<String> extractTestFailures(Path variantPath) {
        List<String> failures = new ArrayList<>();
        try {
            java.util.stream.Stream<Path> files = java.nio.file.Files.walk(variantPath, 10);
            files.filter(p -> {
                String s = p.toString();
                return (s.contains("surefire-reports") || s.contains("failsafe-reports"))
                        && s.endsWith(".xml") && p.getFileName().toString().startsWith("TEST-");
            }).forEach(xmlPath -> {
                try {
                    javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
                            .newInstance().newDocumentBuilder();
                    org.w3c.dom.Document doc = db.parse(xmlPath.toFile());
                    org.w3c.dom.NodeList testcases = doc.getElementsByTagName("testcase");
                    for (int i = 0; i < testcases.getLength(); i++) {
                        org.w3c.dom.Element tc = (org.w3c.dom.Element) testcases.item(i);
                        org.w3c.dom.NodeList failNodes = tc.getElementsByTagName("failure");
                        org.w3c.dom.NodeList errorNodes = tc.getElementsByTagName("error");
                        if (failNodes.getLength() > 0 || errorNodes.getLength() > 0) {
                            String className = tc.getAttribute("classname");
                            String method = tc.getAttribute("name");
                            String msg = "";
                            if (failNodes.getLength() > 0) {
                                msg = ((org.w3c.dom.Element) failNodes.item(0)).getAttribute("message");
                            } else if (errorNodes.getLength() > 0) {
                                msg = ((org.w3c.dom.Element) errorNodes.item(0)).getAttribute("message");
                            }
                            String entry = className + "#" + method;
                            if (!msg.isEmpty()) {
                                String shortMsg = msg.length() > 80 ? msg.substring(0, 80) + "..." : msg;
                                entry += " — " + shortMsg;
                            }
                            failures.add(entry);
                        }
                    }
                } catch (Exception e) {
                    // skip unparseable XML
                }
            });
            files.close();
        } catch (IOException e) {
            // variant path may already be gone
        }
        return failures;
    }
}
