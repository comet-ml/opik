package com.comet.opik.api.resources.utils;

import com.comet.opik.utils.JsonUtils;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@UtilityClass
public class TestUtils {

    public static UUID getIdFromLocation(URI location) {
        return UUID.fromString(location.getPath().substring(location.getPath().lastIndexOf('/') + 1));
    }

    public static String toURLEncodedQueryParam(List<?> filters) {
        return CollectionUtils.isEmpty(filters)
                ? null
                : URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8);
    }
}
