package com.comet.opik.api.resources.utils;

import lombok.experimental.UtilityClass;

import java.net.URI;
import java.util.UUID;

@UtilityClass
public class TestUtils {

    public static UUID getIdFromLocation(URI location) {
        return UUID.fromString(location.getPath().substring(location.getPath().lastIndexOf('/') + 1));
    }
}
