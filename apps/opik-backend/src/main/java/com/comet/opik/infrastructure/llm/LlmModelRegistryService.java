package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Singleton
public class LlmModelRegistryService {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, List<ModelEntry>>> REGISTRY_TYPE = new TypeReference<>() {
    };
    private static final CuratedCatalog NO_CATALOG = new CuratedCatalog(Map.of(), Map.of(), Map.of());

    private final LlmModelRegistryConfig config;
    private final Client httpClient;
    // volatile: reload() is called from the scheduled job thread
    private volatile Map<String, List<LlmModelDefinition>> registry;
    private volatile Map<String, List<ModelEntry>> lastGoodRemote;

    @Inject
    public LlmModelRegistryService(@NonNull @Config OpikConfiguration configuration,
            @NonNull Client httpClient) {
        this(configuration.getLlmModelRegistry(), httpClient);
    }

    // visible for testing
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config, @NonNull Client httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.registry = load();
    }

    // visible for testing (no remote fetch)
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config) {
        this.config = config;
        this.httpClient = null;
        this.registry = load();
    }

    public record ModelLookupResult(@NonNull LlmProvider provider, @NonNull LlmModelDefinition model) {
    }

    // Separate from LlmModelDefinition because merge needs nullable flags: a primitive can't tell a flag a YAML
    // layer leaves out from an explicit false.
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelEntry(String id, String qualifiedName, String label, Boolean structuredOutput, Boolean reasoning,
            String profile, ModelParams params, Boolean awaitingSync) {

        LlmModelDefinition toDefinition(ModelParams resolvedParams) {
            return LlmModelDefinition.builder()
                    .id(id)
                    .qualifiedName(qualifiedName)
                    .label(label)
                    .structuredOutput(BooleanUtils.isTrue(structuredOutput))
                    .reasoning(BooleanUtils.isTrue(reasoning))
                    .params(resolvedParams)
                    .build();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProviderDefaults(String profile, String reasoning) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CuratedCatalog(
            Map<String, ProviderDefaults> defaults,
            Map<String, ModelParams> profiles,
            Map<String, List<ModelEntry>> models) {

        CuratedCatalog {
            defaults = defaults == null ? Map.of() : defaults;
            profiles = profiles == null ? Map.of() : profiles;
            models = models == null ? Map.of() : models;
        }

        /** Provider profile, then the reasoning profile, then the model's own profile, then its own params. */
        ModelParams resolve(String provider, ModelEntry entry) {
            var providerDefaults = defaults.get(provider);
            ModelParams resolved = null;
            if (providerDefaults != null) {
                resolved = profile(providerDefaults.profile(), provider, entry);
                if (BooleanUtils.isTrue(entry.reasoning()) && providerDefaults.reasoning() != null) {
                    resolved = ModelParams.overlay(resolved, profile(providerDefaults.reasoning(), provider, entry));
                }
            }
            if (entry.profile() != null) {
                resolved = ModelParams.overlay(resolved, profile(entry.profile(), provider, entry));
            }
            resolved = ModelParams.overlay(resolved, entry.params());
            return resolved == null ? null : resolved.settled();
        }

        private ModelParams profile(String name, String provider, ModelEntry entry) {
            if (name == null) {
                return null;
            }
            var found = profiles.get(name);
            if (found == null) {
                log.warn("Unknown params profile '{}' for model '{}' of provider '{}', skipping it", name,
                        entry.id(), provider);
            }
            return found;
        }
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

    boolean isRemoteConfigured() {
        return config.isRemoteEnabled()
                && config.getRemoteUrl() != null
                && !config.getRemoteUrl().isBlank();
    }

    private Map<String, List<LlmModelDefinition>> load() {
        var catalog = loadCuratedCatalog();
        var result = loadClasspathResource(config.getDefaultResource());

        if (isRemoteConfigured() && httpClient != null) {
            try {
                var remote = loadRemoteResource(config.getRemoteUrl());
                result = merge(result, remote);
                lastGoodRemote = remote;
                log.debug("Merged remote model registry from '{}'", config.getRemoteUrl());
            } catch (Exception e) {
                if (lastGoodRemote == null) {
                    log.warn("Failed to fetch remote model registry from '{}', using classpath defaults",
                            config.getRemoteUrl(), e);
                } else {
                    log.warn("Failed to fetch remote model registry from '{}', keeping the last good copy",
                            config.getRemoteUrl(), e);
                    result = merge(result, lastGoodRemote);
                }
            }
        }

        result = applyCurated(result, catalog.models());

        var overridePath = config.getLocalOverridePath();
        if (overridePath == null || overridePath.isBlank()) {
            return toRegistry(result, catalog);
        }

        var path = Path.of(overridePath);
        if (!Files.exists(path)) {
            log.debug("Local override file not found at '{}', using defaults only", overridePath);
            return toRegistry(result, catalog);
        }

        try {
            var overrides = loadFileResource(path);
            return toRegistry(merge(result, overrides), catalog);
        } catch (Exception e) {
            log.error("Failed to load local override registry from '{}', falling back to defaults",
                    overridePath, e);
            return toRegistry(result, catalog);
        }
    }

    private static Map<String, List<LlmModelDefinition>> toRegistry(Map<String, List<ModelEntry>> merged,
            CuratedCatalog catalog) {
        var copy = new LinkedHashMap<String, List<LlmModelDefinition>>(merged.size());
        merged.forEach((provider, models) -> copy.put(provider,
                models.stream().map(m -> m.toDefinition(catalog.resolve(provider, m))).toList()));
        // Not Map.copyOf: its iteration order changes between JVM starts, and the API lists providers in it.
        return Collections.unmodifiableMap(copy);
    }

    private CuratedCatalog loadCuratedCatalog() {
        var resourceName = config.getCuratedResource();
        if (resourceName == null || resourceName.isBlank()) {
            return NO_CATALOG;
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IllegalStateException("Classpath resource not found: " + resourceName);
            }
            return YAML_MAPPER.readValue(is, CuratedCatalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load classpath resource: " + resourceName, e);
        }
    }

    // A curated row corrects a model the generated layers list; it never adds one, so a row written ahead of the
    // sync (awaitingSync) waits until the sync lists the model.
    static Map<String, List<ModelEntry>> applyCurated(
            @NonNull Map<String, List<ModelEntry>> generated,
            @NonNull Map<String, List<ModelEntry>> curated) {
        var result = new LinkedHashMap<>(generated);
        curated.forEach((provider, rows) -> {
            var existing = result.get(provider);
            if (existing == null || rows == null || rows.isEmpty()) {
                return;
            }
            var byId = new LinkedHashMap<String, ModelEntry>();
            rows.forEach(row -> byId.put(row.id(), row));
            result.put(provider, existing.stream()
                    .map(m -> byId.containsKey(m.id()) ? inheritUnsetFields(m, byId.get(m.id())) : m)
                    .toList());
        });
        return result;
    }

    private Map<String, List<ModelEntry>> loadClasspathResource(String resourceName) {
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

    private Map<String, List<ModelEntry>> loadFileResource(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            return YAML_MAPPER.readValue(is, REGISTRY_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load file: " + path, e);
        }
    }

    private Map<String, List<ModelEntry>> loadRemoteResource(String url) {
        var uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Remote registry URL must use http or https scheme: " + url);
        }

        try (Response response = httpClient.target(uri).request().get()) {
            if (response.getStatus() != 200) {
                throw new IOException("HTTP %d from '%s'".formatted(response.getStatus(), url));
            }

            try (InputStream body = response.readEntity(InputStream.class)) {
                return YAML_MAPPER.readValue(body, REGISTRY_TYPE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch remote model registry from: " + url, e);
        }
    }

    static Map<String, List<ModelEntry>> merge(
            @NonNull Map<String, List<ModelEntry>> defaults,
            @NonNull Map<String, List<ModelEntry>> overrides) {
        var result = new LinkedHashMap<>(defaults);

        overrides.forEach((provider, overrideModels) -> {
            if (overrideModels == null || overrideModels.isEmpty()) {
                return;
            }
            var existing = result.getOrDefault(provider, List.of());
            var existingIds = new LinkedHashMap<String, ModelEntry>();
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
                existingIds.merge(m.id(), m, LlmModelRegistryService::inheritUnsetFields);
            });

            result.put(provider, List.copyOf(existingIds.values()));
        });

        return result;
    }

    // An upper layer can lag the classpath file a release ships (the remote copy) or set only a few fields
    // (a local override). A field it leaves out keeps the lower layer's value, so only an entry that sets a
    // field may change it.
    private static ModelEntry inheritUnsetFields(ModelEntry existing, ModelEntry override) {
        return override.toBuilder()
                .qualifiedName(ObjectUtils.getIfNull(override.qualifiedName(), existing.qualifiedName()))
                .label(ObjectUtils.getIfNull(override.label(), existing.label()))
                .structuredOutput(ObjectUtils.getIfNull(override.structuredOutput(), existing.structuredOutput()))
                .reasoning(ObjectUtils.getIfNull(override.reasoning(), existing.reasoning()))
                .profile(ObjectUtils.getIfNull(override.profile(), existing.profile()))
                .params(ModelParams.overlay(existing.params(), override.params()))
                .build();
    }
}
