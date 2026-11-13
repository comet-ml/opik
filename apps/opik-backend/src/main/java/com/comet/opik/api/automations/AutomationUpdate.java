package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

/** A partial update: null leaves the stored value alone, as everywhere on this resource. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AutomationUpdate(
        @Nullable @Size(max = 150) String name,
        @Nullable Boolean enabled,
        @Nullable @Size(min = 1, max = Automation.MAX_TRIGGERS) @Valid List<Automation.@NotNull Trigger> triggers,
        @Nullable @Size(min = 1, max = Automation.MAX_STEPS) @Valid List<Automation.@NotNull Step> steps,
        @Nullable @Valid Automation.Limits limits) {
}
