package com.comet.opik.infrastructure.aws;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.infrastructure.AlertsEventBridgeConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsModuleTest {

    private static final String ARN_BUS = "arn:aws:events:eu-west-1:123456789012:event-bus/opik-alerts";

    static Stream<Arguments> eventBridgeRegionCases() {
        return Stream.of(
                Arguments.of(ARN_BUS, "us-east-2", Optional.of(Region.US_EAST_2)),
                Arguments.of(ARN_BUS, null, Optional.of(Region.EU_WEST_1)),
                Arguments.of(ARN_BUS, "", Optional.of(Region.EU_WEST_1)),
                Arguments.of("opik-alerts", "us-east-2", Optional.of(Region.US_EAST_2)),
                Arguments.of("opik-alerts", null, Optional.empty()),
                Arguments.of("arn:aws:events", null, Optional.empty()),
                Arguments.of(null, null, Optional.empty()));
    }

    @ParameterizedTest
    @MethodSource("eventBridgeRegionCases")
    void eventBridgeRegion(String eventBus, String region, Optional<Region> expected) {
        var config = new AlertsEventBridgeConfig();
        config.setEventBus(eventBus);
        config.setRegion(region);

        assertThat(AwsModule.eventBridgeRegion(config)).isEqualTo(expected);
    }

    @Test
    void eventBridgeClientWhenDisabledIsEmpty() {
        var toggles = new ServiceTogglesConfig();
        toggles.setEventBridgeAlertsEnabled(false);

        assertThat(
                new AwsModule().eventBridgeClient(toggles, new AlertsEventBridgeConfig(), TestHttpClientUtils.client()))
                .isEmpty();
    }

    @Test
    void eventBridgeClientWhenEnabledIsPresent() {
        var toggles = new ServiceTogglesConfig();
        toggles.setEventBridgeAlertsEnabled(true);
        var config = new AlertsEventBridgeConfig();
        config.setEventBus(ARN_BUS);

        var client = new AwsModule().eventBridgeClient(toggles, config, TestHttpClientUtils.client());

        assertThat(client).isPresent();
        try (var eventBridgeClient = client.get()) {
            assertThat(eventBridgeClient.serviceClientConfiguration().region()).isEqualTo(Region.EU_WEST_1);
            var override = eventBridgeClient.serviceClientConfiguration().overrideConfiguration();
            assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
            assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(8));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CustomerCredentialsEndpoint {

        private static final String PATH = "/customer-credentials";

        private WireMockServer endpoint;

        @BeforeAll
        void setUpAll() {
            endpoint = new WireMockServer(0);
            endpoint.start();
        }

        @AfterAll
        void tearDownAll() {
            endpoint.stop();
        }

        @BeforeEach
        void setUp() {
            endpoint.resetAll();
        }

        private AlertsEventBridgeConfig config() {
            var config = new AlertsEventBridgeConfig();
            config.setEventBus(ARN_BUS);
            config.setCustomerCredentialsEndpoint(endpoint.baseUrl() + PATH);
            return config;
        }

        private void stubCredentials(String scenarioState, String nextState, String accessKeyId, Instant expiration) {
            endpoint.stubFor(get(PATH)
                    .inScenario("rotation")
                    .whenScenarioStateIs(scenarioState)
                    .willSetStateTo(nextState)
                    .willReturn(okJson("""
                            {"Version": 1, "AccessKeyId": "%s", "SecretAccessKey": "secret-%s",
                             "SessionToken": "token-%s", "Expiration": "%s"}
                            """.formatted(accessKeyId, accessKeyId, accessKeyId, expiration))));
        }

        // Restores rather than clears: other tests in the same JVM (e.g. MinIO) set some of these globally
        private void withSystemProperties(Map<String, String> properties, Runnable test) {
            var previous = new HashMap<String, String>();
            properties.forEach((key, value) -> previous.put(key, System.setProperty(key, value)));
            try {
                test.run();
            } finally {
                previous.forEach((key, value) -> {
                    if (value == null) {
                        System.clearProperty(key);
                    } else {
                        System.setProperty(key, value);
                    }
                });
            }
        }

        @Test
        void signsWithEndpointCredentialsEvenWhenIrsaIsConfigured() {
            stubCredentials(Scenario.STARTED, Scenario.STARTED, "CUSTOMER1",
                    Instant.now().plus(1, ChronoUnit.HOURS));

            withSystemProperties(Map.of(
                    "aws.webIdentityTokenFile", "/var/run/secrets/eks.amazonaws.com/serviceaccount/token",
                    "aws.roleArn", "arn:aws:iam::222222222222:role/partner"), () -> {
                        var credentials = AwsModule
                                .eventBridgeCredentialsProvider(config(), TestHttpClientUtils.client())
                                .resolveCredentials();

                        assertThat(credentials).isInstanceOfSatisfying(AwsSessionCredentials.class, session -> {
                            assertThat(session.accessKeyId()).isEqualTo("CUSTOMER1");
                            assertThat(session.secretAccessKey()).isEqualTo("secret-CUSTOMER1");
                            assertThat(session.sessionToken()).isEqualTo("token-CUSTOMER1");
                        });
                    });
        }

        @Test
        void cachesCredentialsUntilCloseToExpiry() {
            stubCredentials(Scenario.STARTED, "second", "CUSTOMER1", Instant.now().plus(1, ChronoUnit.HOURS));
            stubCredentials("second", "second", "CUSTOMER2", Instant.now().plus(1, ChronoUnit.HOURS));
            var provider = AwsModule.eventBridgeCredentialsProvider(config(), TestHttpClientUtils.client());

            assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("CUSTOMER1");
            assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("CUSTOMER1");
            endpoint.verify(1, getRequestedFor(urlEqualTo(PATH)));
        }

        @Test
        void fetchesNewCredentialsOnceTheFirstHaveExpired() {
            stubCredentials(Scenario.STARTED, "second", "CUSTOMER1", Instant.now().minus(1, ChronoUnit.SECONDS));
            stubCredentials("second", "second", "CUSTOMER2", Instant.now().plus(1, ChronoUnit.HOURS));
            var provider = AwsModule.eventBridgeCredentialsProvider(config(), TestHttpClientUtils.client());

            assertThat(provider.resolveCredentials())
                    .extracting(AwsCredentials::accessKeyId, AwsCredentials::secretAccessKey)
                    .containsExactly("CUSTOMER1", "secret-CUSTOMER1");
            assertThat(provider.resolveCredentials())
                    .extracting(AwsCredentials::accessKeyId, AwsCredentials::secretAccessKey)
                    .containsExactly("CUSTOMER2", "secret-CUSTOMER2");
            endpoint.verify(2, getRequestedFor(urlEqualTo(PATH)));
        }

        @ParameterizedTest
        @ValueSource(ints = {500, 403})
        void failsRetryablyInsteadOfFallingBackWhenEndpointErrors(int status) {
            endpoint.stubFor(get(PATH).willReturn(aResponse().withStatus(status)));

            withSystemProperties(Map.of("aws.accessKeyId", "PARTNER", "aws.secretAccessKey", "partner-secret"), () -> {
                var provider = AwsModule.eventBridgeCredentialsProvider(config(), TestHttpClientUtils.client());

                // The I/O cause is what makes the alert publisher retry it
                assertThatThrownBy(provider::resolveCredentials)
                        .isInstanceOf(SdkClientException.class)
                        .hasMessageContaining("HTTP '%s'".formatted(status))
                        .hasCauseInstanceOf(IOException.class);
            });
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"Token\": \"token-CUSTOMER1\"", "\"SessionToken\": \" \""})
        void rejectsResponseWithoutSessionToken(String tokenFields) {
            endpoint.stubFor(get(PATH).willReturn(okJson("""
                    {"AccessKeyId": "CUSTOMER1", "SecretAccessKey": "secret-CUSTOMER1", %s, "Expiration": "%s"}
                    """.formatted(tokenFields, Instant.now().plus(1, ChronoUnit.HOURS)))));

            assertThatThrownBy(AwsModule.eventBridgeCredentialsProvider(config(),
                    TestHttpClientUtils.client())::resolveCredentials)
                    .isInstanceOf(SdkClientException.class)
                    .hasMessageContaining("'SessionToken'");
        }

        @Test
        void rejectsResponseWithoutBody() {
            endpoint.stubFor(get(PATH).willReturn(aResponse().withStatus(200)));
            var expectedMessage = "Customer credentials endpoint returned an unreadable response";

            assertThatThrownBy(AwsModule.eventBridgeCredentialsProvider(config(),
                    TestHttpClientUtils.client())::resolveCredentials)
                    .isExactlyInstanceOf(SdkClientException.class)
                    .hasMessage(expectedMessage);
        }

        @Test
        void withoutEndpointKeepsIrsaContainerDefaultChain() {
            var config = new AlertsEventBridgeConfig();
            config.setEventBus(ARN_BUS);

            assertThat(AwsModule.eventBridgeCredentialsProvider(config, TestHttpClientUtils.client()))
                    .isInstanceOf(AwsCredentialsProviderChain.class)
                    .asString()
                    .contains("WebIdentityTokenFileCredentialsProvider", "ContainerCredentialsProvider",
                            "DefaultCredentialsProvider");
        }
    }
}
