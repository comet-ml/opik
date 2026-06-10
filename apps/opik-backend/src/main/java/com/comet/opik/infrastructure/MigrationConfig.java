package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared configuration for V1 → V2 entity project migration jobs (experiments, datasets,
 * optimizations, etc.). Centralised so all migration jobs share the same excluded workspaces.
 */
@Data
public class MigrationConfig {

    private @NotNull Set<@NotBlank String> excludedWorkspaceIds = Set.of();

    @JsonSetter
    public void setExcludedWorkspaceIds(String excludedWorkspaceIds) {
        this.excludedWorkspaceIds = Optional.ofNullable(excludedWorkspaceIds)
                .filter(StringUtils::isNotBlank)
                .map(commaSeparated -> Arrays.stream(commaSeparated.split(","))
                        .map(String::strip)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.collectingAndThen(
                                Collectors.toCollection(LinkedHashSet::new),
                                Collections::unmodifiableSet)))
                .orElse(Set.of());
    }
}
