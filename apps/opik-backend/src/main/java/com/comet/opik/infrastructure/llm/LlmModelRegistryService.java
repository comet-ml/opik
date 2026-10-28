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
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
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
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Singleton
public class LlmModelRegistryService {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<Map<String, List<ModelEntry>>> REGISTRY_TYPE = new TypeReference<>() {
    };

    private final LlmModelRegistryConfig config;
    private final Client httpClient;
    private final Supplier<CuratedCatalog> shippedCatalog = Suppliers.memoize(
            () -> readCuratedCatalog(new LlmModelRegistryConfig().getCuratedResource()));
    private final CuratedCatalog catalog;
    // volatile: reload() is called from the scheduled job thread
    private volatile Map<String, List<LlmModelDefinition>> registry;
    private volatile Map<String, List<ModelEntry>> lastGoodRemote;
    private final Clock clock;
    private volatile ServedRegistry served;
    private final Set<String> reportedAwaitingSyncRows = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedUnknownProfiles = ConcurrentHashMap.newKeySet();
    // Kept across reloads because the curated files ship with the release; the reasoning flag, which a reload can
    // change, is part of the key. Bounded because the effort check asks with whatever model name a request carries.
    private final LoadingCache<CuratedParamsKey, Optional<ModelParams>> curatedParams = CacheBuilder.newBuilder()
            .maximumSize(1_000)
            .build(CacheLoader.from(this::resolveCuratedParams));

    private record CuratedParamsKey(String provider, String modelId, boolean reasoning) {
    }

    private record ServedRegistry(Map<String, List<LlmModelDefinition>> source, LocalDate day,
            Map<String, List<LlmModelDefinition>> registry) {
    }

    @Inject
    public LlmModelRegistryService(@NonNull @Config OpikConfiguration configuration,
            @NonNull Client httpClient) {
        this(configuration.getLlmModelRegistry(), httpClient);
    }

    // visible for testing
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config, @NonNull Client httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.clock = Clock.systemUTC();
        this.catalog = loadCuratedCatalog();
        this.registry = load();
    }

    // visible for testing (no remote fetch)
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config) {
        this(config, Clock.systemUTC());
    }

    // visible for testing (no remote fetch)
    LlmModelRegistryService(@NonNull LlmModelRegistryConfig config, @NonNull Clock clock) {
        this.config = config;
        this.httpClient = null;
        this.clock = clock;
        this.catalog = loadCuratedCatalog();
        this.registry = load();
    }

    public record ModelLookupResult(@NonNull LlmProvider provider, @NonNull LlmModelDefinition model) {
    }

    // Separate from LlmModelDefinition because merge needs nullable flags: a primitive can't tell a flag a YAML
    // layer leaves out from an explicit false.
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelEntry(String id, String qualifiedName, String label, Boolean structuredOutput, Boolean reasoning,
            List<String> supportedParameters, List<String> reasoningEfforts, String profile, ModelParams params,
            Boolean awaitingSync, Boolean hidden, String retiresOn) {

        LlmModelDefinition toDefinition(ModelParams resolvedParams) {
            return LlmModelDefinition.builder()
                    .id(id)
                    .qualifiedName(qualifiedName)
                    // The picker shows a model when it has a label, and a layer that leaves the label out keeps the
                    // one below, so hiding needs its own field: an upper layer (the CDN) can then take a model out.
                    .label(BooleanUtils.isTrue(hidden) ? null : label)
                    .structuredOutput(BooleanUtils.isTrue(structuredOutput))
                    .reasoning(BooleanUtils.isTrue(reasoning))
                    .supportedParameters(supportedParameters)
                    .reasoningEfforts(reasoningEfforts)
                    .params(resolvedParams)
                    .retiresOn(isoDateOrNull(id, retiresOn))
                    .build();
        }

        private static String isoDateOrNull(String id, String value) {
            if (StringUtils.isBlank(value)) {
                return null;
            }
            try {
                return LocalDate.parse(value.strip()).toString();
            } catch (DateTimeParseException e) {
                log.warn("Ignoring retiresOn '{}' of model '{}': not an ISO date", value, id);
                return null;
            }
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProviderDefaults(String profile, String reasoningProfile, List<IdProfile> idProfiles) {

        ProviderDefaults {
            idProfiles = idProfiles == null ? List.of() : idProfiles;
        }

        String idProfile(String modelId) {
            if (modelId == null) {
                return null;
            }
            return idProfiles.stream()
                    .filter(idProfile -> idProfile.match().matcher(modelId).find())
                    .map(IdProfile::profile)
                    .findFirst()
                    .orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IdProfile(@NonNull Pattern match, @NonNull String profile) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CuratedCatalog(
            Map<String, ProviderDefaults> defaults,
            Map<String, ModelParams> profiles,
            Map<String, List<ModelEntry>> models) {

        static final CuratedCatalog EMPTY = CuratedCatalog.builder().build();

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
                    : rows.stream().map(this::foldProfile).toList()));
            return folded;
        }

        ModelEntry foldProfile(ModelEntry row) {
            if (row == null || row.profile() == null) {
                return row;
            }
            return row.toBuilder()
                    .profile(null)
                    .params(ModelParams.overlay(profile(row.profile()), row.params()))
                    .build();
        }

        /**
         * Provider profile, then the profile its id matches, then the reasoning profile, then the model's own params,
         * into which {@link #foldProfiles} has already merged the model's own profile.
         */
        ModelParams resolve(String provider, ModelEntry entry) {
            var providerDefaults = defaults.get(provider);
            ModelParams resolved = null;
            if (providerDefaults != null) {
                resolved = profile(providerDefaults.profile());
                resolved = ModelParams.overlay(resolved, profile(providerDefaults.idProfile(entry.id())));
                if (BooleanUtils.isTrue(entry.reasoning())) {
                    resolved = ModelParams.overlay(resolved, profile(providerDefaults.reasoningProfile()));
                }
            }
            resolved = ModelParams.overlay(resolved, entry.params());
            return resolved == null ? null : resolved.settled();
        }

        private ModelParams profile(String name) {
            return name == null ? null : profiles.get(name);
        }

        Optional<ModelEntry> row(String provider, String modelId) {
            return models.getOrDefault(provider, List.of()).stream()
                    .filter(row -> row != null && row.id() != null && row.id().equals(modelId))
                    .findFirst();
        }

        // The file ships with the release, so a name it gets wrong is a bug to stop at startup, not a warning
        // to repeat on every reload.
        CuratedCatalog requireKnownProfiles() {
            var named = Stream.concat(
                    defaults.values().stream()
                            .filter(Objects::nonNull)
                            .flatMap(d -> Stream.concat(Stream.of(d.profile(), d.reasoningProfile()),
                                    d.idProfiles().stream().map(IdProfile::profile))),
                    models.values().stream()
                            .filter(Objects::nonNull)
                            .flatMap(List::stream)
                            .filter(Objects::nonNull)
                            .map(ModelEntry::profile));
            var unknown = named.filter(Objects::nonNull)
                    .filter(name -> !profiles.containsKey(name))
                    .distinct()
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalStateException(
                        "The curated model catalog names params profiles it doesn't define: " + unknown);
            }
            return this;
        }
    }

    /**
     * The registry the picker reads: a model whose retirement date has come is served without a label, so it leaves
     * the picker that day even before a sync marks it hidden. Lookups keep it, so saved prompts and rules still
     * resolve. Checked here rather than at load because the refresh job only reloads with a remote copy configured,
     * so a self-hosted backend may serve one load for weeks.
     */
    public Map<String, List<LlmModelDefinition>> getRegistry() {
        var source = registry;
        var today = LocalDate.now(clock);
        var current = served;
        if (current == null || current.source() != source || !current.day().equals(today)) {
            current = new ServedRegistry(source, today, withoutRetiredLabels(source, today));
            served = current;
        }
        return current.registry();
    }

    private static Map<String, List<LlmModelDefinition>> withoutRetiredLabels(
            Map<String, List<LlmModelDefinition>> source, LocalDate today) {
        var copy = new LinkedHashMap<String, List<LlmModelDefinition>>(source.size());
        source.forEach((provider, models) -> copy.put(provider, models.stream()
                .map(m -> m.label() != null && m.retiresOn() != null
                        && !LocalDate.parse(m.retiresOn()).isAfter(today)
                                ? m.toBuilder().label(null).build()
                                : m)
                .toList()));
        return Collections.unmodifiableMap(copy);
    }

    public Map<String, ModelParams> getProfiles() {
        var profiles = new LinkedHashMap<String, ModelParams>();
        for (var provider : LlmProvider.values()) {
            var params = paramsOf(provider.getValue(), null);
            if (params != null) {
                profiles.put(provider.getValue(), params);
            }
        }
        return profiles;
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
                && StringUtils.isNotBlank(config.getRemoteUrl());
    }

    private Map<String, List<LlmModelDefinition>> load() {
        var result = foldLayerProfiles(loadClasspathResource(config.getDefaultResource()));

        if (isRemoteConfigured() && httpClient != null) {
            try {
                var remote = loadRemoteResource(config.getRemoteUrl());
                result = merge(result, foldLayerProfiles(remote));
                lastGoodRemote = remote;
                log.debug("Merged remote model registry from '{}'", config.getRemoteUrl());
            } catch (Exception e) {
                if (lastGoodRemote == null) {
                    log.warn("Failed to fetch remote model registry from '{}', using classpath defaults",
                            config.getRemoteUrl(), e);
                } else {
                    log.warn("Failed to fetch remote model registry from '{}', keeping the last good copy",
                            config.getRemoteUrl(), e);
                    result = merge(result, foldLayerProfiles(lastGoodRemote));
                }
            }
        }

        result = applyCurated(result, catalog.foldProfiles(catalog.models()));
        reportSyncedAwaitingRows(result, catalog.models());

        var overridePath = config.getLocalOverridePath();
        if (StringUtils.isBlank(overridePath)) {
            return toRegistry(result);
        }

        var path = Path.of(overridePath);
        if (!Files.exists(path)) {
            log.debug("Local override file not found at '{}', using defaults only", overridePath);
            return toRegistry(result);
        }

        try {
            var overrides = foldLayerProfiles(loadFileResource(path));
            return toRegistry(merge(result, overrides));
        } catch (Exception e) {
            log.error("Failed to load local override registry from '{}', falling back to defaults",
                    overridePath, e);
            return toRegistry(result);
        }
    }

    private Map<String, List<LlmModelDefinition>> toRegistry(Map<String, List<ModelEntry>> merged) {
        var copy = new LinkedHashMap<String, List<LlmModelDefinition>>(merged.size());
        merged.forEach((provider, models) -> copy.put(provider,
                models.stream().map(m -> m.toDefinition(resolve(provider, m))).toList()));
        // Not Map.copyOf: its iteration order changes between JVM starts, and the API lists providers in it.
        return Collections.unmodifiableMap(copy);
    }

    /**
     * A deployment that turns the curated layer off still resolves against the shipped profiles and rows, placed
     * under every other layer. Without them a row that a layer gives only some params keeps only those, and a
     * missing field reads as refused: a Claude model would lose its temperature and every effort level.
     */
    private ModelParams resolve(String provider, ModelEntry entry) {
        if (!curatedLayerOff()) {
            return catalog.resolve(provider, entry);
        }
        var shipped = shippedCatalog.get();
        var shippedParams = shipped.row(provider, entry.id())
                .map(shipped::foldProfile)
                .map(ModelEntry::params)
                .orElse(null);
        return shipped.resolve(provider,
                entry.toBuilder().params(ModelParams.overlay(shippedParams, entry.params())).build());
    }

    /**
     * The params the registry serves for a model id, the same ones the API lists; an unlisted or null id gets its
     * provider's profile. Null when no layer and no profile says anything about the model, as for a provider the
     * catalog has no profile for.
     */
    public @Nullable ModelParams paramsOf(@NonNull String provider, @Nullable String modelId) {
        var listed = listed(provider, modelId);
        if (listed.isPresent() && listed.get().params() != null) {
            return listed.get().params();
        }
        var reasoning = listed.map(LlmModelDefinition::reasoning).orElse(false);
        return resolve(provider, ModelEntry.builder().id(modelId).reasoning(reasoning).build());
    }

    /**
     * The params the release's own rules give a model: the curated row and profiles (the shipped ones when the
     * curated layer is off), without the generated, CDN or local-override layers. The effort check, which rejects
     * a request, reads these instead of {@link #paramsOf}, and the Claude sampling rules read them next to it as a
     * veto, so a bad row in remote data can cost a param but never fail every request for a model.
     */
    public @Nullable ModelParams curatedParamsOf(@NonNull String provider, @Nullable String modelId) {
        var reasoning = listed(provider, modelId).map(LlmModelDefinition::reasoning).orElse(false);
        return curatedParams.getUnchecked(new CuratedParamsKey(provider, modelId, reasoning)).orElse(null);
    }

    private Optional<ModelParams> resolveCuratedParams(CuratedParamsKey key) {
        var rules = curatedLayerOff() ? shippedCatalog.get() : catalog;
        var row = rules.row(key.provider(), key.modelId())
                .map(curated -> curated.toBuilder().reasoning(key.reasoning()).build())
                .orElseGet(() -> ModelEntry.builder().id(key.modelId()).reasoning(key.reasoning()).build());
        return Optional.ofNullable(rules.resolve(key.provider(), rules.foldProfile(row)));
    }

    private boolean curatedLayerOff() {
        return StringUtils.isBlank(config.getCuratedResource());
    }

    private Optional<LlmModelDefinition> listed(String provider, String modelId) {
        return registry.getOrDefault(provider, List.of()).stream()
                .filter(model -> model.id() != null && model.id().equals(modelId))
                .findFirst();
    }

    private CuratedCatalog loadCuratedCatalog() {
        return readCuratedCatalog(config.getCuratedResource());
    }

    private static CuratedCatalog readCuratedCatalog(String resourceName) {
        if (StringUtils.isBlank(resourceName)) {
            return CuratedCatalog.EMPTY;
        }
        return readClasspathYaml(resourceName, new TypeReference<CuratedCatalog>() {
        }).requireKnownProfiles();
    }

    private Map<String, List<ModelEntry>> foldLayerProfiles(Map<String, List<ModelEntry>> layer) {
        reportUnknownProfiles(layer);
        return catalog.foldProfiles(layer);
    }

    // A generated layer or the local override is data, so a profile it names wrongly is skipped rather than
    // failing the load, and the reload would otherwise repeat the warning every few minutes.
    private void reportUnknownProfiles(Map<String, List<ModelEntry>> layer) {
        layer.forEach((provider, rows) -> {
            if (rows == null) {
                return;
            }
            rows.stream()
                    .filter(row -> row != null && row.profile() != null
                            && !catalog.profiles().containsKey(row.profile()))
                    .forEach(row -> {
                        var message = "Unknown params profile '{}' for model '{}' of provider '{}', skipping it";
                        if (reportedUnknownProfiles.add(provider + "/" + row.id() + "/" + row.profile())) {
                            log.warn(message, row.profile(), row.id(), provider);
                        } else {
                            log.debug(message, row.profile(), row.id(), provider);
                        }
                    });
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
                if (row == null || StringUtils.isBlank(row.id())) {
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
    private void reportSyncedAwaitingRows(Map<String, List<ModelEntry>> merged,
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
                if (StringUtils.isBlank(m.id())) {
                    log.warn("Skipping default model with missing id for provider '{}'", provider);
                    return;
                }
                existingIds.put(m.id(), m);
            });

            overrideModels.forEach(m -> {
                if (StringUtils.isBlank(m.id())) {
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
    // field may change it. An empty list is set: it says the model takes none of the panel's parameters.
    private static ModelEntry inheritUnsetFields(ModelEntry existing, ModelEntry override) {
        return override.toBuilder()
                .qualifiedName(ObjectUtils.getIfNull(override.qualifiedName(), existing.qualifiedName()))
                .label(ObjectUtils.getIfNull(override.label(), existing.label()))
                .structuredOutput(ObjectUtils.getIfNull(override.structuredOutput(), existing.structuredOutput()))
                .reasoning(ObjectUtils.getIfNull(override.reasoning(), existing.reasoning()))
                .supportedParameters(
                        ObjectUtils.getIfNull(override.supportedParameters(), existing.supportedParameters()))
                .reasoningEfforts(ObjectUtils.getIfNull(override.reasoningEfforts(), existing.reasoningEfforts()))
                .profile(ObjectUtils.getIfNull(override.profile(), existing.profile()))
                .params(ModelParams.overlay(existing.params(), override.params()))
                .hidden(ObjectUtils.getIfNull(override.hidden(), existing.hidden()))
                .retiresOn(ObjectUtils.getIfNull(override.retiresOn(), existing.retiresOn()))
                .build();
    }
}
