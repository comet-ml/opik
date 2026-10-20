package com.comet.opik.infrastructure.aws;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.awssdk.utils.cache.CachedSupplier;
import software.amazon.awssdk.utils.cache.RefreshResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final Duration timeout;
    private final HttpClient httpClient;
    private final CachedSupplier<AwsCredentials> cache;

    CustomerCredentialsProvider(@NonNull String endpoint, @NonNull Duration timeout) {
        this.endpoint = URI.create(endpoint.trim());
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
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
        HttpResponse<String> response;
        try {
            response = httpClient.send(HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw SdkClientException.create("Failed to call the customer credentials endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw SdkClientException.create("Interrupted while calling the customer credentials endpoint", exception);
        }
        if (response.statusCode() != 200) {
            throw SdkClientException.create(
                    "Customer credentials endpoint returned HTTP '%s'".formatted(response.statusCode()));
        }

        try {
            JsonNode json = JsonUtils.getJsonNodeFromString(response.body());
            Instant expiration = Instant.parse(required(json, "Expiration"));
            // SessionToken is the credential_process format; Token is the container format. Accept both until AWS
            // confirms which one this endpoint returns.
            String sessionToken = json.hasNonNull("SessionToken")
                    ? json.get("SessionToken").asText()
                    : required(json, "Token");
            AwsCredentials credentials = AwsSessionCredentials.builder()
                    .accessKeyId(required(json, "AccessKeyId"))
                    .secretAccessKey(required(json, "SecretAccessKey"))
                    .sessionToken(sessionToken)
                    .expirationTime(expiration)
                    .build();
            return RefreshResult.builder(credentials)
                    .staleTime(expiration.minus(STALE_BEFORE_EXPIRY))
                    .prefetchTime(expiration.minus(PREFETCH_BEFORE_EXPIRY))
                    .build();
        } catch (UncheckedIOException | DateTimeParseException exception) {
            throw SdkClientException.create("Customer credentials endpoint returned an unreadable response",
                    exception);
        }
    }

    private static String required(JsonNode json, String field) {
        if (!json.hasNonNull(field) || json.get(field).asText().isBlank()) {
            throw SdkClientException.create("Customer credentials endpoint response has no '%s'".formatted(field));
        }
        return json.get(field).asText();
    }
}
