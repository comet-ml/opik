package com.comet.opik.domain;

import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BatchDeleteUtils {
    public static <T, U> TxAction<T> getHandler(
            Class<T> aClass, Function<T, List<U>> entityGetter, Function<U, UUID> idGetter,
            BiConsumer<T, Set<UUID>> entityDeleter) {
        return handle -> {
            var repository = handle.attach(aClass);

            // handle only existing entities
            Set<UUID> existingEntityIds = entityGetter.apply(repository).stream()
                    .map(idGetter).collect(Collectors.toUnmodifiableSet());

            if (existingEntityIds.isEmpty()) {
                // Void return
                return null;
            }

            entityDeleter.accept(repository, existingEntityIds);

            // Void return
            return null;
        };
    }
}
