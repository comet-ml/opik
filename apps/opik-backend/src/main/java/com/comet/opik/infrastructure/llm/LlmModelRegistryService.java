package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Singleton
public class LlmModelRegistryService {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, List<LlmModelDefinition>>> REGISTRY_TYPE = new TypeReference<>() {
    };

    private final LlmModelRegistryConfig config;
    // volatile: reload() will be called from a scheduler thread (remote YAML refresh, OPIK-5020)
    private volatile Map<String, List<LlmModelDefinition>> registry;

    @Inject
    public LlmModelRegistryService(@NonNull @Config OpikConfiguration configuration) {
        this(configuration.getLlmModelRegistry());
    }

    // visible for testing
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config) {
        this.config = config;
        this.registry = load();
    }

    public Map<String, List<LlmModelDefinition>> getRegistry() {
        return registry;
    }

    public void reload() {
        try {
            registry = load();
            log.info("LLM model registry reloaded successfully");
        } catch (UncheckedIOException | IllegalStateException e) {
            log.error("Failed to reload LLM model registry, keeping previous version", e);
        }
    }

    private Map<String, List<LlmModelDefinition>> load() {
        var defaults = loadClasspathResource(config.getDefaultResource());
        var overridePath = config.getLocalOverridePath();

        if (overridePath == null || overridePath.isBlank()) {
            return immutable(defaults);
        }

        var path = Path.of(overridePath);
        if (!Files.exists(path)) {
            log.debug("Local override file not found at '{}', using defaults only", overridePath);
            return immutable(defaults);
        }

        var overrides = loadFileResource(path);
        return Map.copyOf(merge(defaults, overrides));
    }

    private static Map<String, List<LlmModelDefinition>> immutable(Map<String, List<LlmModelDefinition>> raw) {
        var copy = new LinkedHashMap<String, List<LlmModelDefinition>>(raw.size());
        raw.forEach((provider, models) -> copy.put(provider, List.copyOf(models)));
        return Map.copyOf(copy);
    }

    private Map<String, List<LlmModelDefinition>> loadClasspathResource(String resourceName) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Classpath resource not found: " + resourceName);
            }
            return YAML_MAPPER.readValue(is, REGISTRY_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load classpath resource: " + resourceName, e);
        }
    }

    private Map<String, List<LlmModelDefinition>> loadFileResource(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            return YAML_MAPPER.readValue(is, REGISTRY_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load file: " + path, e);
        }
    }

    static Map<String, List<LlmModelDefinition>> merge(
            @NonNull Map<String, List<LlmModelDefinition>> defaults,
            @NonNull Map<String, List<LlmModelDefinition>> overrides) {
        var result = new LinkedHashMap<>(defaults);

        overrides.forEach((provider, overrideModels) -> {
            if (overrideModels == null || overrideModels.isEmpty()) {
                return;
            }
            var existing = result.getOrDefault(provider, List.of());
            var existingIds = new LinkedHashMap<String, LlmModelDefinition>();
            existing.forEach(m -> {
                if (m.id() == null || m.id().isBlank()) {
                    log.warn("Skipping default model with missing id for provider '{}'", provider);
                    return;
                }
                existingIds.put(m.id(), m);
            });

            overrideModels.forEach(m -> {
                if (m.id() == null || m.id().isBlank()) {
                    log.warn("Skipping override model with missing id for provider '{}'", provider);
                    return;
                }
                existingIds.put(m.id(), m);
            });

            result.put(provider, List.copyOf(existingIds.values()));
        });

        return result;
    }
}
