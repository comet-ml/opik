package com.comet.opik.domain;

import java.util.UUID;

public record ExperimentItemRef(UUID experimentId, UUID itemId) {
}
