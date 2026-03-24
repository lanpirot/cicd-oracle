package ch.unibe.cs.mergeci.model.patterns;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PatternFactoryTest {

    @Test
    void testAtomicPatterns() {
        assertInstanceOf(OursPattern.class,  PatternFactory.fromName("OURS"));
        assertInstanceOf(TheirsPattern.class, PatternFactory.fromName("THEIRS"));
        assertInstanceOf(BasePattern.class,  PatternFactory.fromName("BASE"));
        assertInstanceOf(EmptyPattern.class, PatternFactory.fromName("EMPTY"));
    }

    @Test
    void testSimpleCompoundPattern() {
        IPattern pattern = PatternFactory.fromName("OURSTHEIRS");
        assertInstanceOf(CompoundPattern.class, pattern);

        CompoundPattern compound = (CompoundPattern) pattern;
        assertEquals(2, compound.getAtomicPatterns().size());

        // Order is determined by the name: OURS first, then THEIRS
        assertInstanceOf(OursPattern.class,   compound.getAtomicPatterns().get(0));
        assertInstanceOf(TheirsPattern.class, compound.getAtomicPatterns().get(1));
    }

    @Test
    void testThreeComponentCompound() {
        IPattern pattern = PatternFactory.fromName("OURSTHEIRSBASE");
        assertInstanceOf(CompoundPattern.class, pattern);

        CompoundPattern compound = (CompoundPattern) pattern;
        assertEquals(3, compound.getAtomicPatterns().size());

        assertInstanceOf(OursPattern.class,   compound.getAtomicPatterns().get(0));
        assertInstanceOf(TheirsPattern.class, compound.getAtomicPatterns().get(1));
        assertInstanceOf(BasePattern.class,   compound.getAtomicPatterns().get(2));
    }

    @Test
    void testOrderingIsPreservedByName() {
        // "OURSTHEIRS" and "THEIRSOURS" must produce different orderings
        CompoundPattern oursFirst   = (CompoundPattern) PatternFactory.fromName("OURSTHEIRS");
        CompoundPattern theirsFirst = (CompoundPattern) PatternFactory.fromName("THEIRSOURS");

        assertInstanceOf(OursPattern.class,   oursFirst.getAtomicPatterns().get(0));
        assertInstanceOf(TheirsPattern.class, oursFirst.getAtomicPatterns().get(1));

        assertInstanceOf(TheirsPattern.class, theirsFirst.getAtomicPatterns().get(0));
        assertInstanceOf(OursPattern.class,   theirsFirst.getAtomicPatterns().get(1));
    }

    @Test
    void testSampleOrderingProducesAllOrderings() {
        // "OURSTHEIRS" has 2 orderings — sampleOrdering must be able to produce both
        Random random = new Random(0);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(PatternFactory.sampleOrdering("OURSTHEIRS", random));
        }
        assertEquals(Set.of("OURSTHEIRS", "THEIRSOURS"), seen);
    }

    @Test
    void testSampleOrderingThreeComponents() {
        // "OURSTHEIRSBASE" has 3! = 6 orderings
        Random random = new Random(0);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(PatternFactory.sampleOrdering("OURSTHEIRSBASE", random));
        }
        assertEquals(6, seen.size());
    }

    @Test
    void testSampleOrderingAtomicIsIdentity() {
        Random random = new Random(42);
        assertEquals("OURS",   PatternFactory.sampleOrdering("OURS",   random));
        assertEquals("THEIRS", PatternFactory.sampleOrdering("THEIRS", random));
        assertEquals("BASE",   PatternFactory.sampleOrdering("BASE",   random));
        assertEquals("EMPTY",  PatternFactory.sampleOrdering("EMPTY",  random));
    }

    @Test
    void testCompoundWithDuplicates() {
        IPattern pattern = PatternFactory.fromName("OURSOURS");
        assertInstanceOf(CompoundPattern.class, pattern);

        CompoundPattern compound = (CompoundPattern) pattern;
        assertEquals(2, compound.getAtomicPatterns().size());
        for (IPattern p : compound.getAtomicPatterns()) {
            assertInstanceOf(OursPattern.class, p);
        }
    }

    @Test
    void testTheirsBaseCompound() {
        CompoundPattern compound = (CompoundPattern) PatternFactory.fromName("THEIRSBASE");
        assertEquals(2, compound.getAtomicPatterns().size());

        assertInstanceOf(TheirsPattern.class, compound.getAtomicPatterns().get(0));
        assertInstanceOf(BasePattern.class,   compound.getAtomicPatterns().get(1));
    }

    @Test
    void testInvalidPatternName() {
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName(null));
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName(""));
    }

    @Test
    void testEmptyInCompoundThrowsError() {
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName("EMPTYOURS"));
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName("OURSEMPTY"));
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName("OURSEMPTYTHEIRS"));
    }

    @Test
    void testNonInPatternThrowsError() {
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName("NON"));
        assertThrows(IllegalArgumentException.class, () -> PatternFactory.fromName("OURSNON"));
    }

    @Test
    void testIsAtomic() {
        assertTrue(PatternFactory.isAtomic("OURS"));
        assertTrue(PatternFactory.isAtomic("THEIRS"));
        assertTrue(PatternFactory.isAtomic("BASE"));
        assertTrue(PatternFactory.isAtomic("EMPTY"));

        assertFalse(PatternFactory.isAtomic("OURSTHEIRS"));
        assertFalse(PatternFactory.isAtomic("THEIRSBASE"));
        assertFalse(PatternFactory.isAtomic("INVALID"));
    }

    @Test
    void testGetAtomicPatternNames() {
        Set<String> atomicNames = PatternFactory.getAtomicPatternNames();
        assertEquals(4, atomicNames.size());
        assertTrue(atomicNames.contains("OURS"));
        assertTrue(atomicNames.contains("THEIRS"));
        assertTrue(atomicNames.contains("BASE"));
        assertTrue(atomicNames.contains("EMPTY"));
    }

    @Test
    void testComplexCompoundPatterns() {
        String[] complexPatterns = {
            "OURSTHEIRSOURS",
            "THEIRSBASETHEIRS",
            "BASEOURSTHEIRS",
            "OURSOURSOURS",
            "THEIRSTHEIRSBASE"
        };

        for (String patternName : complexPatterns) {
            IPattern pattern = PatternFactory.fromName(patternName);
            assertInstanceOf(CompoundPattern.class, pattern);
            CompoundPattern compound = (CompoundPattern) pattern;
            assertTrue(compound.getAtomicPatterns().size() >= 2,
                "Pattern " + patternName + " should have multiple components");
        }
    }
}
