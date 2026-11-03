package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
class BedrockConverseClient {

    private final @NonNull HttpClient httpClient;
    private final @NonNull String baseUrl;
    private final String apiKey;

    JsonNode converse(@NonNull String modelId, @NonNull ObjectNode body) {
        var response = JsonUtils.getJsonNodeFromStringWithFallback(
                httpClient.execute(request(modelId, "converse", body)).body());
        // Bedrock answers a path it doesn't serve with a 200 and {"Output": {"__type": "...UnknownOperationException"}}
        if (!response.path("output").path("message").isObject()) {
            throw new ProviderRequestRefusedException(ProviderRequestRefusedException.Reason.BEDROCK_CONVERSE_URL);
        }
        return response;
    }

    void converseStream(@NonNull String modelId, @NonNull ObjectNode body, @NonNull ServerSentEventListener listener) {
        httpClient.execute(request(modelId, "converse-stream", body), new AwsEventStreamParser(), listener);
    }

    // In token mode there is no static key: InterceptingHttpClient puts the fetched token in Authorization.
    private HttpRequest request(String modelId, String operation, ObjectNode body) {
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("%s/model/%s/%s".formatted(baseUrl, encodePathSegment(modelId), operation))
                .addHeader("Content-Type", "application/json")
                .body(JsonUtils.writeValueAsString(body));
        if (StringUtils.isNotBlank(apiKey)) {
            request.addHeader("Authorization", "Bearer " + apiKey);
        }
        return request.build();
    }

    // An inference profile ARN has slashes, which must stay inside the one path segment; the colon of a version
    // suffix (-v1:0) is valid in a path and is kept as Bedrock's own examples show it.
    private static String encodePathSegment(String modelId) {
        return URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":");
    }
}
