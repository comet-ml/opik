package com.comet.opik.domain.llm;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModelName;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@UtilityClass
public class ModelCapabilities {

    // Hardcoded vision-capable models or patterns that should support vision/multimodal
    // This set of patterns overrides the JSON configuration to handle cases where:
    // - The JSON uses different naming conventions (e.g., openrouter/ prefix)
    // - Models are missing from the JSON
    // - The JSON is not yet updated with new vision models
    // - Custom LLM models that support multimodal (audio, video, images)
    private static final Set<Pattern> VISION_MODEL_PATTERNS = Set.of(
            // Made pattern more flexible to match anywhere in the name
            Pattern.compile(".*qwen.*vl.*", Pattern.CASE_INSENSITIVE),
            // Qwen Omni models support multimodal (audio, video, images)
            Pattern.compile(".*qwen.*omni.*", Pattern.CASE_INSENSITIVE));

    /**
     * Anthropic's Claude models reject temperature and top_p together. The family name is the only
     * signal common to every way they are routed — Anthropic's own ids ({@code claude-opus-4-6}),
     * Bedrock's decorated ids ({@code us.anthropic.claude-…-v1:0}) and OpenAI-compatible proxy names
     * — so the match is deliberately loose. A false positive only drops top_p when temperature is
     * also set, which is what Anthropic recommends regardless.
     */
    private static final Pattern EXCLUSIVE_SAMPLING_MODEL_PATTERN = Pattern.compile(".*claude.*",
            Pattern.CASE_INSENSITIVE);

    /** Anthropic dates a release as yyyyMMdd, e.g. claude-sonnet-4-5-20250929. */
    private static final int DATE_SUFFIX_LENGTH = 8;

    /**
     * The generations that predate the constraint, recognised by shape instead of being listed: they
     * all take sampling params, so the allow-list only has to name models from the generation where
     * taking none became the norm. Claude 3 and earlier spell the version before the family
     * ({@code claude-3-5-sonnet}) or omit it ({@code claude-instant}); Claude 4 onward spells it
     * after ({@code claude-sonnet-4-5}).
     */
    private static final Set<String> LEGACY_GENERATION_PREFIXES = Set.of(
            "claude-2", "claude-3", "claude-v2", "claude-instant");

    /**
     * The family words Anthropic actually ships, read off the enum so a sync that adds a family adds
     * it here too. An id whose family we do not recognise is not treated as an Anthropic id at all.
     */
    private static final Set<String> FAMILY_TOKENS = AnthropicModelName.allModelIds().stream()
            .map(id -> StringUtils.substringBefore(StringUtils.removeStart(id, "claude-"), "-"))
            .filter(token -> !StringUtils.isNumeric(token))
            .collect(Collectors.toUnmodifiableSet());

    private static final String LATEST_ALIAS_SUFFIX = "-latest";

    private static final Map<String, ModelCapability> CAPABILITIES_BY_NORMALIZED_NAME = loadCapabilities();

    /**
     * Checks if a model name matches any of the hardcoded vision patterns.
     */
    private boolean matchesVisionPattern(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return false;
        }
        return VISION_MODEL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(modelName).matches());
    }

    /**
     * Whether the model rejects temperature and top_p in the same request, so that only one may be sent.
     */
    public boolean requiresExclusiveSamplingParams(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return false;
        }
        return EXCLUSIVE_SAMPLING_MODEL_PATTERN.matcher(modelSegment(modelName)).matches();
    }

    /**
     * Whether the model refuses temperature and top_p outright, rather than merely refusing them
     * together. Anthropic's adaptive-thinking models answer a request carrying either with a 400.
     *
     * <p>An Anthropic id we cannot place is assumed to take none. Taking none is where the family is
     * heading, so the unplaceable id is far more often a model newer than this list than an older one
     * missing from it — and the two failures are not equal: assuming it takes none omits a parameter,
     * while assuming it takes them fails the whole request. The floating {@code claude-opus-latest}
     * aliases are the case that settles it, since they follow the newest model by definition.
     *
     * <p>A name that is not an Anthropic id at all is left alone — see {@link #canonicalAnthropicId}.
     */
    public boolean rejectsSamplingParams(String modelName) {
        var canonical = canonicalAnthropicId(modelName);
        if (canonical.isEmpty() || isLegacyGeneration(canonical)) {
            return false;
        }
        return knownAnthropicId(canonical)
                .map(id -> !AnthropicModelName.samplingCapableModelIds().contains(id))
                .orElse(true);
    }

    /**
     * The newest listed member of a family, which is what {@code claude-opus-latest} names.
     *
     * <p>A floating alias has to be read as the model it currently resolves to, not left unplaceable:
     * {@code claude-haiku-latest} is Haiku 4.5, which does take sampling params, and classifying it
     * by the unplaceable default would silently drop a temperature someone set — the same model under
     * its own id keeps one.
     */
    private Optional<String> newestInFamily(String familyPrefix) {
        return AnthropicModelName.allModelIds().stream()
                .filter(id -> id.startsWith(familyPrefix + "-"))
                .max(Comparator.comparing(ModelCapabilities::versionKey));
    }

    /**
     * Comparable version segments, with any release date dropped so {@code claude-opus-4-6} outranks
     * {@code claude-opus-4-20250514} instead of losing to the larger number.
     */
    private String versionKey(String modelId) {
        return Arrays.stream(modelId.split("-"))
                .filter(StringUtils::isNumeric)
                .filter(segment -> segment.length() != DATE_SUFFIX_LENGTH)
                .map(segment -> StringUtils.leftPad(segment, 4, '0'))
                .collect(Collectors.joining("."));
    }

    /**
     * The Anthropic id a routed model name denotes, when we know it.
     *
     * <p>One model arrives spelled three ways: Anthropic's own {@code claude-opus-4-6}, Bedrock's
     * {@code us.anthropic.claude-sonnet-4-5-20250929-v1:0} and OpenRouter's dotted
     * {@code anthropic/claude-opus-4.7}. Reducing all three to the bare id lets the match be anchored
     * at the start rather than found anywhere in the string, and the longest match wins so a later
     * {@code claude-opus-4-9} reads as itself rather than as the {@code claude-opus-4} it begins with.
     */
    private Optional<String> knownAnthropicId(String canonical) {
        if (canonical.endsWith(LATEST_ALIAS_SUFFIX)) {
            return newestInFamily(StringUtils.removeEnd(canonical, LATEST_ALIAS_SUFFIX));
        }
        return AnthropicModelName.allModelIds().stream()
                .filter(id -> namesModel(canonical, id))
                .max(Comparator.comparingInt(String::length));
    }

    /**
     * The bare Anthropic id a routed model name denotes, or empty when the name does not denote one.
     *
     * <p>Only a vendor decoration may precede the id: Bedrock's region and vendor prefix is a
     * statement about which Claude this is, whereas a proxy's own name for a model it renamed
     * ({@code my-claude-deployment}) is not, and must keep the sampling params someone set on it.
     */
    private String canonicalAnthropicId(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return "";
        }
        // normalize() also spells versions with hyphens, which is how OpenRouter's dots reach our ids.
        var segment = normalize(modelSegment(modelName));
        var claudeAt = segment.indexOf("claude-");
        if (claudeAt < 0) {
            return "";
        }
        var prefix = segment.substring(0, claudeAt);
        if (!prefix.isEmpty() && !prefix.endsWith("anthropic-")) {
            return "";
        }
        // Bedrock appends an inference profile (-v1:0); OpenRouter, a :free or :beta variant.
        var id = stripInferenceProfile(StringUtils.substringBefore(segment.substring(claudeAt), ":"));
        return namesAnAnthropicModel(id) ? id : "";
    }

    /**
     * Anthropic names a model {@code claude-<family>-<version>}, or puts the version first in the
     * legacy generations. A name that fits neither is a deployment someone chose — {@code claude-prod}
     * behind a gateway — and saying nothing about which Claude it is, it keeps the sampling params set
     * on it rather than being assumed to take none.
     */
    private boolean namesAnAnthropicModel(String id) {
        return isLegacyGeneration(id)
                || FAMILY_TOKENS.contains(StringUtils.substringBefore(StringUtils.removeStart(id, "claude-"), "-"));
    }

    /**
     * Bedrock's inference profile suffix, removed — but never down to the bare family word, because
     * {@code claude-v2} is Claude 2 rather than a decorated {@code claude}.
     */
    private String stripInferenceProfile(String id) {
        var stripped = id.replaceFirst("-v\\d+$", "");
        return StringUtils.contains(stripped, "-") ? stripped : id;
    }

    /** The prefix has to end where a segment does, or {@code claude-30-future} would read as Claude 3. */
    private boolean isLegacyGeneration(String canonical) {
        return LEGACY_GENERATION_PREFIXES.stream()
                .anyMatch(prefix -> canonical.equals(prefix) || canonical.startsWith(prefix + "-"));
    }

    /**
     * A prefix names the model only when it ends where a segment does, so {@code claude-opus-4-1} is
     * not {@code claude-opus-4}. The release date is optional on either side, because providers drop
     * it as often as they add it — but only a whole date is matched across, or {@code claude-opus-4}
     * would claim {@code claude-opus-4-8}.
     */
    private boolean namesModel(String canonical, String modelId) {
        if (canonical.equals(modelId)) {
            return true;
        }
        if (canonical.startsWith(modelId + "-")) {
            // A numeric segment is the next version, not a variant of this one: claude-fable-5-1 is
            // its own model, while claude-opus-4-6-fast and a dated build are still claude-opus-4-6.
            var next = StringUtils.substringBefore(canonical.substring(modelId.length() + 1), "-");
            return isReleaseDate(next) || !StringUtils.isNumeric(next);
        }
        return modelId.startsWith(canonical + "-") && isReleaseDate(modelId.substring(canonical.length() + 1));
    }

    private boolean isReleaseDate(String segment) {
        return segment.length() == DATE_SUFFIX_LENGTH && StringUtils.isNumeric(segment);
    }

    /**
     * The model, not the route to it. Custom ids carry the gateway in the prefix
     * (custom-llm/&lt;provider_name&gt;/&lt;model&gt;), so a provider someone called "claude-gw" would
     * otherwise make every model behind it — Mistral, Llama — look like Claude. An id with no
     * separator is the model; one that ends in a separator names no model at all and must not fall
     * back to the gateway — the frontend reads the same id the same way.
     */
    private String modelSegment(String modelName) {
        return StringUtils.contains(modelName, "/")
                ? StringUtils.substringAfterLast(modelName, "/")
                : modelName;
    }

    public boolean supportsVision(String modelName) {
        if (matchesVisionPattern(modelName)) {
            return true;
        }

        return find(modelName).map(ModelCapability::supportsVision).orElse(false);
    }

    private Optional<ModelCapability> find(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }

        for (var candidate : candidateKeys(modelName)) {
            var found = CAPABILITIES_BY_NORMALIZED_NAME.get(candidate);
            if (found != null) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    private Map<String, ModelCapability> loadCapabilities() {
        try {
            Map<String, ModelCostData> rawData = JsonUtils.readJsonFile(CostService.MODEL_PRICES_FILE,
                    new TypeReference<>() {
                    });
            if (rawData.isEmpty()) {
                throw new IllegalStateException(
                        "No entries found in model prices file '%s'".formatted(CostService.MODEL_PRICES_FILE));
            }

            var capabilities = new HashMap<String, ModelCapability>();
            rawData.forEach((modelName, modelData) -> {
                if (StringUtils.isBlank(modelName)) {
                    return;
                }

                var normalizedName = normalize(modelName);
                var canonicalName = modelName.trim();
                capabilities.putIfAbsent(normalizedName, ModelCapability.builder()
                        .name(canonicalName)
                        .litellmProvider(Objects.requireNonNullElse(modelData.litellmProvider(), ""))
                        .supportsVision(modelData.supportsVision())
                        .build());
            });

            return Collections.unmodifiableMap(capabilities);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to load model capabilities from file '%s'".formatted(CostService.MODEL_PRICES_FILE),
                    exception);
        }
    }

    private List<String> candidateKeys(String modelName) {
        var candidates = new HashSet<String>();
        var normalized = normalize(modelName);
        candidates.add(normalized);

        var slashIndex = normalized.lastIndexOf('/') + 1;
        if (slashIndex > 0 && slashIndex < normalized.length()) {
            candidates.add(normalized.substring(slashIndex));
        }

        var colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            candidates.add(normalized.substring(0, colonIndex));

            if (slashIndex > 0 && slashIndex < colonIndex) {
                candidates.add(normalized.substring(slashIndex, colonIndex));
            }
        }

        return new ArrayList<>(candidates);
    }

    /**
     * Normalizes model names for consistent lookup.
     * - Converts to lowercase
     * - Trims whitespace
     * - Replaces dots with hyphens (fixes issue #4114 for vision capability detection)
     *
     * This ensures "claude-3.5-sonnet" matches "claude-3-5-sonnet" in the pricing database.
     */
    private String normalize(String modelName) {
        return modelName.trim().toLowerCase().replace('.', '-');
    }
}
