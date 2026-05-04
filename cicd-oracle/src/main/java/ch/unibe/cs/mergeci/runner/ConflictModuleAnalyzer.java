package ch.unibe.cs.mergeci.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Selective reactor pruning, phase 1: determine which Maven modules a set of
 * conflict files transitively affects, so that variants can be built with
 * {@code mvn -pl <affected>} and skip the rest.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>For each conflict file path, walk up to the deepest enclosing {@code pom.xml}.
 *       That directory (relative to the repo root) is the file's "owning" module.
 *   <li>Build the project module graph from every {@code pom.xml} under the root,
 *       recording which modules' {@code <dependencies>} reference which other modules
 *       (intra-project edges only — external coords ignored).
 *   <li>Take the downstream-transitive closure of the owning modules.
 * </ol>
 *
 * <p>Returns an {@link AffectedModules} carrying either the closed set of relative
 * module directories, or the {@link AffectedModules#all()} sentinel meaning
 * "pruning would be unsafe — build the full reactor". The sentinel is used when:
 * <ul>
 *   <li>any conflict file is the root {@code pom.xml} (parent-pom edits invalidate everything);
 *   <li>any conflict file's owning pom is the root pom (i.e. file lives outside any module);
 *   <li>the project has no root {@code pom.xml} at all.
 * </ul>
 */
public final class ConflictModuleAnalyzer {
    private static final Logger LOG = LoggerFactory.getLogger(ConflictModuleAnalyzer.class);

    private ConflictModuleAnalyzer() {}

    /** Result: either a concrete set of relative module dirs, or the all-affected sentinel. */
    public static final class AffectedModules {
        private static final AffectedModules ALL = new AffectedModules(null);
        private final List<String> modules;

        private AffectedModules(List<String> modules) { this.modules = modules; }

        public static AffectedModules all() { return ALL; }

        public static AffectedModules of(Collection<String> modules) {
            return new AffectedModules(new ArrayList<>(new LinkedHashSet<>(modules)));
        }

        /** True when pruning is unsafe — the full reactor must be built. */
        public boolean isAll() { return this == ALL; }

        /** Relative module directory paths (e.g. {@code para-server}). Order is stable. */
        public List<String> modules() {
            if (isAll()) throw new IllegalStateException("AffectedModules.all() has no concrete list");
            return modules;
        }

        @Override
        public String toString() {
            return isAll() ? "AffectedModules{ALL}" : "AffectedModules" + modules;
        }
    }

    /**
     * Compute the set of modules that must be rebuilt for variants of this merge.
     *
     * @param repoRoot         absolute path of the project root
     * @param conflictRelPaths conflict file paths relative to {@code repoRoot} (POSIX-style separators OK)
     * @return concrete affected module set, or {@link AffectedModules#all()} if pruning is unsafe
     */
    public static AffectedModules analyze(Path repoRoot, Collection<String> conflictRelPaths) {
        Path rootPom = repoRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            return AffectedModules.all();
        }

        Set<String> ownerModules = new LinkedHashSet<>();
        for (String rel : conflictRelPaths) {
            String norm = rel.replace('\\', '/');
            // Edits to any pom.xml — including the root parent pom or a module pom —
            // invalidate the project model in ways pruning can't reason about.
            if (norm.endsWith("pom.xml") || norm.equals("pom.xml")) {
                return AffectedModules.all();
            }
            Optional<String> owner = findOwningModule(repoRoot, norm);
            if (owner.isEmpty() || owner.get().isEmpty()) {
                // Conflict file lives directly under the root (no enclosing module pom)
                // or could not be resolved — fall back to full reactor.
                return AffectedModules.all();
            }
            ownerModules.add(owner.get());
        }

        if (ownerModules.isEmpty()) {
            return AffectedModules.all();
        }

        Map<String, Set<String>> downstream = buildDownstreamGraph(repoRoot);
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(ownerModules);
        while (!stack.isEmpty()) {
            String m = stack.pop();
            if (!closure.add(m)) continue;
            Set<String> dependents = downstream.get(m);
            if (dependents != null) stack.addAll(dependents);
        }
        return AffectedModules.of(closure);
    }

    /**
     * Walk up from {@code repoRoot.resolve(relFilePath)} towards {@code repoRoot},
     * returning the relative directory of the deepest enclosing {@code pom.xml}
     * that is NOT the root pom. Empty string means "the root pom is the only enclosing
     * pom" (caller should treat as all-affected). Empty Optional means no enclosing pom found.
     */
    static Optional<String> findOwningModule(Path repoRoot, String relFilePath) {
        Path file = repoRoot.resolve(relFilePath).normalize();
        Path dir = Files.isDirectory(file) ? file : file.getParent();
        if (dir == null) return Optional.empty();
        while (dir != null && dir.startsWith(repoRoot)) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                String rel = repoRoot.relativize(dir).toString().replace('\\', '/');
                return Optional.of(rel);
            }
            if (dir.equals(repoRoot)) break;
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /**
     * Build a {@code module → {modules that depend on it}} map by parsing every
     * {@code pom.xml} under {@code repoRoot}. Edges are based on intra-project
     * {@code <dependency>} entries (groupId+artifactId match against another module's pom).
     * {@code <dependencyManagement>} entries are ignored (they pin versions, not edges).
     */
    static Map<String, Set<String>> buildDownstreamGraph(Path repoRoot) {
        // (a) discover all module poms under root → relPath, gav
        List<ModulePom> modules = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().equals("pom.xml")
                            && !isInsideTargetOrHidden(repoRoot, p))
                .forEach(p -> {
                    String rel = repoRoot.relativize(p.getParent()).toString().replace('\\', '/');
                    parseGav(p).ifPresent(gav -> modules.add(new ModulePom(rel, gav, p)));
                });
        } catch (IOException e) {
            LOG.warn("[reactor-pruning] walk failed under {}: {}", repoRoot, e.toString());
            return Map.of();
        }

        // (b) coord → relPath lookup for resolving dependency edges
        Map<String, String> coordToModule = new HashMap<>();
        for (ModulePom m : modules) {
            coordToModule.put(m.gav.gaKey(), m.relPath);
        }

        // (c) for each module, parse its <dependencies> → reverse edges
        Map<String, Set<String>> downstream = new HashMap<>();
        for (ModulePom m : modules) {
            for (Gav dep : parseDependencies(m.pomPath, m.gav)) {
                String depModule = coordToModule.get(dep.gaKey());
                if (depModule != null && !depModule.equals(m.relPath)) {
                    downstream.computeIfAbsent(depModule, k -> new LinkedHashSet<>()).add(m.relPath);
                }
            }
        }
        return downstream;
    }

    private static boolean isInsideTargetOrHidden(Path repoRoot, Path pom) {
        for (Path part : repoRoot.relativize(pom)) {
            String n = part.toString();
            if (n.equals("target") || (n.startsWith(".") && !n.equals("."))) return true;
        }
        return false;
    }

    /** Parse the GAV (groupId, artifactId, version) of a single pom, inheriting from {@code <parent>}. */
    static Optional<Gav> parseGav(Path pomPath) {
        try {
            Document doc = parseXml(pomPath);
            Element root = doc.getDocumentElement();
            String groupId    = childText(root, "groupId");
            String artifactId = childText(root, "artifactId");
            String version    = childText(root, "version");
            // Inherit groupId/version from <parent> when absent on the child
            Element parent = childElement(root, "parent");
            if (parent != null) {
                if (groupId == null) groupId = childText(parent, "groupId");
                if (version == null) version = childText(parent, "version");
            }
            if (artifactId == null) return Optional.empty();
            return Optional.of(new Gav(groupId, artifactId, version));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Collect intra-project candidate dependencies (real {@code <dependencies>} only). */
    static List<Gav> parseDependencies(Path pomPath, Gav owner) {
        List<Gav> out = new ArrayList<>();
        try {
            Document doc = parseXml(pomPath);
            Element root = doc.getDocumentElement();
            // Walk <dependencies> at top level only — skip <dependencyManagement>
            for (Element deps : directChildren(root, "dependencies")) {
                for (Element dep : directChildren(deps, "dependency")) {
                    String g = childText(dep, "groupId");
                    String a = childText(dep, "artifactId");
                    if (g == null) g = owner.groupId; // inherit from owner module if omitted
                    if (a == null) continue;
                    out.add(new Gav(g, a, null));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Document parseXml(Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        try (var in = Files.newInputStream(p)) {
            return b.parse(in);
        }
    }

    private static String childText(Element parent, String name) {
        Element e = childElement(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }

    private static Element childElement(Element parent, String name) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(name)) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && n.getNodeName().equals(name)) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** Maven coord (groupId, artifactId, version). Equality uses GA only. */
    record Gav(String groupId, String artifactId, String version) {
        String gaKey() { return (groupId == null ? "" : groupId) + ":" + artifactId; }
    }

    private record ModulePom(String relPath, Gav gav, Path pomPath) {}
}
