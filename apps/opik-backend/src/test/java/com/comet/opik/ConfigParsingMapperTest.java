package com.comet.opik;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed app writes config classes returned as responses (ServiceTogglesConfig on /v1/private/toggles/) in
 * snake_case only because Dropwizard parses the config with a copy of the app's mapper. The test app doesn't, so the
 * test harness drops the serializers config parsing caches; this test fails if the deployed app ever stops copying.
 */
class ConfigParsingMapperTest {

    @Test
    void configParsingLeavesTheAppMapperWithoutCachedSerializers() {
        var mapper = Jackson.newObjectMapper();
        // The factory a deployed app parses config.yml with: Bootstrap's default, which OpikApplication keeps.
        new Bootstrap<>(new OpikApplication()).getConfigurationFactoryFactory()
                .create(OpikConfiguration.class, Validators.newValidator(), mapper, "dw");
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE);

        assertThat(mapper.valueToTree(new ServiceTogglesConfig()).has("ollie_enabled")).isTrue();
    }
}
