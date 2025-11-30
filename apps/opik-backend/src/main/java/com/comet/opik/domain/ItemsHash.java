package com.comet.opik.domain;

/**
 * Represents a hash aggregate of dataset items for efficient change detection.
 * Uses dual XOR hashing to detect both item additions/deletions and data modifications.
 *
 * @param idHash XOR of all item IDs (detects item additions/deletions)
 * @param dataHash XOR of all data hashes (detects data modifications)
 */
public record ItemsHash(Long idHash, Long dataHash) {
}
