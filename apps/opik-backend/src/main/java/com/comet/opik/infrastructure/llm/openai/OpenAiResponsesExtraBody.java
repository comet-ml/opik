package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ResponseFormat;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.MapUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

// langchain4j's Responses models build the request body inside the model and have no slot for extra keys, so the
// extra body is added where the model hands that request to the openai-java client.
@UtilityClass
class OpenAiResponsesExtraBody {

    private static final String MAX_TOKENS = "max_tokens";
    private static final String MAX_COMPLETION_TOKENS = "max_completion_tokens";
    private static final String REASONING_EFFORT = "reasoning_effort";
    private static final String RESPONSE_FORMAT = "response_format";
    private static final Set<String> CHAT_COMPLETIONS_KEYS = Set.of(
            MAX_TOKENS, MAX_COMPLETION_TOKENS, REASONING_EFFORT, RESPONSE_FORMAT);
    // Written from the request itself. Replaced from the extra body, the call would no longer match the request that
    // was logged and costed, or the reply would not come back in the shape the caller asked for.
    private static final List<String> KEYS_OPIK_SETS = List.of("model", "input", "stream");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final JsonMapper OPENAI_JSON_MAPPER = ObjectMappers.jsonMapper();
    // Not the openai-java mapper: it drops null map values, and a null in the extra body is part of it as given.
    private static final ObjectMapper EXTRA_BODY_MAPPER = new ObjectMapper();

    // The Responses API names these settings max_output_tokens, reasoning.effort and text.format, so a config saved
    // for a Chat Completions key would be rejected if they went out as given. As typed fields they are written under
    // the Responses names, and an extra body key that already uses a Responses name is merged later and wins.
    ChatCompletionRequest toResponsesRequest(@NonNull ChatCompletionRequest request) {
        var customParameters = request.customParameters();
        if (MapUtils.isEmpty(customParameters)) {
            return request;
        }
        KEYS_OPIK_SETS.stream()
                .filter(customParameters::containsKey)
                .findFirst()
                .ifPresent(key -> {
                    throw new BadRequestException(
                            "custom_parameters.%s is set by Opik from the request and cannot be replaced"
                                    .formatted(key));
                });
        if (CHAT_COMPLETIONS_KEYS.stream().noneMatch(customParameters::containsKey)) {
            return request;
        }
        var builder = ChatCompletionRequest.builder().from(request);
        if (customParameters.containsKey(MAX_COMPLETION_TOKENS) || customParameters.containsKey(MAX_TOKENS)) {
            builder.maxCompletionTokens(Stream.of(MAX_COMPLETION_TOKENS, MAX_TOKENS)
                    .filter(key -> customParameters.get(key) != null)
                    .findFirst()
                    .map(key -> valueOf(customParameters, key, Integer.class))
                    .orElse(null))
                    .maxTokens(null);
        }
        if (customParameters.containsKey(REASONING_EFFORT)) {
            builder.reasoningEffort(valueOf(customParameters, REASONING_EFFORT, String.class));
        }
        if (customParameters.containsKey(RESPONSE_FORMAT)) {
            builder.responseFormat(valueOf(customParameters, RESPONSE_FORMAT, ResponseFormat.class));
        }
        var rest = new LinkedHashMap<>(customParameters);
        rest.keySet().removeAll(CHAT_COMPLETIONS_KEYS);
        return builder.customParameters(rest).build();
    }

    @Nullable Map<String, Object> fromJudgeCustomParameters(@Nullable JsonNode customParameters) {
        if (customParameters == null || !customParameters.isObject()) {
            return null;
        }
        return JsonUtils.getMapper().convertValue(customParameters, MAP_TYPE);
    }

    OpenAIClient withExtraBody(@NonNull OpenAIClient client, @Nullable Map<String, Object> extraBody) {
        if (MapUtils.isEmpty(extraBody)) {
            return client;
        }
        var responses = client.responses();
        var responsesWithExtraBody = newProxy(ResponseService.class, (self, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof ResponseCreateParams params) {
                args[0] = merge(params, extraBody);
            }
            return invoke(responses, method, args);
        });
        return newProxy(OpenAIClient.class, (self, method, args) -> "responses".equals(method.getName())
                ? responsesWithExtraBody
                : invoke(client, method, args));
    }

    // openai-java writes additional body properties after the typed fields and keeps both when a name matches, so a
    // key Opik also sets would go twice. Merging into one object lets the extra body value replace Opik's, the way
    // OpenAI's own SDKs apply extra_body.
    private ResponseCreateParams merge(ResponseCreateParams params, Map<String, Object> extraBody) {
        ObjectNode body = OPENAI_JSON_MAPPER.valueToTree(params._body());
        body.setAll(EXTRA_BODY_MAPPER.<ObjectNode>valueToTree(extraBody));
        var mergedBody = ResponseCreateParams.Body.builder();
        body.properties().forEach(field -> mergedBody.putAdditionalProperty(
                field.getKey(), JsonValue.fromJsonNode(field.getValue())));
        return params.toBuilder().body(mergedBody.build()).build();
    }

    private <T> T valueOf(Map<String, Object> customParameters, String key, Class<T> type) {
        try {
            return JsonUtils.getMapper().convertValue(customParameters.get(key), type);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(
                    "Invalid custom_parameters.%s for the OpenAI Responses API".formatted(key), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T newProxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    // Unwrapped so callers get the client's own exception, which langchain4j maps to the provider's status, rather than
    // the proxy's UndeclaredThrowableException.
    private Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
