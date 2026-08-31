package com.comet.opik.infrastructure.bundle;

import com.comet.opik.OpikApplication;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.StreamReadConstraints;
import io.dropwizard.core.ConfiguredBundle;
import io.dropwizard.core.setup.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.vyarus.dropwizard.guice.GuiceBundle;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the startup ordering that decides which Jackson limits the Redis stream codec ends
 * up with. Getting this wrong does not lose a message, it wedges the stream: an entry above the codec's
 * limit is written happily and then fails to decode inside Redisson, below our error handling and with no
 * StreamMessageId, so it can never be acked, retried or removed.
 */
@DisplayName("JsonUtils configuration bundle")
class JsonUtilsConfigurationBundleTest {

    private static final int MB = 1024 * 1024;
    private static final int CONFIGURED_STRING_LIMIT = 100 * MB;
    private static final long CONFIGURED_DOCUMENT_LIMIT = 200L * MB;

    @AfterEach
    void restoreDefaults() {
        // JsonUtils' mapper is process-wide static state; put it back so test order can't leak.
        JsonUtils.configure(StreamReadConstraints.DEFAULT_MAX_STRING_LEN, -1L);
    }

    @Test
    @DisplayName("run() applies the configured limits to the shared mapper")
    void runAppliesConfiguredLimits() {
        var configuration = new OpikConfiguration();
        configuration.getJacksonConfig().setMaxStringLength(CONFIGURED_STRING_LIMIT);
        configuration.getJacksonConfig().setMaxDocumentLength(CONFIGURED_DOCUMENT_LIMIT);

        new JsonUtilsConfigurationBundle().run(configuration, null);

        var constraints = JsonUtils.getMapper().getFactory().streamReadConstraints();
        assertThat(constraints.getMaxStringLength()).isEqualTo(CONFIGURED_STRING_LIMIT);
        assertThat(constraints.getMaxDocumentLength()).isEqualTo(CONFIGURED_DOCUMENT_LIMIT);
    }

    @Test
    @DisplayName("is registered before the GuiceBundle, which is when the codecs capture the mapper")
    void isRegisteredBeforeTheGuiceBundle() {
        var registrationOrder = new ArrayList<ConfiguredBundle<? super OpikConfiguration>>();
        var application = new OpikApplication();
        var bootstrap = new Bootstrap<OpikConfiguration>(application) {
            @Override
            public void addBundle(ConfiguredBundle<? super OpikConfiguration> bundle) {
                registrationOrder.add(bundle);
                super.addBundle(bundle);
            }
        };

        application.initialize(bootstrap);

        int guiceIndex = indexOfType(registrationOrder, GuiceBundle.class);
        int jsonUtilsIndex = indexOfType(registrationOrder, JsonUtilsConfigurationBundle.class);

        // Asserted, not assumed: if either bundle stopped being registered at all the ordering assertion
        // below would pass vacuously on two -1s.
        assertThat(guiceIndex).as("GuiceBundle must be registered").isNotNegative();
        assertThat(jsonUtilsIndex).as("JsonUtilsConfigurationBundle must be registered").isNotNegative();

        // Dropwizard runs configured bundles in registration order, so this is run order.
        assertThat(jsonUtilsIndex)
                .as("JsonUtils must be configured before the Guice injector builds RedisConfig and "
                        + "RedisStreamCodec, which capture the mapper as it is at that moment")
                .isLessThan(guiceIndex);
    }

    private int indexOfType(List<? extends ConfiguredBundle<? super OpikConfiguration>> bundles, Class<?> type) {
        for (int i = 0; i < bundles.size(); i++) {
            if (type.isInstance(bundles.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
