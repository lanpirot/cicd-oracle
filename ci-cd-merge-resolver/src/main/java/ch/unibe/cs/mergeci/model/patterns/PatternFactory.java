package ch.unibe.cs.mergeci.model.patterns;

import java.util.*;
import java.util.function.Supplier;

/**
 * Factory for creating pattern instances from pattern names.
 * Supports atomic patterns (OURS, THEIRS, BASE, EMPTY) and compound patterns (OURSTHEIRS, etc.).
 *
 * Compound patterns are parsed into atomic components and randomly shuffled to explore
 * different resolution orders.
 */
public class PatternFactory {

    private static final Map<String, Supplier<IPattern>> ATOMIC_PATTERNS = Map.of(
        "OURS", OursPattern::new,
        "THEIRS", TheirsPattern::new,
        "BASE", BasePattern::new,
        "EMPTY", EmptyPattern::new
    );

    /**
     * Create a pattern instance from a pattern name.
     *
     * @param patternName Pattern name (e.g., "OURS", "OURSTHEIRS", "OURSTHEIRSBASE")
     * @param random Random instance for shuffling compound patterns
     * @return Pattern instance (atomic or compound with randomized order)
     * @throws IllegalArgumentException if pattern name is invalid or EMPTY appears in compound
     */
    public static IPattern fromName(String patternName, Random random) {
        if (patternName == null || patternName.isEmpty()) {
            throw new IllegalArgumentException("Pattern name cannot be null or empty");
        }

        // Single atomic pattern
        if (ATOMIC_PATTERNS.containsKey(patternName)) {
            return ATOMIC_PATTERNS.get(patternName).get();
        }

        // EMPTY cannot be part of compound patterns
        if (patternName.contains("EMPTY")) {
            throw new IllegalArgumentException("EMPTY cannot be part of compound pattern: " + patternName);
        }

        // NON should have been replaced by StrategySelector before reaching here
        if (patternName.contains("NON")) {
            throw new IllegalArgumentException("NON is not a valid pattern (should be replaced by StrategySelector): " + patternName);
        }

        // Parse compound pattern
        List<IPattern> components = parseCompound(patternName);

        // Single component doesn't need CompoundPattern wrapper
        if (components.size() == 1) {
            return components.get(0);
        }

        // Randomly shuffle components to explore different orders
        Collections.shuffle(components, random);

        return new CompoundPattern(components);
    }

    /**
     * Parse a compound pattern name into atomic pattern instances.
     * Uses greedy matching to handle pattern names like "THEIRS" that contain "THEIRS".
     *
     * @param patternName Compound pattern name (e.g., "OURSTHEIRS", "THEIRSBASE")
     * @return List of atomic pattern instances (not yet shuffled)
     * @throws IllegalArgumentException if pattern name cannot be parsed
     */
    private static List<IPattern> parseCompound(String patternName) {
        List<IPattern> components = new ArrayList<>();
        String remaining = patternName;

        // Greedy matching: try longest pattern names first to avoid ambiguity
        // "THEIRS" (6 chars) must be tried before "OURS" (4 chars) to avoid matching "THE" + "IRS"
        List<String> atomicNames = List.of("THEIRS", "OURS", "BASE"); // Sorted by length descending

        while (!remaining.isEmpty()) {
            boolean matched = false;

            for (String atomicName : atomicNames) {
                if (remaining.startsWith(atomicName)) {
                    components.add(ATOMIC_PATTERNS.get(atomicName).get());
                    remaining = remaining.substring(atomicName.length());
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                throw new IllegalArgumentException("Cannot parse pattern name: " + patternName +
                    " (remaining: " + remaining + ")");
            }
        }

        if (components.isEmpty()) {
            throw new IllegalArgumentException("Pattern name resulted in no components: " + patternName);
        }

        return components;
    }

    /**
     * Check if a pattern name is atomic (single pattern, not compound).
     */
    public static boolean isAtomic(String patternName) {
        return ATOMIC_PATTERNS.containsKey(patternName);
    }

    /**
     * Get all supported atomic pattern names.
     */
    public static Set<String> getAtomicPatternNames() {
        return ATOMIC_PATTERNS.keySet();
    }
}
