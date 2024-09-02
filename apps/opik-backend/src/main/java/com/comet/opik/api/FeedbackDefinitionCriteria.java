package com.comet.opik.api;

import com.comet.opik.domain.FeedbackDefinitionModel;
import lombok.Builder;

@Builder(toBuilder = true)
public record FeedbackDefinitionCriteria(String name, FeedbackDefinitionModel.FeedbackType type) {
}
