package ch.unibe.cs.mergeci.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CandidateDedupIndexTest {

    @TempDir
    Path tmpDir;

    @Test
    void fingerprintIsDeterministic() {
        Map<String, String> files = Map.of("foo.java", "class Foo {}");
        assertEquals(
                CandidateDedupIndex.computeFingerprint(files),
                CandidateDedupIndex.computeFingerprint(Map.of("foo.java", "class Foo {}")));
    }

    @Test
    void fingerprintDiffersOnContentChange() {
        assertNotEquals(
                CandidateDedupIndex.computeFingerprint(Map.of("f.java", "class A {}")),
                CandidateDedupIndex.computeFingerprint(Map.of("f.java", "class B {}")));
    }

    @Test
    void fingerprintDiffersOnPathChange() {
        assertNotEquals(
                CandidateDedupIndex.computeFingerprint(Map.of("a.java", "content")),
                CandidateDedupIndex.computeFingerprint(Map.of("b.java", "content")));
    }

    @Test
    void fingerprintIsOrderIndependent() {
        Map<String, String> m1 = new java.util.LinkedHashMap<>();
        m1.put("b.java", "B"); m1.put("a.java", "A");
        Map<String, String> m2 = new java.util.LinkedHashMap<>();
        m2.put("a.java", "A"); m2.put("b.java", "B");
        assertEquals(
                CandidateDedupIndex.computeFingerprint(m1),
                CandidateDedupIndex.computeFingerprint(m2));
    }

    @Test
    void lookupMissesOnEmptyIndex() throws IOException {
        CandidateDedupIndex idx = new CandidateDedupIndex(tmpDir);
        assertNull(idx.lookup("abc123", "somefingerprint"));
    }

    @Test
    void insertAndLookupRoundTrip() throws IOException {
        CandidateDedupIndex idx = new CandidateDedupIndex(tmpDir);
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("mode", "cache_parallel");
        node.put("variantIndex", 5);

        idx.insert("abc123", "fp1", node);

        ObjectNode retrieved = idx.lookup("abc123", "fp1");
        assertNotNull(retrieved);
        assertEquals("cache_parallel", retrieved.get("mode").asText());
        assertEquals(5, retrieved.get("variantIndex").asInt());
    }

    @Test
    void insertPersistsAcrossInstances() throws IOException {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("mode", "jdime");
        new CandidateDedupIndex(tmpDir).insert("abc123", "fp1", node);

        // Fresh index instance, same directory
        ObjectNode retrieved = new CandidateDedupIndex(tmpDir).lookup("abc123", "fp1");
        assertNotNull(retrieved);
        assertEquals("jdime", retrieved.get("mode").asText());
    }

    @Test
    void firstWriterWins() throws IOException {
        CandidateDedupIndex idx = new CandidateDedupIndex(tmpDir);
        ObjectNode node1 = new ObjectMapper().createObjectNode();
        node1.put("mode", "first");
        ObjectNode node2 = new ObjectMapper().createObjectNode();
        node2.put("mode", "second");

        idx.insert("abc123", "fp1", node1);
        idx.insert("abc123", "fp1", node2); // no-op

        assertEquals("first", idx.lookup("abc123", "fp1").get("mode").asText());
    }

    @Test
    void differentMergeCommitsAreIsolated() throws IOException {
        CandidateDedupIndex idx = new CandidateDedupIndex(tmpDir);
        ObjectNode n1 = new ObjectMapper().createObjectNode();
        n1.put("mode", "m1");
        ObjectNode n2 = new ObjectMapper().createObjectNode();
        n2.put("mode", "m2");

        idx.insert("commit_a", "fp", n1);
        idx.insert("commit_b", "fp", n2);

        assertEquals("m1", idx.lookup("commit_a", "fp").get("mode").asText());
        assertEquals("m2", idx.lookup("commit_b", "fp").get("mode").asText());
        assertNull(idx.lookup("commit_a", "otherFp"));
    }
}
