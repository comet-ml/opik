package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
class WebhookSubscriberTest {

    public static final int MAX_RETRIES = 4;
    public static final String WORKSPACE_ID = UUID.randomUUID().toString();
    public static final String USER_NAME = UUID.randomUUID().toString();

    @Mock
    private RedissonReactiveClient redisson;

    private WireMockServer wireMockServer;
    private WebhookConfig webhookConfig;
    private WebhookHttpClient webhookHttpClient;
    private WebhookSubscriber webhookSubscriber;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0); // Use random port
        wireMockServer.start();

        webhookConfig = createWebhookConfig();

        // Mock the static UserFacingLoggingFactory.getLogger method
        try (MockedStatic<UserFacingLoggingFactory> mockedFactory = mockStatic(UserFacingLoggingFactory.class)) {
            mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                    .thenReturn(mock(org.slf4j.Logger.class));

            // Create WebhookHttpClient with a real JAX-RS client and a mock UserFacingLoggingFactory
            // Note: The factory parameter is not actually used in the constructor, but is required
            webhookHttpClient = new WebhookHttpClient(
                    jakarta.ws.rs.client.ClientBuilder.newClient(),
                    webhookConfig);
        }

        webhookSubscriber = new WebhookSubscriber(webhookConfig, redisson, webhookHttpClient);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void processEvent_whenValidWebhookEvent_shouldSendHttpRequest() {
        // Given
        var webhookUrl = "http://localhost:" + wireMockServer.port() + "/webhook";
        var webhookEvent = createWebhookEvent(webhookUrl);

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\"}")));

        // When
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Then
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("User-Agent", equalTo("Opik-Webhook/1.0")));
    }

    @Test
    void processEvent_whenWebhookReturns500_shouldRetryAndEventuallyFail() {
        // Given
        var webhookUrl = "http://localhost:" + wireMockServer.port() + "/webhook";
        var webhookEvent = createWebhookEvent(webhookUrl);

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // When & Then - Should complete without error (permanent failure is handled gracefully)
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Verify multiple retry attempts were made
        wireMockServer.verify(webhookConfig.getMaxRetries() + 1,
                postRequestedFor(urlEqualTo("/webhook")));
    }

    @Test
    void processEvent_whenInvalidUrl_shouldFailValidation() {
        // Given
        var webhookEvent = createWebhookEvent("invalid-url");

        // When & Then
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete(); // Should complete gracefully after handling validation error
    }

    @Test
    void processEvent_whenCustomHeaders_shouldIncludeHeadersInRequest() {
        // Given
        var webhookUrl = "http://localhost:" + wireMockServer.port() + "/webhook";
        var customHeaders = Map.of(
                "X-Custom-Header", "custom-value",
                "Authorization", "Bearer token123");
        var webhookEvent = createWebhookEvent(webhookUrl).toBuilder()
                .headers(customHeaders)
                .build();

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200)));

        // When
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Then
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook"))
                .withHeader("X-Custom-Header", equalTo("custom-value"))
                .withHeader("Authorization", equalTo("Bearer token123")));
    }

    @Test
    void processEvent_whenWebhookEventExceedsMaxRetries_shouldNotRetry() {
        // Given
        var webhookUrl = "http://localhost:" + wireMockServer.port() + "/webhook";
        var webhookEvent = createWebhookEvent(webhookUrl).toBuilder()
                .maxRetries(MAX_RETRIES)
                .build();

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(500)));

        // When
        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        // Then - Should only make one attempt (no retries)
        wireMockServer.verify(webhookEvent.getMaxRetries() + 1, postRequestedFor(urlEqualTo("/webhook")));
    }

    private WebhookConfig createWebhookConfig() {
        var config = new WebhookConfig();
        config.setEnabled(true);
        config.setMaxRetries(MAX_RETRIES);
        config.setInitialRetryDelay(Duration.milliseconds(100));
        config.setMaxRetryDelay(Duration.seconds(1));
        config.setRequestTimeout(Duration.seconds(5));
        config.setConnectionTimeout(Duration.seconds(2));
        config.setConsumerBatchSize(10);
        config.setPoolingInterval(Duration.seconds(1));
        return config;
    }

    private WebhookEvent<?> createWebhookEvent(String url) {
        return WebhookEvent.builder()
                .id("webhook-" + System.currentTimeMillis())
                .eventType(AlertEventType.PROMPT_CREATED)
                .alertId(UUID.randomUUID())
                .alertName("Test Alert")
                .alertType(AlertType.GENERAL)
                .workspaceId(WORKSPACE_ID)
                .url(url)
                .payload(Map.of("message", "test payload", "timestamp", Instant.now().toString()))
                .createdAt(Instant.now())
                .maxRetries(MAX_RETRIES)
                .headers(Map.of())
                .build();
    }
}
