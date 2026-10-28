package com.comet.opik.utils;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * What carries configured headers, configuration or user-supplied URLs prints only the keys and the URLs' scheme, host
 * and port, never the values, the URL paths or the credentials.
 */
class LoggableMapsTest {

    private static final String SECRET = "secret-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(24));

    static Stream<Arguments> headersArePrintedByName() {
        BiFunction<Map<String, String>, String, String> providerApiKey = (headers, secret) -> ProviderApiKey
                .builder().id(UUID.randomUUID()).provider(LlmProvider.CUSTOM_LLM).apiKey(secret).headers(headers)
                .build().toString();
        BiFunction<Map<String, String>, String, String> providerApiKeyBuilder = (headers, secret) -> ProviderApiKey
                .builder().provider(LlmProvider.CUSTOM_LLM).apiKey(secret).headers(headers).toString();
        BiFunction<Map<String, String>, String, String> webhook = (headers, secret) -> Webhook.builder()
                .url("https://example.com/hook").secretToken(secret).headers(headers).build().toString();
        BiFunction<Map<String, String>, String, String> webhookBuilder = (headers, secret) -> Webhook.builder()
                .url("https://example.com/hook").secretToken(secret).headers(headers).toString();
        BiFunction<Map<String, String>, String, String> webhookEventBuilder = (headers, secret) -> WebhookEvent
                .builder().id(UUID.randomUUID().toString()).url("https://example.com/hook").secret(secret)
                .headers(headers).toString();
        BiFunction<Map<String, String>, String, String> clientApiConfig = (headers,
                secret) -> LlmProviderClientApiConfig.builder().apiKey(secret).headers(headers).build().toString();
        BiFunction<Map<String, String>, String, String> clientApiConfigBuilder = (headers,
                secret) -> LlmProviderClientApiConfig.builder().apiKey(secret).headers(headers).toString();
        return Stream.of(
                arguments(Named.of("ProviderApiKey", providerApiKey)),
                arguments(Named.of("ProviderApiKey builder", providerApiKeyBuilder)),
                arguments(Named.of("Webhook", webhook)),
                arguments(Named.of("Webhook builder", webhookBuilder)),
                arguments(Named.of("WebhookEvent builder", webhookEventBuilder)),
                arguments(Named.of("LlmProviderClientApiConfig", clientApiConfig)),
                arguments(Named.of("LlmProviderClientApiConfig builder", clientApiConfigBuilder)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("headers are printed by name, without their values or the credential")
    void headersArePrintedByName(BiFunction<Map<String, String>, String, String> toString) {
        var firstName = "X-A-%s".formatted(RandomStringUtils.secure().nextAlphabetic(8));
        var secondName = "X-B-%s".formatted(RandomStringUtils.secure().nextAlphabetic(8));
        var firstValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var secondValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var expectedHeaders = "headers=[%s, %s]".formatted(firstName, secondName);

        var actual = toString.apply(Map.of(firstName, firstValue, secondName, secondValue), SECRET);

        assertThat(actual).contains(expectedHeaders).doesNotContain(firstValue, secondValue, SECRET);
    }

    @Test
    @DisplayName("a provider key update prints nothing it carries, nor does its builder")
    void providerApiKeyUpdatePrintsNothing() {
        var headers = Map.of("X-Route", SECRET);
        var expected = List.of("ProviderApiKeyUpdate{}", "ProviderApiKeyUpdateBuilder{}");

        var actual = List.of(
                ProviderApiKeyUpdate.builder().apiKey(SECRET).headers(headers).build().toString(),
                ProviderApiKeyUpdate.builder().apiKey(SECRET).headers(headers).toString());

        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> urlsArePrintedByOrigin() {
        Function<String, String> providerApiKey = url -> ProviderApiKey.builder().provider(LlmProvider.CUSTOM_LLM)
                .baseUrl(url).build().toString();
        Function<String, String> clientApiConfig = url -> LlmProviderClientApiConfig.builder().baseUrl(url).build()
                .toString();
        Function<String, String> webhook = url -> Webhook.builder().url(url).build().toString();
        Function<String, String> authConfig = url -> ProviderAuthConfig.builder().tokenUrl(url).build().toString();
        return Stream.of(
                arguments(Named.of("ProviderApiKey base URL", providerApiKey)),
                arguments(Named.of("LlmProviderClientApiConfig base URL", clientApiConfig)),
                arguments(Named.of("Webhook URL", webhook)),
                arguments(Named.of("ProviderAuthConfig token URL", authConfig)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("a URL is printed by its scheme, host and port, without its path or query")
    void urlsArePrintedByOrigin(Function<String, String> toString) {
        var expectedOrigin = "https://example.com:8443";
        var url = "%s/services/%s?token=%s".formatted(expectedOrigin, SECRET, SECRET);

        var actual = toString.apply(url);

        assertThat(actual).contains(expectedOrigin).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("a client API config prints its configuration by key, and its auth config without secrets")
    void clientApiConfigPrintsConfigurationKeysAndNoAuthSecrets() {
        var configurationValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var credentialValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var authConfig = ProviderAuthConfig.builder()
                .tokenUrl("https://auth.example.com/%s".formatted(SECRET))
                .credentials(List.of(ProviderAuthConfig.Credential.builder()
                        .key("client_secret").value(credentialValue).secret(true).build()))
                .build();
        var expectedConfiguration = "configuration=[auth_header_name, provider_name]";

        var actual = LlmProviderClientApiConfig.builder()
                .configuration(Map.of("provider_name", configurationValue, "auth_header_name", configurationValue))
                .build()
                .toString();
        var actualAuthConfig = authConfig.toString();

        assertThat(actual).contains(expectedConfiguration).doesNotContain(configurationValue);
        assertThat(actualAuthConfig).doesNotContain(credentialValue, SECRET);
    }

    @Test
    @DisplayName("keys are sorted with a missing key first, and a missing map has none")
    void keysAreSortedAndNullSafe() {
        var map = new HashMap<String, String>();
        map.put("b", SECRET);
        map.put(null, SECRET);
        map.put("a", SECRET);
        var expectedKeys = Arrays.asList(null, "a", "b");

        var actualKeys = LoggableMaps.keys(map);
        var actualNullKeys = LoggableMaps.keys(null);

        assertThat(actualKeys).isEqualTo(expectedKeys);
        assertThat(actualNullKeys).isNull();
    }
}
