package com.comet.opik.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single retryability rule, shared by ChatCompletionService and BaseRedisSubscriber. Lives with the
 * predicate rather than either caller, because it is now the contract between them.
 */
class HttpStatusRetryabilityTest {

    @ParameterizedTest(name = "isPermanent({0}) -> {1}")
    @CsvSource({
            // Client-error family, minus the transient carve-outs.
            "400, true",
            "401, true",
            "403, true",
            "404, true",
            "413, true",
            "422, true",
            "499, true",
            // "not now", not "not ever".
            "408, false",
            "425, false",
            "429, false",
            // Server errors are the textbook retry case.
            "500, false",
            "502, false",
            "503, false",
            // Outside both error families - unrecognised defaults to retryable.
            "200, false",
            "302, false",
    })
    @DisplayName("Only the client-error family minus 408/425/429 is permanent")
    void classifiesTheStatus(int status, boolean expectedPermanent) {
        assertThat(HttpStatusRetryability.isPermanent(status)).isEqualTo(expectedPermanent);
    }
}
