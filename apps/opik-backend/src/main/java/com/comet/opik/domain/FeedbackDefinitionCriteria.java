package com.comet.opik.domain;

import lombok.Builder;

@Builder(toBuilder = true)
public record FeedbackDefinitionCriteria(String name, FeedbackDefinitionModel.FeedbackType type) {
}
