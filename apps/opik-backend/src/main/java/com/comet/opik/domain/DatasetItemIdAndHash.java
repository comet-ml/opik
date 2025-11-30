package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * Lightweight DTO for dataset item comparison containing only ID and hash.
 * Used for efficient diff calculation without loading full item data.
 *
 * For versioned items: itemId = draft_item_id (links to original draft item)
 * For draft items: itemId = id (the actual item ID)
 */
@Builder(toBuilder = true)
public record DatasetItemIdAndHash(
        @NonNull UUID itemId,
        long dataHash) {
}
