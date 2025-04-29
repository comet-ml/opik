package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.usagelimit.Quota;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

public class QuotaLimitTestUtils {
    public static final String ERR_USAGE_LIMIT_EXCEEDED = "You have exceeded the usage limit for this operation.";

    public static Stream<Arguments> quotaLimitsTestProvider() {
        return Stream.of(
                arguments(null, false),
                arguments(List.of(), false),
                arguments(List.of(Quota.builder()
                        .type(Quota.QuotaType.OPIK_SPAN_COUNT)
                        .limit(25_000)
                        .used(24_999)
                        .build()), false),
                arguments(List.of(Quota.builder()
                        .type(Quota.QuotaType.OPIK_SPAN_COUNT)
                        .limit(25_000)
                        .used(25_000)
                        .build()), true));
    }
}
