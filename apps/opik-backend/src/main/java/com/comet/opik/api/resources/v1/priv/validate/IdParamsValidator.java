package com.comet.opik.api.resources.v1.priv.validate;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.BadRequestException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class IdParamsValidator {

    private static final TypeReference<List<UUID>> LIST_UUID_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static Set<UUID> getIds(String idsQueryParam) {
        var message = "Invalid query param ids '%s'".formatted(idsQueryParam);
        try {
            return JsonUtils.readValue(idsQueryParam, LIST_UUID_TYPE_REFERENCE)
                    .stream()
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            log.warn(message, exception);
            throw new BadRequestException(message, exception);
        }
    }

}
