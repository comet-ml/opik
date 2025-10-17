package com.comet.opik.infrastructure.queues;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * Utility class for building RQ (Redis Queue) job HASH structures.
 *
 * This class creates RQ-compatible job HASHes by:
 * 1. Serializing the job data (RqJob) to plain JSON bytes (UTF-8)
 * 2. Combining with RQ metadata from RqMessage
 */
@Slf4j
@UtilityClass
public class RqJobUtils {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * Build a complete RQ job HASH from an RqMessage.
     *
     * @param message The complete RQ message (job data + metadata)
     * @param job The job data (function, args, kwargs)
     * @return Map of HASH fields ready to be stored in Redis
     */
    public static Map<String, Object> buildJobHash(@NonNull QueueMessage message, @NonNull Job job) {

        // 1. Build job data as plain JSON string
        String jsonDataString = buildJobDataJsonString(job);

        // 2. Build RQ job HASH POJO (without data - handled separately)
        // RQ's logger expects a non-null description string; default to function name
        String safeDescription = StringUtils.isNotBlank(message.description())
                ? message.description()
                : job.func();

        var jobHash = RqJobMapper.INSTANCE.toHash(message, safeDescription, jsonDataString);

        // JsonUtils is configured to exclude nulls; no need to manually filter
        return JsonUtils.convertValue(jobHash, MAP_TYPE);
    }

    /**
     * Build job data (plain JSON string) in RQ's expected format.
     *
     * RQ expects: [function_name, null, [args...], {kwargs...}]
     * This is serialized to JSON (UTF-8) without compression.
     *
     * @param job The RQ job containing func, args, kwargs
     * @return Plain JSON string in UTF-8 format
     */
    public static String buildJobDataJsonString(@NonNull Job job) {
        // Build JSON array: [function, null, args, kwargs]
        ArrayNode dataArray = JsonNodeFactory.instance.arrayNode();
        dataArray.add(job.func());
        dataArray.addNull(); // Result callback (not used by RQ)

        // Add args
        ArrayNode argsNode = dataArray.addArray();
        job.args().forEach(argsNode::addPOJO);

        // Add kwargs
        ObjectNode kwargsNode = dataArray.addObject();
        job.kwargs().forEach(kwargsNode::putPOJO);

        // Serialize to plain JSON string (UTF-8)
        return JsonUtils.writeValueAsString(dataArray);
    }
}
