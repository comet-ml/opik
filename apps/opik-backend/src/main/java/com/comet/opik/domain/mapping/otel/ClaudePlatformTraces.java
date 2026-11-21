package com.comet.opik.domain.mapping.otel;

import com.comet.opik.api.Span;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Builds the trace content of Claude Managed Agents traces from their {@code anthropic.session.turn} spans.
 * <p>
 * The root of such a trace is a request span ({@code anthropic.sessions.events.send}, ...) with no content, and the
 * turn that the request starts usually arrives in a later batch. So the trace is updated when the turn arrives: the
 * input is the user text of the turn, the output is the last assistant text, and the thread is the session. Request
 * traces without a turn (stream, list, ...) get no thread, so the thread view shows only the turns.
 * <p>
 * Only top-level turns count. A turn whose parent turn is in the same batch runs on another session thread, for
 * example a sub-agent. A parent turn ends after its child turns, so it is never in an earlier batch.
 */
@UtilityClass
public class ClaudePlatformTraces {

    static final String TURN_SPAN_NAME = "anthropic.session.turn";
    static final String SESSION_ID_ATTR = "anthropic.session.id";

    private static final String MESSAGES_KEY = "messages";
    private static final String TEXT_SEPARATOR = "\n\n";

    public static Map<UUID, TraceUpdate> traceUpdates(List<Span> claudePlatformSpans) {
        var turns = claudePlatformSpans.stream()
                .filter(span -> TURN_SPAN_NAME.equals(span.name()))
                .toList();
        var turnIds = turns.stream().map(Span::id).collect(Collectors.toSet());

        return turns.stream()
                .filter(turn -> !turnIds.contains(turn.parentSpanId()))
                .collect(Collectors.groupingBy(Span::traceId))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> traceUpdate(entry.getValue())));
    }

    private static TraceUpdate traceUpdate(List<Span> topLevelTurns) {
        var firstTurn = Collections.min(topLevelTurns, Comparator.comparing(Span::startTime));
        var lastTurn = Collections.max(topLevelTurns, Comparator.comparing(Span::endTime));

        return TraceUpdate.builder()
                .input(messages(firstTurn.input()).map(ClaudePlatformTraces::userInput).orElse(null))
                .output(messages(lastTurn.output()).map(ClaudePlatformTraces::assistantOutput).orElse(null))
                .threadId(Optional.ofNullable(firstTurn.metadata())
                        .map(metadata -> metadata.path(SESSION_ID_ATTR).asText(null))
                        .filter(StringUtils::isNotBlank)
                        .orElse(null))
                .build();
    }

    private static Optional<JsonNode> messages(JsonNode content) {
        return Optional.ofNullable(content)
                .map(node -> node.get(MESSAGES_KEY))
                .filter(messages -> messages.isArray() && !messages.isEmpty());
    }

    private static JsonNode userInput(JsonNode messages) {
        var text = textsByRole(messages, "user").collect(Collectors.joining(TEXT_SEPARATOR));
        return textOrMessages("input", text, messages);
    }

    private static JsonNode assistantOutput(JsonNode messages) {
        var text = textsByRole(messages, "assistant").reduce((first, second) -> second).orElse("");
        return textOrMessages("output", text, messages);
    }

    private static JsonNode textOrMessages(String key, String text, JsonNode messages) {
        var content = JsonUtils.createObjectNode();
        return text.isBlank() ? content.set(MESSAGES_KEY, messages) : content.put(key, text);
    }

    private static Stream<String> textsByRole(JsonNode messages, String role) {
        return elements(messages)
                .filter(message -> role.equals(message.path("role").asText()))
                .map(message -> elements(message.path("parts"))
                        .filter(part -> "text".equals(part.path("type").asText()))
                        .map(part -> part.path("content").asText(""))
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.joining(TEXT_SEPARATOR)))
                .filter(StringUtils::isNotBlank);
    }

    private static Stream<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
