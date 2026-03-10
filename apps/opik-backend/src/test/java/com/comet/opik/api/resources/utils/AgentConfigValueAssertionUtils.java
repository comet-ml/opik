package com.comet.opik.api.resources.utils;

import com.comet.opik.domain.AgentConfigValue;
import lombok.experimental.UtilityClass;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class AgentConfigValueAssertionUtils {

    public static final String[] IGNORED_FIELDS = {
            "id", "projectId", "validFromBlueprintId", "validToBlueprintId"};

    public static void assertConfigValue(AgentConfigValue expected, AgentConfigValue actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .isEqualTo(expected);
    }

    public static void assertConfigValues(List<AgentConfigValue> expected, List<AgentConfigValue> actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS)
                .ignoringCollectionOrder()
                .isEqualTo(expected);
    }
}
