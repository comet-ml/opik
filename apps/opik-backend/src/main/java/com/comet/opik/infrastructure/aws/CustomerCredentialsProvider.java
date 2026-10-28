package com.comet.opik.infrastructure.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import org.glassfish.jersey.client.ClientProperties;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.awssdk.utils.cache.CachedSupplier;
import software.amazon.awssdk.utils.cache.RefreshResult;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Credentials served by the SageMaker Partner AI App customer-credentials endpoint, cached until shortly before they
 * expire.
 */
class CustomerCredentialsProvider implements AwsCredentialsProvider, SdkAutoCloseable {

    // Same margins as the SDK's container provider: refetch in the background ahead of expiry, block only once stale
    private static final Duration STALE_BEFORE_EXPIRY = Duration.ofMinutes(1);
    private static final Duration PREFETCH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    private final URI endpoint;

    /** Bounds connecting and then each read of the response, in milliseconds. */
    private final int timeoutMillis;

    private final Client client;
    private final CachedSupplier<AwsCredentials> cache;

    CustomerCredentialsProvider(@NonNull String endpoint, @NonNull Duration timeout, @NonNull Client client) {
        this.endpoint = URI.create(endpoint.trim());
        this.timeoutMillis = Math.toIntExact(timeout.toMillis());
        this.client = client;
        this.cache = CachedSupplier.builder(this::fetch).cachedValueName("CustomerCredentialsProvider").build();
    }

    @Override
    public AwsCredentials resolveCredentials() {
        return cache.get();
    }

    @Override
    public void close() {
        cache.close();
    }

    private RefreshResult<AwsCredentials> fetch() {
        JsonNode json;
        try (Response response = client.target(endpoint).request()
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .property(ClientProperties.CONNECT_TIMEOUT, timeoutMillis)
                .property(ClientProperties.READ_TIMEOUT, timeoutMillis)
                .get()) {
            if (response.getStatus() != 200) {
                // Per AWS, the endpoint answers 5xx while the customer credentials aren't available yet and 4xx on a
                // token problem, both to be retried with backoff. The IOException cause makes the publisher retry
                // them like any other transient I/O failure.
                var message = "Customer credentials endpoint returned HTTP '%s'".formatted(response.getStatus());
                throw SdkClientException.create(message, new IOException(message));
            }
            json = readJson(response);
        } catch (ProcessingException exception) {
            // its I/O cause, a timeout included, makes the publisher retry it
            throw SdkClientException.create("Failed to call the customer credentials endpoint", exception);
        }

        try {
            Instant expiration = Instant.parse(required(json, "Expiration"));
            AwsCredentials credentials = AwsSessionCredentials.builder()
                    .accessKeyId(required(json, "AccessKeyId"))
                    .secretAccessKey(required(json, "SecretAccessKey"))
                    .sessionToken(required(json, "SessionToken"))
                    .expirationTime(expiration)
                    .build();
            return RefreshResult.builder(credentials)
                    .staleTime(expiration.minus(STALE_BEFORE_EXPIRY))
                    .prefetchTime(expiration.minus(PREFETCH_BEFORE_EXPIRY))
                    .build();
        } catch (DateTimeParseException exception) {
            throw SdkClientException.create("Customer credentials endpoint returned an unreadable response",
                    exception);
        }
    }

    /** The response parsed as it is read; a body that isn't JSON is an unreadable response, not an I/O failure. */
    private static JsonNode readJson(Response response) {
        try {
            return response.readEntity(JsonNode.class);
        } catch (ProcessingException exception) {
            if (exception.getCause() instanceof JsonProcessingException) {
                throw SdkClientException.create("Customer credentials endpoint returned an unreadable response",
                        exception);
            }
            throw exception;
        }
    }

    private static String required(JsonNode json, String field) {
        if (!json.hasNonNull(field) || json.get(field).asText().isBlank()) {
            throw SdkClientException.create("Customer credentials endpoint response has no '%s'".formatted(field));
        }
        return json.get(field).asText();
    }
}
