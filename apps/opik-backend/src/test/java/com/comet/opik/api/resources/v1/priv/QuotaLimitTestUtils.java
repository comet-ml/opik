package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.infrastructure.usagelimit.Quota;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

public class QuotaLimitTestUtils {
    public static Stream<Arguments> quotaLimitsTestProvider() {
        return Stream.of(
                arguments(null, false),
                arguments(List.of(), false),
                arguments(List.of(Quota.builder()
                        .type(Quota.QuotaType.SPAN_COUNT)
                        .limit(25_000)
                        .used(24_999)
                        .build()), false),
                arguments(List.of(Quota.builder()
                        .type(Quota.QuotaType.SPAN_COUNT)
                        .limit(25_000)
                        .used(25_000)
                        .build()), true));
    }
}
