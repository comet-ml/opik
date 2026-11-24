package com.comet.opik.domain.topics;

import com.clickhouse.client.ClickHouseException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("topics read errors")
class TopicsReadErrorsTest {

    @ParameterizedTest
    @ValueSource(ints = {158, 241, 307, 396})
    @DisplayName("a rows, bytes or memory guard is too large and not busy")
    void sizeGuardIsTooLarge(int code) {
        assertThat(TopicsReadErrors.isTooLarge(clickHouse(code))).isTrue();
        assertThat(TopicsReadErrors.isBusy(clickHouse(code))).isFalse();
    }

    @Test
    @DisplayName("the time guard, the spent budget and the client timeout are busy and not too large")
    void timeIsBusy() {
        var errors = List.of(clickHouse(159),
                new RuntimeException(new TopicsReadErrors.BudgetExceededException("spent")),
                new IllegalStateException(new TimeoutException("late")));

        assertThat(errors).allSatisfy(error -> {
            assertThat(TopicsReadErrors.isBusy(error)).isTrue();
            assertThat(TopicsReadErrors.isTooLarge(error)).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {158, 307, 396})
    @DisplayName("a rows or bytes limit is not a limit for the filter guard")
    void rowsOrBytesIsNotALimit(int code) {
        var error = clickHouse(code);

        assertThat(TopicsReadErrors.isLimit(error)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {159, 241})
    @DisplayName("a time or memory limit is a limit for the filter guard")
    void timeOrMemoryIsALimit(int code) {
        var error = clickHouse(code);

        assertThat(TopicsReadErrors.isLimit(error)).isTrue();
    }

    @Test
    @DisplayName("the client timeout anywhere in the causes is a limit")
    void clientTimeoutIsALimit() {
        assertThat(TopicsReadErrors.isLimit(new IllegalStateException(new TimeoutException("late")))).isTrue();
    }

    @Test
    @DisplayName("another error is neither")
    void otherErrorIsNeither() {
        var error = clickHouse(62);

        assertThat(TopicsReadErrors.isLimit(error)).isFalse();
        assertThat(TopicsReadErrors.isTooLarge(error)).isFalse();
        assertThat(TopicsReadErrors.isBusy(error)).isFalse();
    }

    private static RuntimeException clickHouse(int code) {
        return new RuntimeException(new ClickHouseException(code, "Limit exceeded", null));
    }

    static Stream<Arguments> guardTrips() {
        return Stream.of(
                arguments("its time guard (159)", clickHouse(159)),
                arguments("its memory guard (241)", clickHouse(241)),
                arguments("the client timeout", new IllegalStateException(new TimeoutException("late"))));
    }

    @ParameterizedTest(name = "a query filtered by topic that stops at {0} is a 503 with Retry-After")
    @MethodSource("guardTrips")
    void guardTripIs503(String name, Throwable error) {
        var read = Mono.<Long>error(error);

        assertBusy(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(true, read).block()));
    }

    @Test
    @DisplayName("a query filtered by topic keeps its other errors, a rows limit included")
    void otherErrorsPassThrough() {
        var rows = clickHouse(158);
        var other = new IllegalStateException("read failed");

        assertThat(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(true, Mono.error(rows)).block()))
                .isSameAs(rows);
        assertThat(catchThrowable(() -> TopicsReadErrors.busyOnFilterGuard(true, Mono.error(other)).block()))
                .isSameAs(other);
    }

    @Test
    @DisplayName("a query without a topic filter keeps its errors, a time limit included")
    void withoutTopicFilterNothingChanges() {
        var time = clickHouse(159);
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
