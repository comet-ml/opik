package com.comet.opik.api.resources.v1.events.tools;

import lombok.NonNull;

public record EntityRef(@NonNull EntityType type, @NonNull String id) {
}