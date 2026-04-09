package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes an effective pattern assignment key for variant deduplication.
 * <p>
 * MANUAL-pinned chunks include the version number so that a manual text
 * change (version bump) produces a distinct key and allows re-testing.
 * Non-manual chunks use the pattern name directly.
 */
public class VariantDedup {

    /**
     * Compute the effective pattern assignment for dedup purposes.
     *
     * @param variant        the variant (with patterns already applied, including manual pins)
     * @param manualTexts    map of global chunk index → manual text (non-null entries = manual pin)
     * @param manualVersions map of global chunk index → version number
     * @return ordered list of effective pattern names (one per conflict chunk)
     */
    public static List<String> computeEffectiveAssignment(VariantProject variant,
                                                           Map<Integer, String> manualTexts,
                                                           Map<Integer, Integer> manualVersions) {
        List<String> effective = new ArrayList<>();
        int globalIdx = 0;
        for (ConflictFile cf : variant.getClasses()) {
            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    if (manualTexts.containsKey(globalIdx)) {
                        int ver = manualVersions.getOrDefault(globalIdx, 0);
                        effective.add("MANUAL_v" + ver);
                    } else {
                        effective.add(cb.getPattern() != null ? cb.getPattern().name() : "?");
                    }
                    globalIdx++;
                }
            }
        }
        return effective;
    }
}
