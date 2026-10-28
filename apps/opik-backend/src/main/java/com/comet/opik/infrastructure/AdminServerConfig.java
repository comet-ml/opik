package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Whether the backend runs Dropwizard's admin server, whose connectors are under {@code server.adminConnectors}. When
 * disabled, nothing listens on the admin port, on any interface; health checks stay on the application port.
 */
@Data
public class AdminServerConfig {

    @NotNull @JsonProperty
    private Boolean enabled;
}
