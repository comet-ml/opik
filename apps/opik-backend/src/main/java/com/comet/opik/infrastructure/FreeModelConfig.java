package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
@ToString
public class FreeModelConfig {

    /**
     * Fixed UUID for the free model provider. This is a virtual provider that doesn't exist in the database.
     */
    public static final UUID FREE_MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Fixed model name for the free model provider. This must remain constant because automation rules
     * store this model name in their configuration. Changing it would break existing rules.
     */
    public static final String FREE_MODEL = "opik-free-model";

    @JsonProperty
    private boolean enabled = false;

    @JsonProperty
    private String actualModel = "";

    @JsonProperty
    private String spanProvider = "";

    @JsonProperty
    private String baseUrl = "";

    @JsonProperty
    @ToString.Exclude
    private String apiKey = "";

    /**
     * Returns the fixed model name. This is not configurable to ensure backward compatibility
     * with stored automation rules.
     */
    public String getModel() {
        return FREE_MODEL;
    }
}
