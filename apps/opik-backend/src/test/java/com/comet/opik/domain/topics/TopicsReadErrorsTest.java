package com.comet.opik.domain.topics;

import com.clickhouse.client.ClickHouseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topics read errors")
class TopicsReadErrorsTest {

    @ParameterizedTest
    @ValueSource(ints = {158, 159, 241, 307, 396})
    @DisplayName("every rows, bytes, time or memory guard is a guard trip")
    void everyGuardIsAGuardTrip(int code) {
        assertThat(TopicsReadErrors.isGuardTrip(clickHouse(code))).isTrue();
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
        assertThat(TopicsReadErrors.isGuardTrip(error)).isFalse();
    }

    private static RuntimeException clickHouse(int code) {
        return new RuntimeException(new ClickHouseException(code, "Limit exceeded", null));
    }
}
