package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

/**
 * Lightweight DTO for dataset item comparison containing only ID, hash, and tags.
 * Used for efficient diff calculation without loading full item data.
 *
 * For versioned items: itemId = draft_item_id (links to original draft item)
 * For draft items: itemId = id (the actual item ID)
 *
 * dataHash covers data and metadata fields only.
 * tags are stored and compared as an unordered set.
 */
@Builder(toBuilder = true)
public record DatasetItemIdAndHash(
        @NonNull UUID itemId,
        long dataHash,
        Set<String> tags) {
}
