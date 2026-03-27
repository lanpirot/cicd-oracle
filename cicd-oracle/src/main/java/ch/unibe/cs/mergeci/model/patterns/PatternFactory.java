package ch.unibe.cs.mergeci.model.patterns;

import java.util.*;
import java.util.function.Supplier;

/**
 * Factory for creating pattern instances from pattern names.
 * Supports atomic patterns (OURS, THEIRS, BASE, EMPTY) and compound patterns (OURSTHEIRS, etc.).
 * <p>
 * Compound pattern names encode a specific ordering of atomic components.
 * Use {@link #sampleOrdering(String, Random)} to randomly pick one ordering before calling
 * {@link #fromName(String)}, so that deduplication in StrategySelector operates on the
 * actual ordering rather than the unordered pattern name from the CSV.
 */
public class PatternFactory {

    private static final Map<String, Supplier<IPattern>> ATOMIC_PATTERNS = Map.of(
        "OURS", OursPattern::new,
        "THEIRS", TheirsPattern::new,
        "BASE", BasePattern::new,
        "EMPTY", EmptyPattern::new
    );

    /**
     * Create a pattern instance from an (already-ordered) pattern name.
     * The name encodes the exact component order — no shuffling is applied.
     * Call {@link #sampleOrdering(String, Random)} first to pick a random ordering.
     *
     * @param patternName Pattern name encoding specific order (e.g., "THEIRSOURS")
     * @return Pattern instance (atomic or compound in the specified order)
     * @throws IllegalArgumentException if pattern name is invalid or EMPTY appears in compound
     */
    public static IPattern fromName(String patternName) {
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

        // Parse compound pattern — order in the name is preserved as-is
        List<IPattern> components = parseCompound(patternName);

        // Single component doesn't need CompoundPattern wrapper
        if (components.size() == 1) {
            return components.getFirst();
        }

        return new CompoundPattern(components);
    }

    /**
     * Sample a random ordering for a compound pattern name.
     * For atomic patterns the name is returned unchanged (only one ordering exists).
     * For compound patterns, the atomic components are randomly permuted and
     * the result is returned as a new name string (e.g., "OURSTHEIRS" → "THEIRSOURS").
     * <p>
     * The returned name can be used as a deduplication key and passed to {@link #fromName(String)}.
     *
     * @param patternName Pattern name from the heuristics CSV (e.g., "OURSTHEIRS")
     * @param random      Random instance for permutation selection
     * @return A specific ordering of the pattern's components as a name string
     */
    public static String sampleOrdering(String patternName, Random random) {
        if (ATOMIC_PATTERNS.containsKey(patternName)) {
            return patternName;
        }
        List<String> components = parseToAtomicNames(patternName);
        Collections.shuffle(components, random);
        return String.join("", components);
    }

    /**
     * Parse a compound pattern name into atomic pattern instances in the order given.
     */
    private static List<IPattern> parseCompound(String patternName) {
        return parseToAtomicNames(patternName).stream()
                .map(name -> ATOMIC_PATTERNS.get(name).get())
                .toList();
    }

    /**
     * Parse a compound pattern name into its atomic component names in order.
     * Uses greedy matching to avoid ambiguity (e.g., "THEIRS" before "OURS").
     */
    private static List<String> parseToAtomicNames(String patternName) {
        List<String> names = new ArrayList<>();
        // Accept both colon-separated (THEIRS:OURS) and concatenated (THEIRSOURS) notation.
        String remaining = patternName.replace(":", "");
        // Sorted by length descending so "THEIRS" is tried before "OURS"
        List<String> atomicNames = List.of("THEIRS", "OURS", "BASE");

        while (!remaining.isEmpty()) {
            boolean matched = false;
            for (String atomicName : atomicNames) {
                if (remaining.startsWith(atomicName)) {
                    names.add(atomicName);
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

        if (names.isEmpty()) {
            throw new IllegalArgumentException("Pattern name resulted in no components: " + patternName);
        }

        return names;
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
