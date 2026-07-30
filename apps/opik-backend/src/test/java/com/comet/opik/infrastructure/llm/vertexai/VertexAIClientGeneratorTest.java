package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VertexAIClientGeneratorTest {

    private static final String MODEL = "vertex_ai/gemini-2.5-flash";

    private static String serviceAccountKey;

    private final VertexAIClientGenerator generator = new VertexAIClientGenerator(clientConfig());

    private static LlmProviderClientConfig clientConfig() {
        var config = new LlmProviderClientConfig();
        config.setVertexAIClient(
                new LlmProviderClientConfig.VertexAIClientConfig("https://www.googleapis.com/auth/cloud-platform"));
        return config;
    }

    /**
     * {@code ServiceAccountCredentials.fromStream} parses and validates the private key, so the fixture needs a real
     * RSA key rather than a placeholder string. Generated per run to keep a credential-shaped blob out of the repo.
     */
    @BeforeAll
    static void generateServiceAccountKey() throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        var privateKey = keyPairGenerator.generateKeyPair().getPrivate();

        var pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(privateKey.getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";

        serviceAccountKey = """
                {
                  "type": "service_account",
                  "project_id": "test-project",
                  "private_key_id": "test-key-id",
                  "private_key": "%s",
                  "client_email": "test@test-project.iam.gserviceaccount.com",
                  "client_id": "1234567890"
                }
                """.formatted(pem);
    }

    private String apiEndpointForConfiguredLocation(Map<String, String> configuration) {
        var request = ChatCompletionRequest.builder().model(MODEL).build();
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(serviceAccountKey)
                .configuration(configuration)
                .build();

        var model = (VertexAiGeminiChatModel) generator.generate(config, request);

        return vertexAIApiEndpoint(model);
    }

    /**
     * The endpoint is only observable through the {@code VertexAI} instance the client was built around. It has to be
     * reached via the {@code GenerativeModel}, because the constructor this generator uses leaves the chat model's own
     * {@code vertexAI} field unset.
     */
    private static String vertexAIApiEndpoint(VertexAiGeminiChatModel model) {
        try {
            Field generativeModelField = VertexAiGeminiChatModel.class.getDeclaredField("generativeModel");
            generativeModelField.setAccessible(true);
            var generativeModel = generativeModelField.get(model);

            Field vertexAiField = GenerativeModel.class.getDeclaredField("vertexAi");
            vertexAiField.setAccessible(true);

            return ((VertexAI) vertexAiField.get(generativeModel)).getApiEndpoint();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not read the API endpoint off the generated client", e);
        }
    }

    @Nested
    @DisplayName("Multi-region locations resolve to their own hosts")
    class MultiRegionEndpoints {

        @ParameterizedTest
        @CsvSource({
                "global,aiplatform.googleapis.com",
                "eu,aiplatform.eu.rep.googleapis.com",
                "us,aiplatform.us.rep.googleapis.com"})
        void overridesTheLocationDerivedHost(String location, String expectedEndpoint) {
            assertThat(VertexAIClientGenerator.apiEndpointFor(location)).contains(expectedEndpoint);
        }

        @ParameterizedTest
        @CsvSource({"GLOBAL,aiplatform.googleapis.com", "  global  ,aiplatform.googleapis.com",
                "Eu,aiplatform.eu.rep.googleapis.com"})
        void toleratesCasingAndSurroundingWhitespace(String location, String expectedEndpoint) {
            assertThat(VertexAIClientGenerator.apiEndpointFor(location)).contains(expectedEndpoint);
        }

        @Test
        void generatedClientTargetsTheGlobalHost() {
            assertThat(apiEndpointForConfiguredLocation(Map.of("location", "global")))
                    .isEqualTo("aiplatform.googleapis.com");
        }
    }

    @Nested
    @DisplayName("Single-region locations keep the SDK default")
    class RegionalEndpoints {

        @ParameterizedTest
        @ValueSource(strings = {"europe-west4", "us-central1", "asia-northeast1"})
        void areNotOverridden(String location) {
            assertThat(VertexAIClientGenerator.apiEndpointFor(location)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"europe-west4", "us-central1"})
        void generatedClientKeepsTheRegionalHost(String location) {
            assertThat(apiEndpointForConfiguredLocation(Map.of("location", location)))
                    .isEqualTo("%s-aiplatform.googleapis.com".formatted(location));
        }
    }
}
