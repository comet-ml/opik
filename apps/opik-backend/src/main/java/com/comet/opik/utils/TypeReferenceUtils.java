package com.comet.opik.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TypeReferenceUtils {

    public static <C> TypeReference<C> forTypes(@NonNull Class<C> wrapperType, @NonNull Class<?> elementClass) {
        return new TypeReference<>() {
            @Override
            public JavaType getType() {
                return TypeFactory.defaultInstance().constructParametricType(wrapperType, elementClass);
            }
        };
    }
}
