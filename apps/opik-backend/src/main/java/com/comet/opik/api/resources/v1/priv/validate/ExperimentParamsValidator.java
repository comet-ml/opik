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
public class ExperimentParamsValidator {

    private static final TypeReference<List<UUID>> LIST_UUID_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static Set<UUID> getExperimentIds(String experimentIds) {
        var message = "Invalid query param experiment ids '%s'".formatted(experimentIds);
        try {
            return JsonUtils.readValue(experimentIds, LIST_UUID_TYPE_REFERENCE)
                    .stream()
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            log.warn(message, exception);
            throw new BadRequestException(message, exception);
        }
    }

}
