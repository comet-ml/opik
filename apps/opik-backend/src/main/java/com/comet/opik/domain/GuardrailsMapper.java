package com.comet.opik.domain;

import com.comet.opik.api.GuardrailBatchItem;
import com.comet.opik.api.GuardrailPiiDetails;
import com.comet.opik.api.GuardrailTopicDetails;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.GuardrailsCheck;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface GuardrailsMapper {

    GuardrailsMapper INSTANCE = Mappers.getMapper(GuardrailsMapper.class);

    @Mapping(source = "guardrailBatchItem", target = "items", qualifiedByName = "mapToItems")
    GuardrailsCheck toGuardrailCheck(GuardrailBatchItem guardrailBatchItem);

    @Named("mapToItems")
    default List<GuardrailsCheck.Item> mapToItems(@NonNull GuardrailBatchItem guardrailBatchItem) {
        return mapToItems(guardrailBatchItem.name(), guardrailBatchItem.details());
    }

    @Named("mapToItems")
    default List<GuardrailsCheck.Item> mapToItems(@NonNull GuardrailType name, @NonNull JsonNode details) {
        return switch (name) {
            case TOPIC -> mapToTopicItems(details);
            case PII -> mapToPiiItems(details);
        };
    }

    default List<GuardrailsCheck.Item> mapToTopicItems(@NonNull JsonNode details) {
        var topicDetails = JsonUtils.readValue(JsonUtils.writeValueAsString(details), GuardrailTopicDetails.class);
        return topicDetails.scores().entrySet().stream()
                .map(entry -> GuardrailsCheck.Item.builder()
                        .name(entry.getKey())
                        .score(entry.getValue())
                        .build())
                .toList();
    }

    default List<GuardrailsCheck.Item> mapToPiiItems(@NonNull JsonNode details) {
        var piiDetails = JsonUtils.readValue(JsonUtils.writeValueAsString(details), GuardrailPiiDetails.class);
        return piiDetails.detectedEntities().entrySet().stream()
                .map(entry -> GuardrailsCheck.Item.builder()
                        .name(entry.getKey())
                        .score(entry.getValue().getFirst().score())
                        .build())
                .toList();
    }
}
