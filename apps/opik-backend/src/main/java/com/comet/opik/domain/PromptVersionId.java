package com.comet.opik.domain;

import java.util.UUID;

public record PromptVersionId(UUID id, String commit) {
}
