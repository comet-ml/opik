package com.comet.opik.domain.topics;

import com.clickhouse.client.api.ServerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topics read errors")
class TopicsReadErrorsTest {

    @ParameterizedTest
    @ValueSource(ints = {158, 307, 396})
    @DisplayName("a rows or bytes limit is too large and not a limit, so the order of the checks does not matter")
    void tooLargeIsNotALimit(int code) {
        var v2 = new RuntimeException(new ServerException(code, "Limit exceeded", 500, "query-id"));
        var v1 = new RuntimeException(new com.clickhouse.client.ClickHouseException(code, "Limit exceeded", null));

        assertThat(TopicsReadErrors.isTooLarge(v2)).isTrue();
        assertThat(TopicsReadErrors.isTooLarge(v1)).isTrue();
        assertThat(TopicsReadErrors.isLimit(v2)).isFalse();
        assertThat(TopicsReadErrors.isLimit(v1)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {159, 241})
    @DisplayName("a time or memory limit is a limit and not too large")
    void timeOrMemoryIsALimit(int code) {
        var error = new RuntimeException(new ServerException(code, "Limit exceeded", 500, "query-id"));

        assertThat(TopicsReadErrors.isLimit(error)).isTrue();
        assertThat(TopicsReadErrors.isTooLarge(error)).isFalse();
    }

    @Test
    @DisplayName("the client timeout anywhere in the causes is a limit")
    void clientTimeoutIsALimit() {
        assertThat(TopicsReadErrors.isLimit(new IllegalStateException(new TimeoutException("late")))).isTrue();
    }

    @Test
    @DisplayName("another error is neither")
    void otherErrorIsNeither() {
        var error = new RuntimeException(new ServerException(62, "Syntax error", 400, "query-id"));

        assertThat(TopicsReadErrors.isLimit(error)).isFalse();
        assertThat(TopicsReadErrors.isTooLarge(error)).isFalse();
    }
}
