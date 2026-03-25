package ch.unibe.cs.mergeci.runner;

import java.util.List;
import java.util.Optional;

/**
 * Interface for variant pattern generation.
 * Produces per-chunk pattern assignments (one string pattern name per conflict chunk)
 * until the generator's supply is exhausted or the time budget is reached.
 *
 * <p>Implementations: {@link MLARGenerator} (autoregressive ML model via subprocess).
 */
public interface IVariantGenerator {

    /**
     * Return the next variant assignment (one pattern name per conflict chunk, in order),
     * or empty when the generator is exhausted.
     */
    Optional<List<String>> nextVariant();
}
