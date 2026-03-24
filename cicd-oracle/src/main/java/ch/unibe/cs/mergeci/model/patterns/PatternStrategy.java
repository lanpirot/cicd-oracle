package ch.unibe.cs.mergeci.model.patterns;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a resolution strategy with weighted sub-patterns.
 * Example: "50.0*NON_50.0*OURS" means 50% of chunks use NON, 50% use OURS.
 */
@Getter
public class PatternStrategy {
    private final double weight;
    private final List<SubPattern> subPatterns;

    public PatternStrategy(double weight, List<SubPattern> subPatterns) {
        this.weight = weight;
        this.subPatterns = subPatterns;
    }

    /**
     * Parse a strategy string like "45.19*(100.0*OURS)".
     *
     * @param strategyString Strategy in format "weight*(subpattern1_subpattern2_...)"
     * @return Parsed PatternStrategy
     */
    public static PatternStrategy parse(String strategyString) {
        // Format: "45.19*(100.0*OURS)" or "11.22*(50.0*NON_50.0*OURS)"
        int parenStart = strategyString.indexOf('(');
        int parenEnd = strategyString.lastIndexOf(')');

        if (parenStart == -1 || parenEnd == -1) {
            throw new IllegalArgumentException("Invalid strategy format: " + strategyString);
        }

        // Extract outer weight
        String weightStr = strategyString.substring(0, parenStart).replace("*", "").trim();
        double weight = Double.parseDouble(weightStr);

        // Extract sub-patterns
        String subPatternsStr = strategyString.substring(parenStart + 1, parenEnd);
        String[] subPatternTokens = subPatternsStr.split("_");

        List<SubPattern> subPatterns = new ArrayList<>();
        for (String token : subPatternTokens) {
            subPatterns.add(SubPattern.parse(token));
        }

        return new PatternStrategy(weight, subPatterns);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%.2f*(", weight));
        for (int i = 0; i < subPatterns.size(); i++) {
            if (i > 0) sb.append("_");
            sb.append(subPatterns.get(i));
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Represents a sub-pattern within a strategy.
     * Example: "50.0*OURS" means 50% of chunks should use OURS pattern.
     */
    @Getter
    public static class SubPattern {
        private final double percentage;
        private final String pattern;

        public SubPattern(double percentage, String pattern) {
            this.percentage = percentage;
            this.pattern = pattern;
        }

        /**
         * Parse a sub-pattern string like "50.0*OURS".
         */
        public static SubPattern parse(String subPatternString) {
            // Format: "50.0*OURS" or "100.0*THEIRS"
            String[] parts = subPatternString.split("\\*");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid sub-pattern format: " + subPatternString);
            }

            double percentage = Double.parseDouble(parts[0].trim());
            String pattern = parts[1].trim();

            return new SubPattern(percentage, pattern);
        }

        @Override
        public String toString() {
            return String.format("%.1f*%s", percentage, pattern);
        }
    }
}
