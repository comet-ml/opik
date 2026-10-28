package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueueReference;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@UtilityClass
public class AnnotationQueueReferenceMapper {

    private static final Comparator<AnnotationQueueReference> BY_NAME_THEN_ID = Comparator
            .comparing(AnnotationQueueReference::name)
            .thenComparing(AnnotationQueueReference::id);

    /**
     * Maps the {@code groupArray(tuple(id, name))} column emitted by the trace and thread list queries.
     */
    static List<AnnotationQueueReference> map(List[] queues) {
        if (ArrayUtils.isEmpty(queues)) {
            return List.of();
        }
        return Arrays.stream(queues)
                .filter(queue -> CollectionUtils.isNotEmpty(queue)
                        && !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(queue.get(0).toString()))
                .map(queue -> AnnotationQueueReference.builder()
                        .id(UUID.fromString(queue.get(0).toString()))
                        .name(queue.get(1).toString())
                        .build())
                .sorted(BY_NAME_THEN_ID)
                .toList();
    }
}
