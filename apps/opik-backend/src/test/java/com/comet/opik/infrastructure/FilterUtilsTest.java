package com.comet.opik.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FilterUtilsTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String PLAIN_TEXT = "[A-Za-z0-9 _.,:=@+-]*";

    @ParameterizedTest
    @MethodSource
    void getLogComment_rendersExpectedComment(String queryName, String workspaceId, String userName, Object details,
            String expected) {
        assertThat(FilterUtils.getLogComment(queryName, workspaceId, userName, details)).isEqualTo(expected);
    }

    static Stream<Arguments> getLogComment_rendersExpectedComment() {
        return Stream.of(
                Arguments.of("get_dataset_items", WORKSPACE_ID, "Jane.Doe+opik@example.com",
                        "dataset_id=42, page=1, size=10",
                        "get_dataset_items:" + WORKSPACE_ID
                                + ":Jane.Doe+opik@example.com:dataset_id=42, page=1, size=10"),
                Arguments.of("ai-labels:render", WORKSPACE_ID, null, null, "ai-labels:render:" + WORKSPACE_ID + "::"),
                Arguments.of("count_traces", null, null, null, "count_traces:::"),
                Arguments.of("find_traces", WORKSPACE_ID, "o'brien", 5, "find_traces:" + WORKSPACE_ID + ":o-brien:5"),
                Arguments.of("find_traces", WORKSPACE_ID, "domain\\user", 5,
                        "find_traces:" + WORKSPACE_ID + ":domain-user:5"),
                Arguments.of("find_traces", WORKSPACE_ID, "first\nsecond", 5,
                        "find_traces:" + WORKSPACE_ID + ":first-second:5"),
                Arguments.of("find_traces", WORKSPACE_ID, "[team] lead", 5,
                        "find_traces:" + WORKSPACE_ID + ":-team- lead:5"),
                Arguments.of("find_traces", WORKSPACE_ID, "garcía", 5, "find_traces:" + WORKSPACE_ID + ":garc-a:5"));
    }

    @Test
    void getLogComment_shouldOnlyContainPlainTextForAnyCharacter() {
        var everyCharacter = IntStream.rangeClosed(0, Character.MAX_VALUE)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();

        var logComment = FilterUtils.getLogComment(everyCharacter, everyCharacter, everyCharacter, everyCharacter);

        assertThat(logComment).matches(PLAIN_TEXT);
    }
}
