package com.comet.opik.api.resources.utils;

import org.apache.commons.lang3.RandomUtils;

public class RandomTestUtils {

    public static <T extends Enum<T>> T randomEnumValue(Class<T> enumClass) {
        var values = enumClass.getEnumConstants();
        return values[RandomUtils.secure().randomInt(0, values.length)];
    }

    public static String randomIp() {
        RandomUtils random = RandomUtils.insecure();
        return "%d.%d.%d.%d".formatted(
                random.randomInt(1, 256), random.randomInt(0, 256),
                random.randomInt(0, 256), random.randomInt(1, 256));
    }
}
