package org.example.cicdmergeoracle.cicdMergeTool.model;

/**
 * Identifies a single conflict chunk within a merge.
 *
 * @param filePath          relative path of the conflicting file
 * @param indexWithinFile   zero-based index of this chunk among the file's conflict blocks
 */
public record ChunkKey(String filePath, int indexWithinFile) {}
