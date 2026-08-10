package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.google.cloud.vertexai.Transport;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Vertex AI client generator")
class VertexAIClientGeneratorTest {

    private static final String MODEL = "vertex_ai/gemini-2.5-flash";
    private static final String PROJECT_ID = "test-project";

    private static final String GENERATE_CONTENT_PATH = ".*:generateContent";
    private static final String TOKEN_PATH = "/token";

    private static final String GENERATE_CONTENT_RESPONSE = """
            {
              "candidates": [
                {
                  "content": {"role": "model", "parts": [{"text": "hello from the mock"}]},
                  "finishReason": "STOP"
                }
              ],
              "usageMetadata": {"promptTokenCount": 3, "candidatesTokenCount": 4, "totalTokenCount": 7}
            }
            """;

    private static final String TOKEN_RESPONSE = """
            {"access_token": "test-access-token", "token_type": "Bearer", "expires_in": 3600}
            """;

    private final WireMockUtils.WireMockRuntime wireMock = WireMockUtils.startWireMock();

    private String serviceAccountJson;

    /**
     * WireMock serves a self-signed certificate and the Vertex SDK always talks TLS, so the JVM default has to trust it
     * for the stub to be reachable at all.
     */
    @BeforeAll
    void trustWireMockCertificate() throws Exception {
        var trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{trustAll}, new SecureRandom());

        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }

    /**
     * {@code ServiceAccountCredentials.fromStream} parses and validates the private key, so the fixture needs a real
     * RSA key rather than a placeholder. It is generated per run and its {@code token_uri} points at WireMock, so the
     * OAuth exchange is stubbed too and no real credential exists anywhere in the test.
     */
    @BeforeAll
    void generateServiceAccountKey() throws Exception {
        serviceAccountJson = serviceAccountJson(PROJECT_ID);
    }

    private String serviceAccountJson(String projectId) throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var privateKey = keyPairGenerator.generateKeyPair().getPrivate();

        var pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(privateKey.getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";

        return """
                {
                  "type": "service_account",
                  "project_id": "%s",
                  "private_key_id": "test-key-id",
                  "private_key": "%s",
                  "client_email": "test@%s.iam.gserviceaccount.com",
                  "client_id": "1234567890",
                  "token_uri": "https://%s%s"
                }
                """.formatted(projectId, pem, projectId, wireMockHost(), TOKEN_PATH);
    }

    @AfterAll
    void tearDown() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        wireMock.server().stubFor(post(urlPathMatching(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(GENERATE_CONTENT_RESPONSE)));
        wireMock.server().stubFor(post(urlPathMatching(TOKEN_PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(TOKEN_RESPONSE)));
    }

    private String wireMockHost() {
        return "localhost:" + wireMock.server().httpsPort();
    }

    /**
     * Starts from the shipped {@code config-test.yml} rather than a hand-built config, so the generator is exercised
     * against the same block the app boots with. Only the two things the stub needs are overridden: every multi-region
     * location is remapped onto WireMock, which makes the endpoint the generator resolves observable as the host it
     * actually calls, and {@code Transport.REST} is required because WireMock speaks HTTP, not gRPC.
     */
    private LlmProviderClientConfig clientConfig() {
        var endpoint = wireMockHost() + "/";
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();

        config.setVertexAIClient(config.getVertexAIClient().toBuilder()
                .multiRegionApiEndpoints(Map.of("global", endpoint, "eu", endpoint, "us", endpoint))
                .transport(Transport.REST)
                .build());

        return config;
    }

    private void completeVia(String configuredLocation) {
        completeVia(new VertexAIClientGenerator(clientConfig()), configuredLocation);
    }

    private void completeVia(VertexAIClientGenerator generator, String configuredLocation) {
        var request = ChatCompletionRequest.builder().model(MODEL).build();
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(serviceAccountJson)
                .configuration(configuredLocation == null ? Map.of() : Map.of("location", configuredLocation))
                .build();

        generator.generate(config, request).chat(UserMessage.from("hello"));
    }

    private void assertCalledWithLocation(String expectedLocation) {
        wireMock.server().verify(postRequestedFor(urlPathMatching(
                ".*/locations/" + expectedLocation + "/publishers/google/models/.*")));
    }

    @Nested
    @DisplayName("Multi-region locations")
    class MultiRegionLocations {

        @ParameterizedTest
        @ValueSource(strings = {"global", "eu", "us"})
        void areCalledOnTheirConfiguredEndpoint(String location) {
            completeVia(location);

            assertCalledWithLocation(location);
        }

        /**
         * The location lands in the resource path as well as the host, so canonicalising it for the endpoint lookup
         * alone would leave the client calling the right host with a malformed {@code locations/} segment.
         */
        @ParameterizedTest
        @CsvSource({"GLOBAL,global", "'  global  ',global", "' EU ',eu"})
        void areCanonicalisedInTheRequestPath(String configured, String expectedLocation) {
            completeVia(configured);

            assertCalledWithLocation(expectedLocation);
        }
    }

    @Nested
    @DisplayName("Locations that keep the SDK-derived host")
    class SdkDerivedHosts {

        /**
         * Single-region locations are absent from the endpoint map, so the SDK keeps deriving the host from the location
         * itself rather than using the configured multi-region endpoint. Asserting on the resolved host keeps this off
         * the network: actually calling one of those hosts would mean real egress and multi-second DNS timeouts in CI.
         */
        @ParameterizedTest
        @ValueSource(strings = {"europe-west4", "us-central1", "asia-northeast1"})
        void areNotRedirectedToTheMultiRegionEndpoint(String location) {
            assertThat(resolvedApiEndpoint(location)).isEqualTo("%s-aiplatform.googleapis.com".formatted(location));
        }

        /**
         * A blank location is not rejected at the API boundary and the SDK rejects an empty one outright, so it has to
         * be treated as unset. Were it canonicalised into {@code ""}, building the client would fail with
         * "location can't be null or empty" instead of defaulting like an absent value.
         */
        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        void blankLocationsBehaveLikeAnUnsetOne(String location) {
            assertThat(resolvedApiEndpoint(location)).isEqualTo(resolvedApiEndpoint(null));
        }

        /**
         * Builds a client without calling it, so the host the SDK settled on can be read back. A blank location that
         * reached {@code setLocation} would surface here as an {@link IllegalArgumentException} instead of a host.
         */
        private String resolvedApiEndpoint(String location) {
            var generator = new VertexAIClientGenerator(clientConfig());
            var request = ChatCompletionRequest.builder().model(MODEL).build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(serviceAccountJson)
                    .configuration(location == null ? Map.of() : Map.of("location", location))
                    .build();

            return VertexAITestClients.apiEndpointOf(generator.generate(config, request));
        }
    }

    // Assert client identity, not thread count: this suite runs on REST (WireMock), which starts a GAX thread per
    // call regardless of reuse; only gRPC (prod) makes a shared client add none.
    @Nested
    @DisplayName("Client caching")
    class ClientCaching {

        @ParameterizedTest
        @CsvSource({"global, global", "'  GLOBAL  ', global"})
        @DisplayName("equivalent locations reuse one client")
        void reusesOneClientForEquivalentLocations(String first, String second) {
            var generator = new VertexAIClientGenerator(clientConfig());

            assertThat(clientFor(generator, first, serviceAccountJson))
                    .isSameAs(clientFor(generator, second, serviceAccountJson));
        }

        @Test
        void buildsADistinctClientPerLocation() throws Exception {
            var generator = new VertexAIClientGenerator(clientConfig());

            assertThat(clientFor(generator, "eu", serviceAccountJson))
                    .isNotSameAs(clientFor(generator, "us", serviceAccountJson));
        }

        @Test
        void buildsADistinctClientPerCredential() throws Exception {
            var generator = new VertexAIClientGenerator(clientConfig());

            assertThat(clientFor(generator, "global", serviceAccountJson))
                    .isNotSameAs(clientFor(generator, "global", serviceAccountJson("other-project")));
        }

        @Test
        void keepsCachingSeparatePerGenerator() throws Exception {
            assertThat(clientFor(new VertexAIClientGenerator(clientConfig()), "global", serviceAccountJson))
                    .isNotSameAs(clientFor(new VertexAIClientGenerator(clientConfig()), "global", serviceAccountJson));
        }

        private com.google.cloud.vertexai.VertexAI clientFor(VertexAIClientGenerator generator, String location,
                String apiKey) {
            var request = ChatCompletionRequest.builder().model(MODEL).build();
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(apiKey)
                    .configuration(location == null ? Map.of() : Map.of("location", location))
                    .build();

            return VertexAITestClients.vertexAiOf(generator.generate(config, request));
        }
    }
}
