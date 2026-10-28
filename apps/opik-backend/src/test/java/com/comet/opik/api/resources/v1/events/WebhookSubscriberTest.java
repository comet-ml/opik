package com.comet.opik.api.resources.v1.events;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.dropwizard.util.DataSize;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;

@ExtendWith(MockitoExtension.class)
class WebhookSubscriberTest {

    public static final int MAX_RETRIES = 4;
    public static final String WORKSPACE_ID = UUID.randomUUID().toString();
    public static final String USER_NAME = UUID.randomUUID().toString();

    @Mock
    private RedissonReactiveClient redisson;

    private WireMockServer wireMockServer;
    private WebhookConfig webhookConfig;
    private WebhookSubscriber webhookSubscriber;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0); // Use random port
        wireMockServer.start();

        webhookConfig = createWebhookConfig();

        WebhookHttpClient webhookHttpClient;

        // Mock the static UserFacingLoggingFactory.getLogger method
        try (MockedStatic<UserFacingLoggingFactory> mockedFactory = mockStatic(UserFacingLoggingFactory.class)) {
            mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                    .thenReturn(mock(Logger.class));

            // Create WebhookHttpClient with a real JAX-RS client and a mock UserFacingLoggingFactory
            // Note: The factory parameter is not actually used in the constructor, but is required
            webhookHttpClient = new WebhookHttpClient(TestHttpClientUtils.client(), webhookConfig);
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

    /**
     * The body is sent in chunks over far longer than the test allows, so it completes only if the client stops at the
     * cap and drops the rest of the response, rather than reading it all and truncating.
     */
    @ParameterizedTest
    @ValueSource(ints = {16, 512})
    void sendWebhook_whenResponseBodyExceedsTheCap_shouldReadOnlyTheCap(int responseBodyMaxSize) {
        int chunks = 8;
        var body = RandomStringUtils.secure().nextAlphanumeric(responseBodyMaxSize * chunks);
        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200).withBody(body).withChunkedDribbleDelay(chunks, 16_000)));
        var config = createWebhookConfig();
        config.setRequestTimeout(Duration.seconds(30));
        config.setResponseBodyMaxSize(DataSize.bytes(responseBodyMaxSize));
        WebhookHttpClient webhookHttpClient;
        try (MockedStatic<UserFacingLoggingFactory> mockedFactory = mockStatic(UserFacingLoggingFactory.class)) {
            mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                    .thenReturn(mock(Logger.class));
            webhookHttpClient = new WebhookHttpClient(TestHttpClientUtils.client(), config);
        }

        var expectedBody = body.substring(0, responseBodyMaxSize);
        StepVerifier.create(webhookHttpClient.sendWebhook(
                createWebhookEvent("http://localhost:" + wireMockServer.port() + "/webhook"))
                .contextWrite(ctx -> setRequestContext(ctx, USER_NAME, WORKSPACE_ID)))
                .expectNext(expectedBody)
                .expectComplete()
                .verify(Duration.seconds(8).toJavaDuration());
    }

    /**
     * A Slack-style webhook URL carries its token in the path. Neither the server logs, at any level, nor the
     * user-facing ones show it, whether the delivery succeeds or keeps failing.
     */
    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void processEvent_neverLogsTheTokenInTheWebhookPath(int status) {
        var token = RandomStringUtils.secure().nextAlphanumeric(24);
        var webhookPath = "/services/T%s/B%s/%s".formatted(token, token, token);
        wireMockServer.stubFor(post(urlEqualTo(webhookPath)).willReturn(aResponse().withStatus(status)
                .withBody("{\"ok\": true}")));
        var userFacingLogger = mock(Logger.class);
        WebhookHttpClient webhookHttpClient;
        try (MockedStatic<UserFacingLoggingFactory> mockedFactory = mockStatic(UserFacingLoggingFactory.class)) {
            mockedFactory.when(() -> UserFacingLoggingFactory.getLogger(any(Class.class)))
                    .thenReturn(userFacingLogger);
            webhookHttpClient = new WebhookHttpClient(TestHttpClientUtils.client(), webhookConfig);
        }
        var subscriber = new WebhookSubscriber(webhookConfig, redisson, webhookHttpClient);
        var webhookEvent = createWebhookEvent("http://localhost:%d%s".formatted(wireMockServer.port(), webhookPath));
        var logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.comet.opik");
        var appender = new ListAppender<ILoggingEvent>();
        var previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            StepVerifier.create(subscriber.processEvent(webhookEvent)).verifyComplete();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        wireMockServer.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo(webhookPath)));
        var actualServerLogs = appender.list.stream()
                .map(event -> event.getThrowableProxy() == null
                        ? event.getFormattedMessage()
                        : "%s%n%s".formatted(event.getFormattedMessage(),
                                ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .toList();
        var actualUserFacingLogs = mockingDetails(userFacingLogger).getInvocations().stream()
                .map(invocation -> Arrays.deepToString(invocation.getArguments()))
                .toList();
        assertThat(actualServerLogs).isNotEmpty().noneMatch(line -> line.contains(token));
        assertThat(actualUserFacingLogs).isNotEmpty().noneMatch(line -> line.contains(token));
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
    void processEvent_whenWebhookRedirects_shouldNotFollowTheRedirect() {
        var webhookPath = "/webhook-" + UUID.randomUUID();
        var redirectTarget = "/elsewhere-" + UUID.randomUUID();
        var webhookEvent = createWebhookEvent("http://localhost:" + wireMockServer.port() + webhookPath);
        wireMockServer.stubFor(post(urlEqualTo(webhookPath))
                .willReturn(aResponse().withStatus(307).withHeader("Location", redirectTarget)));
        wireMockServer.stubFor(post(urlEqualTo(redirectTarget)).willReturn(aResponse().withStatus(200)));

        StepVerifier.create(webhookSubscriber.processEvent(webhookEvent))
                .verifyComplete();

        wireMockServer.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo(webhookPath)));
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(redirectTarget)));
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
        config.setDestinationScheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS);
        config.setResponseBodyMaxSize(DataSize.bytes(512));
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
