package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WorkspaceSettings {

    @Valid @NotNull @JsonProperty
    private double maxSizeToAllowSorting;

    @Valid @NotNull @JsonProperty
    private double maxProjectSizeToAllowSorting;

}
