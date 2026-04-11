package ch.unibe.cs.mergeci.runner;

/**
 * Controls when the {@link VariantExecutionEngine} variant loop should stop.
 *
 * <p>Pipeline implementation: deadline-based (remaining seconds decrease over time).
 * Plugin implementation: delegates to OracleSession (pause/resume/cancel).
 */
public interface VariantStopCondition {

    /** Should the loop stop immediately (hard cancel)? */
    boolean isCancelled();

    /** Block until resumed or cancelled. Default: no-op (pipeline never pauses). */
    default void waitIfPaused() throws InterruptedException {}

    /** Is the loop paused or cancelled? Workers check this to bail out early. */
    default boolean isStopped() {
        return isCancelled();
    }

    /**
     * Remaining seconds for this variant's Maven invocation.
     * Return {@code -1} for no global deadline (plugin uses fixed per-variant timeout).
     */
    default int remainingSeconds() {
        return -1;
    }
}
