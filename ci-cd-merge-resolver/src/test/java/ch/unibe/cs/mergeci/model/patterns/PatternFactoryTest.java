package ch.unibe.cs.mergeci.model.patterns;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PatternFactoryTest {

    @Test
    void testAtomicPatterns() {
        Random random = new Random(42);

        // Test all atomic patterns
        IPattern ours = PatternFactory.fromName("OURS", random);
        assertInstanceOf(OursPattern.class, ours);

        IPattern theirs = PatternFactory.fromName("THEIRS", random);
        assertInstanceOf(TheirsPattern.class, theirs);

        IPattern base = PatternFactory.fromName("BASE", random);
        assertInstanceOf(BasePattern.class, base);

        IPattern empty = PatternFactory.fromName("EMPTY", random);
        assertInstanceOf(EmptyPattern.class, empty);
    }

    @Test
    void testSimpleCompoundPattern() {
        Random random = new Random(42);

        // Test OURSTHEIRS
        IPattern pattern = PatternFactory.fromName("OURSTHEIRS", random);
        assertInstanceOf(CompoundPattern.class, pattern);

        CompoundPattern compound = (CompoundPattern) pattern;
        assertEquals(2, compound.getAtomicPatterns().size());

        // Verify both OURS and THEIRS are present (order may vary due to shuffle)
        Set<Class<?>> patternClasses = new HashSet<>();
        for (IPattern p : compound.getAtomicPatterns()) {
            patternClasses.add(p.getClass());
        }
        assertTrue(patternClasses.contains(OursPattern.class));
        assertTrue(patternClasses.contains(TheirsPattern.class));
    }

    @Test
    void testThreeComponentCompound() {
        Random random = new Random(123);

        // Test OURSTHEIRSBASE
        IPattern pattern = PatternFactory.fromName("OURSTHEIRSBASE", random);
        assertInstanceOf(CompoundPattern.class, pattern);

        CompoundPattern compound = (CompoundPattern) pattern;
        assertEquals(3, compound.getAtomicPatterns().size());

        // Verify all three are present
        Set<Class<?>> patternClasses = new HashSet<>();
        for (IPattern p : compound.getAtomicPatterns()) {
            patternClasses.add(p.getClass());
        }
        assertTrue(patternClasses.contains(OursPattern.class));
        assertTrue(patternClasses.contains(TheirsPattern.class));
        assertTrue(patternClasses.contains(BasePattern.class));
    }

    @Test
    void testCompoundWithDuplicates() {
        Random random = new Random(42);

        // Test OURSOURS (duplicate atomic pattern)
        IPattern pattern = PatternFactory.fromName("OURSOURS", random);
        assertInstanceOf(CompoundPattern.class, pattern);

        CompoundPattern compound = (CompoundPattern) pattern;
        assertEquals(2, compound.getAtomicPatterns().size());

        // Both should be OursPattern
        for (IPattern p : compound.getAtomicPatterns()) {
            assertInstanceOf(OursPattern.class, p);
        }
    }

    @Test
    void testTheirsBaseCompound() {
        Random random = new Random(42);

        // Test THEIRSBASE (different order than BASETHEIRS)
        IPattern pattern = PatternFactory.fromName("THEIRSBASE", random);
        assertInstanceOf(CompoundPattern.class, pattern);

        CompoundPattern compound = (CompoundPattern) pattern;
        assertEquals(2, compound.getAtomicPatterns().size());

        Set<Class<?>> patternClasses = new HashSet<>();
        for (IPattern p : compound.getAtomicPatterns()) {
            patternClasses.add(p.getClass());
        }
        assertTrue(patternClasses.contains(TheirsPattern.class));
        assertTrue(patternClasses.contains(BasePattern.class));
    }

    @Test
    void testRandomShuffling() {
        // Test that different random seeds produce different orders
        IPattern pattern1 = PatternFactory.fromName("OURSTHEIRS", new Random(1));
        IPattern pattern2 = PatternFactory.fromName("OURSTHEIRS", new Random(2));

        CompoundPattern compound1 = (CompoundPattern) pattern1;
        CompoundPattern compound2 = (CompoundPattern) pattern2;

        // Both should have same components
        assertEquals(2, compound1.getAtomicPatterns().size());
        assertEquals(2, compound2.getAtomicPatterns().size());

        // With different seeds, order MIGHT differ (not guaranteed, but likely)
        // We just verify the structure is correct
        assertNotNull(compound1.getAtomicPatterns().get(0));
        assertNotNull(compound2.getAtomicPatterns().get(0));
    }

    @Test
    void testInvalidPatternName() {
        Random random = new Random(42);

        // Test invalid pattern name
        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName("INVALID", random);
        });

        // Test null
        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName(null, random);
        });

        // Test empty string
        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName("", random);
        });
    }

    @Test
    void testEmptyInCompoundThrowsError() {
        Random random = new Random(42);

        // EMPTY cannot be part of compound pattern
        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName("EMPTYOURS", random);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName("OURSEMPTY", random);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName("OURSEMPTYTHEIRS", random);
        });
    }

    @Test
    void testNonInPatternThrowsError() {
        Random random = new Random(42);

        // NON should have been replaced by StrategySelector
        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName("NON", random);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            PatternFactory.fromName("OURSNON", random);
        });
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
        Random random = new Random(42);

        // Test various complex combinations
        String[] complexPatterns = {
            "OURSTHEIRSOURS",
            "THEIRSBASETHEIRS",
            "BASEOURSTHEIRS",
            "OURSOURSOURS",
            "THEIRSTHEIRSBASE"
        };

        for (String patternName : complexPatterns) {
            IPattern pattern = PatternFactory.fromName(patternName, random);
            assertInstanceOf(CompoundPattern.class, pattern);
            CompoundPattern compound = (CompoundPattern) pattern;
            assertTrue(compound.getAtomicPatterns().size() >= 2,
                "Pattern " + patternName + " should have multiple components");
        }
    }
}
