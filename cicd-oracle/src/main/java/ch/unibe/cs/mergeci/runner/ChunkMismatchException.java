package ch.unibe.cs.mergeci.runner;

/**
 * Thrown when a variant generator produces an assignment whose length does not match
 * the number of conflict chunks in the merge.  This is unrecoverable for the given
 * merge — all subsequent assignments from the same generator will have the same
 * wrong length — so the merge should be skipped entirely.
 */
public class ChunkMismatchException extends RuntimeException {
    public ChunkMismatchException(String message) {
        super(message);
    }
}
