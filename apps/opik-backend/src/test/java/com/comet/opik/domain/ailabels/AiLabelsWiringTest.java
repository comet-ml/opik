package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.Stage;
import jakarta.ws.rs.client.Client;
import org.junit.jupiter.api.Test;
import ru.vyarus.dropwizard.guice.module.yaml.bind.ConfigImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The bindings guicey provides in the app ({@code @Config("aiLabels")} and the shared {@link Client}) are enough to
 * inject every ai-labels component, and with the feature off (no url, no credential) nothing fails.
 */
class AiLabelsWiringTest {

    @Test
    void componentsInjectWithTheFeatureOff() {
        var injector = Guice.createInjector(Stage.PRODUCTION, binder -> {
            binder.bind(Client.class).toInstance(TestHttpClientUtils.client());
            binder.bind(Key.get(AiLabelsConfig.class, new ConfigImpl("aiLabels"))).toInstance(new AiLabelsConfig());
        });

        var client = injector.getInstance(InferenceClient.class);
        var registry = injector.getInstance(AiLabelTaskRegistry.class);

        assertThat(injector.getInstance(InferenceClient.class)).isSameAs(client);
        assertThat(injector.getInstance(AiLabelItemBuilder.class)).isNotNull();
        assertThat(injector.getInstance(AiLabelResultMapper.class)).isNotNull();
        assertThatCode(registry::start).doesNotThrowAnyException();
    }
}
