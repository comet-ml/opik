package com.comet.opik.api.resources.v1.priv.validate;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.type.TypeFactory;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class ParamsValidator {

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

    public static <T> Set<T> get(String listParamValue, @NonNull Class<T> clazz, @NonNull String paramName) {
        var message = "Invalid query param %s '%s'".formatted(paramName, listParamValue);
        try {

            if (StringUtils.isEmpty(listParamValue)) {
                return Set.of();
            }

            TypeReference<Set<T>> typeReference = new TypeReference<>() {

                @Override
                public Type getType() {
                    return TypeFactory.defaultInstance().constructCollectionType(Set.class, clazz);
                }

            };

            return JsonUtils.readValue(listParamValue, typeReference);
        } catch (RuntimeException exception) {
            log.warn(message, exception);
            throw new BadRequestException(message, exception);
        }
    }

}
