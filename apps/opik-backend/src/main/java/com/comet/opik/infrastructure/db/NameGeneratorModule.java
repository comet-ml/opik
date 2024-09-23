package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.NameGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

import java.io.FileNotFoundException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;

public class NameGeneratorModule extends DropwizardAwareModule<OpikConfiguration> {

    private static final TypeReference<List<String>> STRING_LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    @Provides
    @Singleton
    public NameGenerator getNameGenerator() throws FileNotFoundException, NoSuchAlgorithmException {
        return NameGenerator.builder()
                .secureRandom(SecureRandom.getInstanceStrong())
                .adjectives(getResource("/name-generator/adjectives.json"))
                .nouns(getResource("/name-generator/nouns.json"))
                .build();
    }

    private List<String> getResource(String path) throws FileNotFoundException {
        var inputStream = NameGeneratorModule.class.getResourceAsStream(path);
        if (inputStream == null) {
            throw new FileNotFoundException("Resource not found in path '%s'".formatted(path));
        }
        return JsonUtils.readValue(inputStream, NameGeneratorModule.STRING_LIST_TYPE_REFERENCE);
    }
}
