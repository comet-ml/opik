package com.comet.opik.utils;

import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Collection;

@UtilityClass
public class TypeReferenceUtils {

    public static <C extends Collection<?>> CollectionType forCollection(@NonNull Class<C> collectionClass,
            @NonNull Class<?> elementClass) {
        return TypeFactory.defaultInstance().constructCollectionType(collectionClass, elementClass);
    }
}
