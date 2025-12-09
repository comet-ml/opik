package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
@ToString
public class BuiltinLlmProviderConfig {

    /**
     * Fixed UUID for the built-in provider. This is a virtual provider that doesn't exist in the database.
     */
    public static final UUID BUILTIN_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Fixed model name for the built-in provider. This must remain constant because automation rules
     * store this model name in their configuration. Changing it would break existing rules.
     */
    public static final String BUILTIN_MODEL = "opik-builtin-model";

    @JsonProperty
    private boolean enabled = false;

    @JsonProperty
    private String actualModel = "gpt-4o-mini";

    @JsonProperty
    private String spanProvider = "openai";

    @JsonProperty
    @ToString.Exclude
    private String apiKey = "";

    @JsonProperty
    private String baseUrl = "";

    /**
     * Returns the fixed model name. This is not configurable to ensure backward compatibility
     * with stored automation rules.
     */
    public String getModel() {
        return BUILTIN_MODEL;
    }
}
