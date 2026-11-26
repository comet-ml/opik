package com.comet.opik.infrastructure.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.google.genai.JsonSerializable;
import com.google.genai.types.GenerateContentConfig;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.BadRequestException;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.MapUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Custom parameters for Gemini on Google AI Studio and Vertex AI, read under the names of Google's
 * {@link GenerateContentConfig}, so the same JSON means the same on both, e.g. {@code {"topK": 40, "seed": 7}}.
 */
@UtilityClass
public class GeminiCustomParameters {

    public static final String THINKING = "thinking";

    // Each would change where the call goes, which model answers, the messages, or the shape of the reply.
    private static final List<String> OPIK_MANAGED_KEYS = List.of(
            "httpOptions", "routingConfig", "modelSelectionConfig", "systemInstruction", "shouldReturnHttpResponse");

    // Google's own mapper ignores unknown keys, which would drop a misspelt one without a word.
    private static final ObjectMapper STRICT_MAPPER = JsonSerializable.objectMapper().copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** All but Opik's own thinking level, which {@link GeminiThinkingParams} reads and never sends as it is. */
    public Map<String, Object> forGoogle(@Nullable Map<String, Object> customParameters) {
        if (MapUtils.isEmpty(customParameters)) {
            return Map.of();
        }
        var forGoogle = new LinkedHashMap<>(customParameters);
        forGoogle.remove(THINKING);
        return forGoogle;
    }

    public @Nullable Map<String, Object> thinkingOnly(@Nullable Map<String, Object> customParameters) {
        if (customParameters == null || !customParameters.containsKey(THINKING)) {
            return null;
        }
        return Map.of(THINKING, customParameters.get(THINKING));
    }

    /** Throws a 400 naming the first key Google wouldn't take. */
    public GenerateContentConfig parse(@Nullable Map<String, Object> customParameters) {
        var builder = GenerateContentConfig.builder();
        mergeInto(builder, forGoogle(customParameters));
        return builder.build();
    }

    /** Sets the custom parameters over a config already built from the request, so a custom key wins. */
    public Optional<Consumer<GenerateContentConfig.Builder>> merger(@Nullable Map<String, Object> customParameters) {
        var forGoogle = forGoogle(customParameters);
        return forGoogle.isEmpty() ? Optional.empty() : Optional.of(builder -> mergeInto(builder, forGoogle));
    }

    private void mergeInto(GenerateContentConfig.Builder builder, Map<String, Object> forGoogle) {
        CustomParametersOverrides.rejectOpikManagedKeys(OPIK_MANAGED_KEYS, forGoogle);
        try {
            STRICT_MAPPER.readerForUpdating(builder).readValue(STRICT_MAPPER.<JsonNode>valueToTree(forGoogle));
        } catch (UnrecognizedPropertyException exception) {
            throw new BadRequestException(("custom_parameters.%s isn't a Gemini setting. Use the names of Google's "
                    + "GenerateContentConfig, like topK or responseMimeType").formatted(pathOf(exception)));
        } catch (JsonMappingException exception) {
            throw new BadRequestException("custom_parameters.%s has a value of the wrong type"
                    .formatted(pathOf(exception)));
        } catch (IOException exception) {
            throw new BadRequestException("custom_parameters can't be read as Gemini settings");
        }
    }

    private String pathOf(JsonMappingException exception) {
        return exception.getPath().stream()
                .map(reference -> reference.getFieldName() != null
                        ? "." + reference.getFieldName()
                        : "[%d]".formatted(reference.getIndex()))
                .collect(Collectors.joining())
                .substring(1);
    }
}
