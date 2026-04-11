package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.model.VariantProject;

/**
 * Callbacks invoked by {@link VariantExecutionEngine} at each lifecycle point.
 * Implementations must be thread-safe — multiple variants call these concurrently.
 *
 * <p>The pipeline populates batch result maps; the plugin dispatches to the Swing EDT.
 */
public interface VariantLifecycleListener {

    /**
     * Called before a variant is submitted for execution.
     * Return {@code false} to skip this variant (e.g. dedup).
     */
    default boolean beforeSubmit(VariantProject variant, int variantIndex) {
        return true;
    }

    /**
     * Transform a variant before build — e.g. apply manual pin overlays.
     * Called on the coordinator thread before the variant is handed to a worker.
     */
    default VariantProject transformVariant(VariantProject variant) {
        return variant;
    }

    /** Called when the number of in-flight build workers changes. */
    default void onInFlightChanged(int count) {}

    /** Called after a variant completes (success or failure). */
    void onVariantComplete(VariantOutcome outcome);

    /** Called when the entire run finishes. {@code exhausted} is true if the generator ran out. */
    default void onRunFinished(boolean exhausted) {}
}
