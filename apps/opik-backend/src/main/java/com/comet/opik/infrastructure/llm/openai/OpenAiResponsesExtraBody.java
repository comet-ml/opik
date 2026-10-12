package com.comet.opik.infrastructure.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.MapUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

// langchain4j's Responses models build the request body inside the model and have no slot for extra keys, so the
// extra body is added where the model hands that request to the openai-java client.
@UtilityClass
class OpenAiResponsesExtraBody {

    private static final JsonMapper OPENAI_JSON_MAPPER = ObjectMappers.jsonMapper();
    // Not the openai-java mapper: it drops null map values, and a null in the extra body is part of it as given.
    private static final ObjectMapper EXTRA_BODY_MAPPER = new ObjectMapper();

    OpenAIClient withExtraBody(@NonNull OpenAIClient client, Map<String, Object> extraBody) {
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
