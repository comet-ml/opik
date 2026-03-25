package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.LlmProvider;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class LlmModelRegistryService {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, List<LlmModelDefinition>>> REGISTRY_TYPE = new TypeReference<>() {
    };
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final LlmModelRegistryConfig config;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    // volatile: reload() is called from the scheduler thread
    private volatile Map<String, List<LlmModelDefinition>> registry;

    @Inject
    public LlmModelRegistryService(@NonNull @Config OpikConfiguration configuration) {
        this(configuration.getLlmModelRegistry());
    }

    // visible for testing
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config) {
        this.config = config;
        this.registry = load();

        if (isRemoteConfigured()) {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "llm-registry-refresh");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::reload,
                    config.getRefreshIntervalSeconds(),
                    config.getRefreshIntervalSeconds(),
                    TimeUnit.SECONDS);
            log.info("LLM model registry remote refresh scheduled every '{}' seconds from '{}'",
                    config.getRefreshIntervalSeconds(), config.getRemoteUrl());
        } else {
            this.httpClient = null;
            this.scheduler = null;
        }
    }

    public record ModelLookupResult(@NonNull LlmProvider provider, @NonNull LlmModelDefinition model) {
    }

    public Map<String, List<LlmModelDefinition>> getRegistry() {
        return registry;
    }

    public Optional<ModelLookupResult> findModel(@NonNull String model) {
        var snapshot = this.registry;

        // Pass 1: match by qualifiedName (e.g., "vertex_ai/gemini-2.5-flash")
        for (var entry : snapshot.entrySet()) {
            for (var m : entry.getValue()) {
                if (model.equals(m.qualifiedName())) {
                    var resolved = resolve(entry.getKey(), m);
                    if (resolved.isPresent()) {
                        return resolved;
                    }
                }
            }
        }
        // Pass 2: match by id, skipping models that have a qualifiedName to avoid ambiguity
        // (e.g., "gemini-2.5-flash" exists in both gemini and vertex-ai; only gemini should match by bare id)
        for (var entry : snapshot.entrySet()) {
            for (var m : entry.getValue()) {
                if (m.qualifiedName() == null && model.equals(m.id())) {
                    var resolved = resolve(entry.getKey(), m);
                    if (resolved.isPresent()) {
                        return resolved;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ModelLookupResult> resolve(String providerKey, LlmModelDefinition model) {
        try {
            var provider = LlmProvider.fromString(providerKey);
            return Optional.of(new ModelLookupResult(provider, model));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown provider key '{}' in registry, skipping", providerKey);
            return Optional.empty();
        }
    }

    public void reload() {
        try {
            registry = load();
            log.info("LLM model registry reloaded successfully");
        } catch (Exception e) {
            log.error("Failed to reload LLM model registry, keeping previous version", e);
        }
    }

    private Map<String, List<LlmModelDefinition>> load() {
        var result = loadClasspathResource(config.getDefaultResource());

        if (isRemoteConfigured() && httpClient != null) {
            try {
                var remote = loadRemoteResource(config.getRemoteUrl());
                result = merge(result, remote);
                log.debug("Merged remote model registry from '{}'", config.getRemoteUrl());
            } catch (Exception e) {
                log.warn("Failed to fetch remote model registry from '{}', using classpath defaults",
                        config.getRemoteUrl(), e);
            }
        }

        var overridePath = config.getLocalOverridePath();
        if (overridePath == null || overridePath.isBlank()) {
            return immutable(result);
        }

        var path = Path.of(overridePath);
        if (!Files.exists(path)) {
            log.debug("Local override file not found at '{}', using defaults only", overridePath);
            return immutable(result);
        }

        var overrides = loadFileResource(path);
        return Map.copyOf(merge(result, overrides));
    }

    private boolean isRemoteConfigured() {
        return config.isRemoteEnabled()
                && config.getRemoteUrl() != null
                && !config.getRemoteUrl().isBlank();
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

    private Map<String, List<LlmModelDefinition>> loadRemoteResource(String url) {
        var uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Remote registry URL must use http or https scheme: " + url);
        }

        try {
            var request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP %d from '%s'".formatted(response.statusCode(), url));
            }

            try (var body = response.body()) {
                return YAML_MAPPER.readValue(body, REGISTRY_TYPE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching remote model registry", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch remote model registry from: " + url, e);
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
