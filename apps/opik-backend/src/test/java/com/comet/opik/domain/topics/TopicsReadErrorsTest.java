package com.comet.opik.domain.topics;

import com.clickhouse.client.api.ServerException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    @ParameterizedTest
    @ValueSource(ints = {159, 241})
    @DisplayName("a query filtered by topic that stops at its time or memory guard is a 503 with Retry-After")
    void guardCodeIs503(int code) {
        var read = Mono.<Long>error(new RuntimeException(new ServerException(code, "Limit", 500, "query-id")));

        assertBusy(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(true, read).block()));
    }

    @Test
    @DisplayName("a query filtered by topic that hits the client timeout is a 503 with Retry-After")
    void clientTimeoutIs503() {
        var read = Mono.<Long>error(new IllegalStateException(new TimeoutException("late")));

        assertBusy(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(true, read).block()));
    }

    @Test
    @DisplayName("a query filtered by topic keeps its other errors, a rows limit included")
    void otherErrorsPassThrough() {
        var rows = new RuntimeException(new ServerException(158, "Limit", 500, "query-id"));
        var other = new IllegalStateException("read failed");

        assertThat(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(true, Mono.error(rows)).block()))
                .isSameAs(rows);
        assertThat(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(true, Mono.error(other)).block()))
                .isSameAs(other);
    }

    @Test
    @DisplayName("a query without a topic filter keeps its errors, a time limit included")
    void withoutTopicFilterNothingChanges() {
        var time = new RuntimeException(new ServerException(159, "Limit", 500, "query-id"));
        var read = Mono.<Long>error(time);

        assertThat(TopicsReadErrors.busyOnFilterGuard(false, read)).isSameAs(read);
        assertThat(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(false, read).block())).isSameAs(time);
    }

    private static void assertBusy(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ServiceUnavailableException.class);
        var response = ((ServiceUnavailableException) thrown).getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }
}
