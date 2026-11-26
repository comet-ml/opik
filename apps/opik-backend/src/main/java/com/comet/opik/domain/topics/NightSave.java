package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.UUID;

/**
 * What a nightly update writes to MySQL. {@code keep} is false when no topic stayed live: then the list is cleared
 * and every stored topic is in {@code retired}.
 */
@Builder(toBuilder = true)
record NightSave(boolean keep, @NonNull List<Topic> inserted, @NonNull List<Topic> updated,
        @NonNull List<UUID> retired, PrototypeVectors otherVectors) {
}
