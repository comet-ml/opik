package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.llm.openrouter.decisions.OpenRouterDecisionsClient;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Docker build compiles without {@code lombok.config}, so a {@code @Config} qualifier on a Lombok field never
 * reaches the generated constructor and Guice injects an empty, unvalidated config instead. These checks keep the
 * decisions client wired through a hand-written, qualified parameter.
 */
class OpenRouterModuleTest {

    @Test
    void decisionsClientIsProvidedWithTheLlmProviderClientConfig() throws NoSuchMethodException {
        var provider = OpenRouterModule.class.getMethod("decisionsClient", RetriableHttpClient.class,
                LlmProviderClientConfig.class);

        var config = provider.getParameters()[1].getAnnotation(Config.class);

        assertThat(config).isNotNull();
        assertThat(config.value()).isEqualTo("llmProviderClient");
    }

    @Test
    void decisionsClientCannotBeInjectedDirectly() {
        // Without an @Inject constructor Guice can only build it through the module's provider method.
        assertThat(Arrays.stream(OpenRouterDecisionsClient.class.getConstructors())
                .noneMatch(constructor -> constructor.isAnnotationPresent(Inject.class)
                        || constructor.isAnnotationPresent(com.google.inject.Inject.class)))
                .isTrue();
    }
}
