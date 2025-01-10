package com.comet.opik.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

@UtilityClass
public class TypeReferenceUtils {

    public static <T, C extends Collection<T>> TypeReference<C> forCollection(@NonNull Class<C> collectionClass,
            @NonNull Class<T> elementClass) {
        return new TypeReference<>() {
            @Override
            public Type getType() {
                return new ParameterizedType() {
                    @Override
                    public Type[] getActualTypeArguments() {
                        return new Type[]{elementClass};
                    }

                    @Override
                    public Type getRawType() {
                        return collectionClass;
                    }

                    @Override
                    public Type getOwnerType() {
                        return null;
                    }
                };
            }
        };
    }
}
