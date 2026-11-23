package com.comet.opik.infrastructure;

import com.comet.opik.OpikApplication;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import lombok.experimental.UtilityClass;
import org.apache.commons.text.lookup.StringLookupFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped config.yml, bound as OpikApplication binds it, with the environment given as a map. */
@UtilityClass
public class ShippedConfig {

    private static final Path CONFIG = Path.of("config.yml");

    public static OpikConfiguration load(Map<String, String> environment) throws Exception {
        assertThat(Files.exists(CONFIG)).as("config.yml must resolve from the module root").isTrue();
        // OpikApplication's substitutor, reading a map rather than the real environment, so the result does not depend
        // on what happens to be exported on the machine.
        var substitutor = OpikApplication.newSubstitutor();
        substitutor.setVariableResolver(StringLookupFactory.INSTANCE.mapStringLookup(environment));
        // No validator: this asserts what the YAML binds to, not whether every unrelated section is valid without its
        // own environment.
        var factory = new YamlConfigurationFactory<>(OpikConfiguration.class, null, Jackson.newObjectMapper(), "dw");
        return factory.build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor),
                CONFIG.toString());
    }
}
