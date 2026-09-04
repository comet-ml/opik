package com.comet.opik.infrastructure.bundle;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.core.ConfiguredBundle;
import io.dropwizard.core.setup.Environment;

/**
 * Applies the configured Jackson stream-read limits to {@link JsonUtils}' shared mapper.
 *
 * <p><b>Must be registered before {@code GuiceBundle}.</b> Redisson's codecs capture the mapper while the
 * Guice injector is built, which happens in the bundle run phase - before {@code Application.run()}.
 * {@code RedisConfig} holds it by reference and {@code RedisStreamCodec} memoizes a {@code copy()}, while
 * {@link JsonUtils#configure(int, long)} <em>replaces</em> the static field, so a holder that captured the
 * mapper earlier keeps Jackson's defaults ({@code maxStringLength} 20,000,000) for the life of the process
 * no matter what {@code JACKSON_MAX_STRING_LENGTH} says.
 *
 * <p>The consequence of getting the order wrong is not a lost message but a lost stream: an entry over that
 * default can be written to a Redis stream and never read back, and the decode fails inside Redisson below
 * our error handling - with no {@code StreamMessageId}, so the entry can never be acked, retried or removed
 * and the stream wedges permanently.
 *
 * <p>This is its own bundle rather than a lambda in {@code OpikApplication.initialize} so the ordering
 * constraint is a thing a test can name and assert on - see {@code JsonUtilsConfigurationBundleTest}.
 */
public class JsonUtilsConfigurationBundle implements ConfiguredBundle<OpikConfiguration> {

    @Override
    public void run(OpikConfiguration configuration, Environment environment) {
        JsonUtils.configure(configuration.getJacksonConfig().getMaxStringLength(),
                configuration.getJacksonConfig().getMaxDocumentLength());
    }
}
