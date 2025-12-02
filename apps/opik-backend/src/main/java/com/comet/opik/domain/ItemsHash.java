package com.comet.opik.domain;

import lombok.Builder;

/**
 * Represents a hash aggregate of dataset items for efficient change detection.
 * Uses dual XOR hashing to detect both item additions/deletions and data modifications.
 *
 * @param idHash XOR of all item IDs (detects item additions/deletions)
 * @param dataHash XOR of all data hashes (detects data modifications)
 */
@Builder(toBuilder = true)
public record ItemsHash(long idHash, long dataHash) {
}
