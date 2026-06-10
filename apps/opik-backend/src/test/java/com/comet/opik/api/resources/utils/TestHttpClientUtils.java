package com.comet.opik.api.resources.utils;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.http.HttpModule;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.configuration.ConfigurationException;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import jakarta.ws.rs.client.Client;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;

@UtilityClass
public class TestHttpClientUtils {

    public static final String FAKE_API_KEY_MESSAGE = "User not allowed to access workspace!";
    public static final io.dropwizard.jersey.errors.ErrorMessage UNAUTHORIZED_RESPONSE = new io.dropwizard.jersey.errors.ErrorMessage(
            401, FAKE_API_KEY_MESSAGE);
    public static final String PROJECT_NOT_FOUND_MESSAGE = "Project id: %s not found";
    public static final String PROJECT_NAME_NOT_FOUND_MESSAGE = "Project name: %s not found";
    public static final io.dropwizard.jersey.errors.ErrorMessage NO_API_KEY_RESPONSE = new io.dropwizard.jersey.errors.ErrorMessage(
            401, MISSING_API_KEY);

    /**
     * Returns a static {@link Client} for direct unit tests that construct services manually
     * and therefore cannot receive the production singleton via dependency injection.
     * <p>
     * Does not build the client itself: delegates to {@link HttpModule#buildClient} with the
     * configuration from {@code config-test.yml} so the resulting client is wired
     * close to identically to the production singleton client.
     * <p>
     * Callers must NOT close the returned client. It is shared across tests and its worker
     * threads are daemons, so the underlying executor and connection pool are released on
     * JVM shutdown without an explicit {@code close()}.
     */
    @Getter
    @Accessors(fluent = true)
    private final Client client = newClient();

    private Client newClient() {
        try {
            var jerseyConfig = parseTestOpikConfiguration().getJerseyClient();
            var executor = new ThreadPoolExecutor(
                    jerseyConfig.getMinThreads(),
                    jerseyConfig.getMaxThreads(),
                    60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(jerseyConfig.getWorkQueueSize()),
                    newThreadFactory());
            var jerseyClientBuilder = new JerseyClientBuilder(new MetricRegistry())
                    .using(executor);
            return HttpModule.buildClient(jerseyClientBuilder, jerseyConfig);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private OpikConfiguration parseTestOpikConfiguration() throws IOException, ConfigurationException {
        var yamlConfigFactory = new YamlConfigurationFactory<>(
                OpikConfiguration.class, Validators.newValidator(), Jackson.newObjectMapper(), "dw");
        return yamlConfigFactory.build(new FileConfigurationSourceProvider(), "src/test/resources/config-test.yml");
    }

    private ThreadFactory newThreadFactory() {
        var counter = new AtomicLong();
        var defaultThreadFactory = Executors.defaultThreadFactory();
        return runnable -> {
            var thread = defaultThreadFactory.newThread(runnable);
            thread.setName("thread-%s-test-%d".formatted(HttpModule.CLIENT_NAME, counter.incrementAndGet()));
            thread.setDaemon(true);
            return thread;
        };
    }
}
