package com.comet.opik.infrastructure.aws;

import com.comet.opik.infrastructure.AlertsEventBridgeConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.regions.Region;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AwsModuleTest {

    private static final String ARN_BUS = "arn:aws:events:eu-west-1:123456789012:event-bus/opik-alerts";

    static Stream<Arguments> eventBridgeRegionCases() {
        return Stream.of(
                Arguments.of(ARN_BUS, "us-east-2", Optional.of(Region.US_EAST_2)),
                Arguments.of(ARN_BUS, null, Optional.of(Region.EU_WEST_1)),
                Arguments.of(ARN_BUS, "", Optional.of(Region.EU_WEST_1)),
                Arguments.of("opik-alerts", "us-east-2", Optional.of(Region.US_EAST_2)),
                Arguments.of("opik-alerts", null, Optional.empty()));
    }

    @ParameterizedTest
    @MethodSource("eventBridgeRegionCases")
    void eventBridgeRegion(String eventBus, String region, Optional<Region> expected) {
        var config = new AlertsEventBridgeConfig();
        config.setEventBus(eventBus);
        config.setRegion(region);

        assertThat(AwsModule.eventBridgeRegion(config)).isEqualTo(expected);
    }
}
