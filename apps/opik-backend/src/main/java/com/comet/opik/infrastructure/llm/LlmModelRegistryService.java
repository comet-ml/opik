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
import com.google.common.base.Suppliers;
import jakarta.annotation.Nullable;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class LlmModelRegistryService {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, List<ModelEntry>>> REGISTRY_TYPE = new TypeReference<>() {
    };
    private static final CuratedCatalog NO_CATALOG = CuratedCatalog.builder().build();

    private final LlmModelRegistryConfig config;
    private final Client httpClient;
    private final Supplier<CuratedCatalog> shippedCatalog = Suppliers.memoize(
            () -> readCuratedCatalog(new LlmModelRegistryConfig().getCuratedResource()));
    // volatile: reload() is called from the scheduled job thread
    private volatile Snapshot snapshot;
    private volatile Map<String, List<ModelEntry>> lastGoodRemote;
    private final Set<String> reportedAwaitingSyncRows = ConcurrentHashMap.newKeySet();

    @Inject
    public LlmModelRegistryService(@NonNull @Config OpikConfiguration configuration,
            @NonNull Client httpClient) {
        this(configuration.getLlmModelRegistry(), httpClient);
    }

    // visible for testing
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config, @NonNull Client httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.snapshot = load();
    }

    // visible for testing (no remote fetch)
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config) {
        this.config = config;
        this.httpClient = null;
        this.snapshot = load();
    }

    public record ModelLookupResult(@NonNull LlmProvider provider, @NonNull LlmModelDefinition model) {
    }

    // One reference, so a reload swaps the models and the defaults they were resolved with together.
    private record Snapshot(Map<String, List<LlmModelDefinition>> registry, CuratedCatalog catalog) {
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

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProviderDefaults(String profile, String reasoningProfile) {
    }

    @Builder(toBuilder = true)
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

        /**
         * Each layer's profile, merged into that layer's params before the layers are merged, so a layer's profile
         * outranks every layer below it: a curated {@code profile} wins over the params the sync generated.
         */
        Map<String, List<ModelEntry>> foldProfiles(Map<String, List<ModelEntry>> layer) {
            var folded = new LinkedHashMap<String, List<ModelEntry>>(layer.size());
            layer.forEach((provider, rows) -> folded.put(provider, rows == null
                    ? null
                    : rows.stream()
                            .map(row -> row == null || row.profile() == null
                                    ? row
                                    : row.toBuilder()
                                            .profile(null)
                                            .params(ModelParams.overlay(profile(row.profile(), provider, row),
                                                    row.params()))
                                            .build())
                            .toList()));
            return folded;
        }

        /** Provider profile, then the reasoning profile, then the model's own profile, then its own params. */
        ModelParams resolve(String provider, ModelEntry entry) {
            var providerDefaults = defaults.get(provider);
            ModelParams resolved = null;
            if (providerDefaults != null) {
                resolved = profile(providerDefaults.profile(), provider, entry);
                if (BooleanUtils.isTrue(entry.reasoning()) && providerDefaults.reasoningProfile() != null) {
                    resolved = ModelParams.overlay(resolved,
                            profile(providerDefaults.reasoningProfile(), provider, entry));
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
        return snapshot.registry();
    }

    public Optional<ModelLookupResult> findModel(@NonNull String model) {
        var registry = snapshot.registry();

        // Pass 1: match by qualifiedName (e.g., "vertex_ai/gemini-2.5-flash")
        for (var entry : registry.entrySet()) {
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
        for (var entry : registry.entrySet()) {
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
            snapshot = load();
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

    private Snapshot load() {
        var catalog = loadCuratedCatalog();
        var result = catalog.foldProfiles(loadClasspathResource(config.getDefaultResource()));

        if (isRemoteConfigured() && httpClient != null) {
            try {
                var remote = loadRemoteResource(config.getRemoteUrl());
                result = merge(result, catalog.foldProfiles(remote));
                lastGoodRemote = remote;
                log.debug("Merged remote model registry from '{}'", config.getRemoteUrl());
            } catch (Exception e) {
                if (lastGoodRemote == null) {
                    log.warn("Failed to fetch remote model registry from '{}', using classpath defaults",
                            config.getRemoteUrl(), e);
                } else {
                    log.warn("Failed to fetch remote model registry from '{}', keeping the last good copy",
                            config.getRemoteUrl(), e);
                    result = merge(result, catalog.foldProfiles(lastGoodRemote));
                }
            }
        }

        result = applyCurated(result, catalog.foldProfiles(catalog.models()));
        reportAwaitingSyncRowsTheSyncLists(result, catalog.models());

        var overridePath = config.getLocalOverridePath();
        if (overridePath == null || overridePath.isBlank()) {
            return new Snapshot(toRegistry(result, catalog), catalog);
        }

        var path = Path.of(overridePath);
        if (!Files.exists(path)) {
            log.debug("Local override file not found at '{}', using defaults only", overridePath);
            return new Snapshot(toRegistry(result, catalog), catalog);
        }

        try {
            var overrides = catalog.foldProfiles(loadFileResource(path));
            return new Snapshot(toRegistry(merge(result, overrides), catalog), catalog);
        } catch (Exception e) {
            log.error("Failed to load local override registry from '{}', falling back to defaults",
                    overridePath, e);
            return new Snapshot(toRegistry(result, catalog), catalog);
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

    /**
     * The params the registry serves for a model id, the same ones the API lists; an unlisted or null id gets its
     * provider's profile. Null when no layer and no profile says anything about the model, as for a provider the
     * catalog has no profile for.
     *
     * <p>A deployment that turns the curated layer off still gets the shipped rules for a model no layer gives
     * params, so turning it off cannot make a reasoning or Claude model fail with a 400.
     */
    public @Nullable ModelParams paramsOf(@NonNull String provider, @Nullable String modelId) {
        var current = snapshot;
        var listed = current.registry().getOrDefault(provider, List.of()).stream()
                .filter(model -> model.id() != null && model.id().equals(modelId))
                .findFirst();
        if (listed.isPresent() && listed.get().params() != null) {
            return listed.get().params();
        }
        var row = ModelEntry.builder()
                .id(modelId)
                .reasoning(listed.map(LlmModelDefinition::reasoning).orElse(false))
                .build();
        return ObjectUtils.getIfNull(current.catalog().resolve(provider, row), () -> shippedParams(provider, row));
    }

    private ModelParams shippedParams(String provider, ModelEntry row) {
        var shipped = shippedCatalog.get();
        var shippedRow = shipped.models().getOrDefault(provider, List.of()).stream()
                .filter(m -> m.id() != null && m.id().equals(row.id()))
                .findFirst()
                .map(m -> m.toBuilder().reasoning(row.reasoning()).build())
                .orElse(row);
        return shipped.resolve(provider, shippedRow);
    }

    private CuratedCatalog loadCuratedCatalog() {
        return readCuratedCatalog(config.getCuratedResource());
    }

    private static CuratedCatalog readCuratedCatalog(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return NO_CATALOG;
        }
        return readClasspathYaml(resourceName, new TypeReference<>() {
        });
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
            rows.forEach(row -> {
                if (row == null || row.id() == null || row.id().isBlank()) {
                    log.warn("Skipping curated row with missing id for provider '{}'", provider);
                    return;
                }
                if (byId.containsKey(row.id())) {
                    log.warn("Curated model '{}' of provider '{}' is listed twice; the later row wins only where it"
                            + " sets a field", row.id(), provider);
                }
                byId.merge(row.id(), row, LlmModelRegistryService::inheritUnsetFields);
            });
            result.put(provider, existing.stream()
                    .map(m -> byId.containsKey(m.id()) ? inheritUnsetFields(m, byId.get(m.id())) : m)
                    .toList());
        });
        return result;
    }

    // The registry reloads every few minutes and a release's curated file never changes, so once the CDN lists the
    // model this would otherwise repeat at INFO until the deployment upgrades.
    private void reportAwaitingSyncRowsTheSyncLists(Map<String, List<ModelEntry>> merged,
            Map<String, List<ModelEntry>> curated) {
        curated.forEach((provider, rows) -> {
            if (rows == null) {
                return;
            }
            var listed = merged.getOrDefault(provider, List.of()).stream()
                    .map(ModelEntry::id)
                    .collect(Collectors.toSet());
            rows.stream()
                    .filter(row -> row != null && BooleanUtils.isTrue(row.awaitingSync()) && listed.contains(row.id()))
                    .forEach(row -> {
                        var message = "Curated row '{}' of provider '{}' waits for the sync, which now lists the"
                                + " model; its awaitingSync can be removed";
                        if (reportedAwaitingSyncRows.add(provider + "/" + row.id())) {
                            log.info(message, row.id(), provider);
                        } else {
                            log.debug(message, row.id(), provider);
                        }
                    });
        });
    }

    private Map<String, List<ModelEntry>> loadClasspathResource(String resourceName) {
        return readClasspathYaml(resourceName, REGISTRY_TYPE);
    }

    private static <T> T readClasspathYaml(String resourceName, TypeReference<T> type) {
        try (InputStream is = LlmModelRegistryService.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Classpath resource not found: " + resourceName);
            }
            return YAML_MAPPER.readValue(is, type);
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
