package com.comet.opik.api.resources.utils;

import com.comet.opik.domain.AgentConfigValue;
import lombok.experimental.UtilityClass;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class AgentConfigValueAssertionUtils {

    public static void assertConfigValue(AgentConfigValue expected, AgentConfigValue actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .isEqualTo(expected);
    }

    public static void assertConfigValues(List<AgentConfigValue> expected, List<AgentConfigValue> actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringCollectionOrder()
                .isEqualTo(expected);
    }
}
