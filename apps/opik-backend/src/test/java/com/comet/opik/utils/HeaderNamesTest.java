package com.comet.opik.utils;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Everything that carries configured headers prints only their names, never their values or the credentials. */
class HeaderNamesTest {

    private static final String API_KEY = RandomStringUtils.secure().nextAlphanumeric(24);

    static Stream<Arguments> headersArePrintedByName() {
        BiFunction<Map<String, String>, String, String> providerApiKey = (headers, secret) -> ProviderApiKey
                .builder().id(UUID.randomUUID()).provider(LlmProvider.CUSTOM_LLM).apiKey(secret).headers(headers)
                .build().toString();
        BiFunction<Map<String, String>, String, String> providerApiKeyBuilder = (headers, secret) -> ProviderApiKey
                .builder().provider(LlmProvider.CUSTOM_LLM).apiKey(secret).headers(headers).toString();
        BiFunction<Map<String, String>, String, String> providerApiKeyUpdate = (headers,
                secret) -> ProviderApiKeyUpdate.builder().apiKey(secret).headers(headers).build().toString();
        BiFunction<Map<String, String>, String, String> providerApiKeyUpdateBuilder = (headers,
                secret) -> ProviderApiKeyUpdate.builder().apiKey(secret).headers(headers).toString();
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
                arguments(Named.of("ProviderApiKey", providerApiKey), true),
                arguments(Named.of("ProviderApiKey builder", providerApiKeyBuilder), true),
                // prints nothing it carries
                arguments(Named.of("ProviderApiKeyUpdate", providerApiKeyUpdate), false),
                arguments(Named.of("ProviderApiKeyUpdate builder", providerApiKeyUpdateBuilder), false),
                arguments(Named.of("Webhook", webhook), true),
                arguments(Named.of("Webhook builder", webhookBuilder), true),
                arguments(Named.of("WebhookEvent builder", webhookEventBuilder), true),
                arguments(Named.of("LlmProviderClientApiConfig", clientApiConfig), true),
                arguments(Named.of("LlmProviderClientApiConfig builder", clientApiConfigBuilder), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void headersArePrintedByName(BiFunction<Map<String, String>, String, String> toString, boolean printsNames) {
        var firstName = "X-A-%s".formatted(RandomStringUtils.secure().nextAlphabetic(8));
        var secondName = "X-B-%s".formatted(RandomStringUtils.secure().nextAlphabetic(8));
        var firstValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var secondValue = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));

        var actual = toString.apply(Map.of(firstName, firstValue, secondName, secondValue), API_KEY);

        assertThat(actual).doesNotContain(firstValue, secondValue, API_KEY);
        if (printsNames) {
            assertThat(actual).contains("headers=[%s, %s]".formatted(firstName, secondName));
        }
    }
}
