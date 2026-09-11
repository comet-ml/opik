package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Recipe describing how to fetch a short-lived bearer token before calling a custom LLM provider.
 * Stored as a single AES-GCM-encrypted JSON document in {@code llm_provider_api_key.auth_config};
 * a row without one behaves exactly as before (static api_key auth).
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderAuthConfig(
        @JsonView({
                ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @Schema(description = "Auth service URL the credentials are sent to", example = "https://developer.api.example.com/authentication/v1/token") String tokenUrl,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @Schema(description = "How credentials are sent: form body (default), JSON body, or basic auth (id/secret in an HTTP Basic header, remaining fields in the form body)") SendAs sendAs,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @Valid @Size(max = 20) @Schema(description = "Fields sent to the token URL. Values flagged as secret are write-only: they read back as the '"
                        + ProviderAuthConfig.SECRET_SENTINEL + "' sentinel") List<Credential> credentials,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @Size(max = 250) @Schema(description = "Field holding the token in the reply; dot-path for nested replies", example = "access_token") String tokenField,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @Size(max = 250) @Schema(description = "Field holding the token lifetime in seconds in the reply; dot-path for nested replies", example = "expires_in") String expiresField,
        @JsonView({ProviderApiKey.View.Public.class,
                ProviderApiKey.View.Write.class}) @Min(0) @Max(31_536_000) @Schema(description = "Lifetime in seconds assumed when the reply doesn't state one, capped at one year; 0 disables caching for such replies. A reply-stated lifetime always wins") Long fallbackTtlSeconds) {

    public static final String SECRET_SENTINEL = "__SECRET__";
    private static final ProviderAuthConfig EMPTY = ProviderAuthConfig.builder().build();

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Credential(
            @JsonView({
                    ProviderApiKey.View.Public.class,
                    ProviderApiKey.View.Write.class}) @NotBlank @Size(max = 250) String key,
            @JsonView({ProviderApiKey.View.Public.class,
                    ProviderApiKey.View.Write.class}) @Size(max = 2000) String value,
            @JsonView({ProviderApiKey.View.Public.class,
                    ProviderApiKey.View.Write.class}) @Schema(description = "Secret values are encrypted at rest and never read back; once true it cannot be unset") boolean secret) {

        @Override
        public String toString() {
            return "Credential{key='" + key + "', value='*******', secret=" + secret + '}';
        }

        // Hand-declared so Lombok skips its generated toString(), which would print the
        // plaintext value — reachable via toBuilder() in mask() and the sentinel merge
        public static class CredentialBuilder {
            @Override
            public String toString() {
                return "CredentialBuilder{key='" + key + "', value='*******', secret=" + secret + '}';
            }
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum SendAs {
        FORM("form"),
        JSON("json"),
        BASIC("basic"),
        ;

        @JsonValue
        private final String value;

        @JsonCreator
        public static SendAs fromString(String value) {
            return Arrays.stream(values())
                    .filter(sendAs -> sendAs.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown send_as '%s'".formatted(value)));
        }
    }

    /**
     * A literal empty object ({@code {}}) is the API convention for clearing the auth config on
     * update, mirroring how an empty headers map clears headers.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return equals(EMPTY);
    }

    /**
     * Copy safe to return from the API: secret values are replaced with {@link #SECRET_SENTINEL}.
     */
    public ProviderAuthConfig mask() {
        if (CollectionUtils.isEmpty(credentials)) {
            return this;
        }
        return toBuilder()
                .credentials(credentials.stream()
                        .map(credential -> credential.secret()
                                ? credential.toBuilder().value(SECRET_SENTINEL).build()
                                : credential)
                        .toList())
                .build();
    }

    @Override
    public String toString() {
        return "ProviderAuthConfig{" +
                "tokenUrl='" + tokenUrl + '\'' +
                ", sendAs=" + sendAs +
                ", credentials=" + credentials +
                ", tokenField='" + tokenField + '\'' +
                ", expiresField='" + expiresField + '\'' +
                ", fallbackTtlSeconds=" + fallbackTtlSeconds +
                '}';
    }
}
