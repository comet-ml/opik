package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Builder;
import lombok.NonNull;

/** At most one of {@code response} and {@code error}: a cancelled item has neither, because it never ran. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemResult(
        @NonNull String itemId,
        @JsonDeserialize(using = ItemResponse.Deserializer.class) ItemResponse response,
        ErrorBody error) {
}
