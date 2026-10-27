package com.comet.opik.api;

import com.comet.opik.infrastructure.net.LoggableUrl;
import com.comet.opik.utils.EncryptionDeserializer;
import com.comet.opik.utils.LoggableMaps;
import com.comet.opik.utils.MaskedSecretTokenSerializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Webhook(
        @JsonView({
                Alert.View.Public.class, Alert.View.Write.class}) UUID id,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) String name,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @NotBlank String url,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) @Size(max = 250) @JsonDeserialize(using = EncryptionDeserializer.class) @JsonSerialize(using = MaskedSecretTokenSerializer.class) String secretToken,

        @JsonView({Alert.View.Public.class,
                Alert.View.Write.class}) Map<@NotBlank String, @NotBlank String> headers,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,

        @JsonView({
                Alert.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    /**
     * Without the secret token, with only the names of the headers, whose values are never logged, and with the URL's
     * scheme, host and port only, as its path can hold a token.
     */
    @Override
    public String toString() {
        return """
                Webhook[id=%s, name=%s, url=%s, secretToken=[REDACTED], headers=%s, createdAt=%s, createdBy=%s, \
                lastUpdatedAt=%s, lastUpdatedBy=%s]""".formatted(id, name, LoggableUrl.of(url),
                LoggableMaps.keys(headers), createdAt,
                createdBy, lastUpdatedAt, lastUpdatedBy);
    }

    /** Hand-declared so that Lombok's toString, which would print the secret token and the header values, isn't. */
    public static class WebhookBuilder {
        @Override
        public String toString() {
            return "WebhookBuilder[id=%s, name=%s, headers=%s]".formatted(id, name, LoggableMaps.keys(headers));
        }
    }
}
