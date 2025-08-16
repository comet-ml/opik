package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FeedbackScoresConfig {

    @JsonProperty
    private boolean writeToAuthored = false;
}
