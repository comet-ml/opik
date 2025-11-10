package com.comet.opik.domain;

import java.util.UUID;

public record PromptVersionInfo(UUID id, String commit, String promptName) {
}
