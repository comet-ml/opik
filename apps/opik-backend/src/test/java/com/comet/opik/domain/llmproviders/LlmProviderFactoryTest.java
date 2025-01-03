package com.comet.opik.domain.llmproviders;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ai4j.openai4j.chat.ChatCompletionModel;
import dev.langchain4j.model.anthropic.AnthropicChatModelName;
import io.dropwizard.configuration.ConfigurationException;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import jakarta.validation.Validator;
import org.apache.commons.lang3.EnumUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

public class LlmProviderFactoryTest {
    private static final LlmProviderApiKeyService llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
    private static LlmProviderClientConfig llmProviderClientConfig;

    private static final ObjectMapper objectMapper = Jackson.newObjectMapper();
    private static final Validator validator = Validators.newValidator();
    private static final YamlConfigurationFactory<OpikConfiguration> factory = new YamlConfigurationFactory<>(
            OpikConfiguration.class, validator, objectMapper, "dw");

    @BeforeAll
    static void setUpAll() throws ConfigurationException, IOException {
        final OpikConfiguration config = factory.build(new FileConfigurationSourceProvider(),
                "src/test/resources/config-test.yml");
        EncryptionUtils.setConfig(config);
        llmProviderClientConfig = config.getLlmProviderClient();
    }

    @AfterEach
    public void tearDown() {
        reset(llmProviderApiKeyService);
    }

    @ParameterizedTest
    @MethodSource
    void testGetService(String model, LlmProvider llmProvider, Class<? extends LlmProviderService> providerClass) {
        // setup
        String workspaceId = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();

        when(llmProviderApiKeyService.find(workspaceId)).thenReturn(ProviderApiKey.ProviderApiKeyPage.builder()
                .content(List.of(ProviderApiKey.builder()
                        .provider(llmProvider)
                        .apiKey(EncryptionUtils.encrypt(apiKey))
                        .build()))
                .total(1)
                .page(1)
                .size(1)
                .build());

        // SUT
        var llmProviderFactory = new LlmProviderFactory(llmProviderClientConfig, llmProviderApiKeyService);

        LlmProviderService actual = llmProviderFactory.getService(workspaceId, model);

        // assertions
        assertThat(actual).isInstanceOf(providerClass);
    }

    private static Stream<Arguments> testGetService() {
        var openAiModels = EnumUtils.getEnumList(ChatCompletionModel.class).stream()
                .map(model -> arguments(model.toString(), LlmProvider.OPEN_AI, OpenAi.class));
        var anthropicModels = EnumUtils.getEnumList(AnthropicChatModelName.class).stream()
                .map(model -> arguments(model.toString(), LlmProvider.ANTHROPIC, Anthropic.class));

        return Stream.concat(openAiModels, anthropicModels);
    }
}
