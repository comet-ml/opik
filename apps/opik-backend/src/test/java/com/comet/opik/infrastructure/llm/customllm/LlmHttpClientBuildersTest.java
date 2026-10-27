package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import lombok.Builder;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmHttpClientBuildersTest {

    /** The pool and dispatcher a client runs on. */
    @Builder(toBuilder = true)
    private record Transport(ConnectionPool connectionPool, Dispatcher dispatcher) {
        static Transport of(OkHttpClient client) {
            return new Transport(client.connectionPool(), client.dispatcher());
        }
    }

    @Test
    void backendCodeUsesNoJdkHttpClient() {
        noClasses().should().dependOnClassesThat().belongToAnyOf(HttpClient.class)
                .because("Opik's own calls go through the shared Jersey client and LLM provider calls through OkHttp")
                .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.comet.opik"));
        assertThatThrownBy(() -> Class.forName("dev.langchain4j.http.client.jdk.JdkHttpClient"))
                .isExactlyInstanceOf(ClassNotFoundException.class);
        // a model built without one of the shared clients fails instead of running on a client of its own
        assertThatThrownBy(HttpClientBuilderLoader::loadHttpClientBuilder)
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyModelRunsOnThePoolAndDispatcherOfTheSharedClient() {
        var module = new CustomLlmModule();
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        var client = module.customLlmHttpClient(module.providerBaseUrlGuard(config));
        var builders = module.llmHttpClientBuilders(config, client);

        var actualTransports = Stream.of(builders.builder(), builders.builder())
                .map(LlmHttpClientBuildersTest::transport)
                .toList();

        var expectedTransport = Transport.of(client);
        assertThat(actualTransports).isEqualTo(List.of(expectedTransport, expectedTransport));
    }

    /** The transport of a model's client, built with a timeout of its own as each model's is. */
    private static Transport transport(HttpClientBuilder builder) {
        var client = (CustomLlmHttpClient) builder.readTimeout(Duration.ofSeconds(7)).build();
        return Transport.of(client.getClient());
    }
}
