package com.comet.opik.api.resources.utils;

import lombok.experimental.UtilityClass;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

@UtilityClass
public class TestConfigUtils {

    public static String getBaseUrl(ClientSupport client) {
        return "http://localhost:%d".formatted(client.getPort());
    }

}
