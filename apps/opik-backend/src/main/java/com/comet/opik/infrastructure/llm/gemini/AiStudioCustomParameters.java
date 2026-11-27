package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.llm.GeminiCustomParameters;
import com.google.genai.types.SafetySetting;
import dev.langchain4j.model.googleai.GeminiHarmBlockThreshold;
import dev.langchain4j.model.googleai.GeminiHarmCategory;
import dev.langchain4j.model.googleai.GeminiMediaResolutionLevel;
import dev.langchain4j.model.googleai.GeminiSafetySetting;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.BadRequestException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * The custom parameters Google AI Studio takes. Unlike Vertex AI's, langchain4j's AI Studio client can't add a field
 * to the request, so only the settings it has a setter for get through, and any other is refused rather than dropped.
 */
record AiStudioCustomParameters(
        Double temperature,
        Double topP,
        Integer topK,
        Integer seed,
        Double frequencyPenalty,
        Double presencePenalty,
        Integer maxOutputTokens,
        List<String> stopSequences,
        Boolean responseLogprobs,
        Integer logprobs,
        GeminiMediaResolutionLevel mediaResolution,
        List<GeminiSafetySetting> safetySettings) {

    private static final AiStudioCustomParameters NONE = new AiStudioCustomParameters(
            null, null, null, null, null, null, null, null, null, null, null, null);

    private static final List<String> KEYS = Arrays.stream(AiStudioCustomParameters.class.getRecordComponents())
            .map(component -> component.getName())
            .toList();

    /** Throws a 400 naming the first key AI Studio wouldn't get. */
    static AiStudioCustomParameters from(@Nullable Map<String, Object> customParameters) {
        var forGoogle = GeminiCustomParameters.forGoogle(customParameters);
        if (forGoogle.isEmpty()) {
            return NONE;
        }
        var config = GeminiCustomParameters.parse(forGoogle);
        forGoogle.keySet().stream()
                .filter(key -> !KEYS.contains(key))
                .sorted()
                .findFirst()
                .ifPresent(key -> {
                    throw new BadRequestException(
                            "custom_parameters.%s can't be sent to Gemini on Google AI Studio, Vertex AI takes it. AI Studio takes: %s"
                                    .formatted(key, String.join(", ", KEYS)));
                });
        return new AiStudioCustomParameters(
                config.temperature().map(AiStudioCustomParameters::toDouble).orElse(null),
                config.topP().map(AiStudioCustomParameters::toDouble).orElse(null),
                config.topK().map(topK -> wholeNumber("topK", topK)).orElse(null),
                config.seed().orElse(null),
                config.frequencyPenalty().map(AiStudioCustomParameters::toDouble).orElse(null),
                config.presencePenalty().map(AiStudioCustomParameters::toDouble).orElse(null),
                config.maxOutputTokens().orElse(null),
                config.stopSequences().orElse(null),
                config.responseLogprobs().orElse(null),
                config.logprobs().orElse(null),
                config.mediaResolution()
                        .map(level -> enumValue(GeminiMediaResolutionLevel.class, "mediaResolution", level.toString()))
                        .orElse(null),
                config.safetySettings().map(AiStudioCustomParameters::toSafetySettings).orElse(null));
    }

    void applyTo(GoogleAiGeminiChatModelBuilder builder) {
        Optional.ofNullable(temperature).ifPresent(builder::temperature);
        Optional.ofNullable(topP).ifPresent(builder::topP);
        Optional.ofNullable(topK).ifPresent(builder::topK);
        Optional.ofNullable(seed).ifPresent(builder::seed);
        Optional.ofNullable(frequencyPenalty).ifPresent(builder::frequencyPenalty);
        Optional.ofNullable(presencePenalty).ifPresent(builder::presencePenalty);
        Optional.ofNullable(maxOutputTokens).ifPresent(builder::maxOutputTokens);
        Optional.ofNullable(stopSequences).ifPresent(builder::stopSequences);
        Optional.ofNullable(responseLogprobs).ifPresent(builder::responseLogprobs);
        Optional.ofNullable(logprobs).ifPresent(builder::logprobs);
        Optional.ofNullable(mediaResolution).ifPresent(builder::mediaResolution);
        Optional.ofNullable(safetySettings).ifPresent(builder::safetySettings);
    }

    void applyTo(GoogleAiGeminiStreamingChatModelBuilder builder) {
        Optional.ofNullable(temperature).ifPresent(builder::temperature);
        Optional.ofNullable(topP).ifPresent(builder::topP);
        Optional.ofNullable(topK).ifPresent(builder::topK);
        Optional.ofNullable(seed).ifPresent(builder::seed);
        Optional.ofNullable(frequencyPenalty).ifPresent(builder::frequencyPenalty);
        Optional.ofNullable(presencePenalty).ifPresent(builder::presencePenalty);
        Optional.ofNullable(maxOutputTokens).ifPresent(builder::maxOutputTokens);
        Optional.ofNullable(stopSequences).ifPresent(builder::stopSequences);
        Optional.ofNullable(responseLogprobs).ifPresent(builder::responseLogprobs);
        Optional.ofNullable(logprobs).ifPresent(builder::logprobs);
        Optional.ofNullable(mediaResolution).ifPresent(builder::mediaResolution);
        Optional.ofNullable(safetySettings).ifPresent(builder::safetySettings);
    }

    // Google's config holds these as floats, so 0.7 comes back as 0.699999988079071 unless it goes through its text.
    private static Double toDouble(Float value) {
        return Double.valueOf(value.toString());
    }

    private static Integer wholeNumber(String key, Float value) {
        if (value % 1 != 0) {
            throw new BadRequestException("custom_parameters.%s must be a whole number on Google AI Studio"
                    .formatted(key));
        }
        return value.intValue();
    }

    private static List<GeminiSafetySetting> toSafetySettings(List<SafetySetting> settings) {
        return IntStream.range(0, settings.size())
                .mapToObj(index -> toSafetySetting("safetySettings[%d]".formatted(index), settings.get(index)))
                .toList();
    }

    private static GeminiSafetySetting toSafetySetting(String path, SafetySetting setting) {
        if (setting.method().isPresent()) {
            throw new BadRequestException(
                    "custom_parameters.%s.method can't be sent to Gemini on Google AI Studio, Vertex AI takes it"
                            .formatted(path));
        }
        var category = setting.category().orElseThrow(() -> missing(path + ".category"));
        var threshold = setting.threshold().orElseThrow(() -> missing(path + ".threshold"));
        return new GeminiSafetySetting(
                enumValue(GeminiHarmCategory.class, path + ".category", category.toString()),
                enumValue(GeminiHarmBlockThreshold.class, path + ".threshold", threshold.toString()));
    }

    private static BadRequestException missing(String path) {
        return new BadRequestException("custom_parameters.%s is required".formatted(path));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String path, String value) {
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("custom_parameters.%s '%s' isn't one Google AI Studio takes"
                        .formatted(path, value)));
    }
}
