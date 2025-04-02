package com.comet.opik.domain;

import com.comet.opik.api.GuardrailBatchItem;
import com.comet.opik.api.GuardrailsValidation;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;

@Mapper
public interface GuardrailsMapper {

    GuardrailsMapper INSTANCE = Mappers.getMapper(GuardrailsMapper.class);

    default List<GuardrailsValidation> mapToValidations(@NonNull List<GuardrailBatchItem> guardrailBatchItems) {
        if (guardrailBatchItems.isEmpty()) {
            return List.of();
        }

        return guardrailBatchItems.stream()
                // group by span ids
                .collect(groupingBy(GuardrailBatchItem::secondaryId, mapping(Function.identity(), Collectors.toList())))
                .entrySet().stream()
                .map(entry -> mapToValidation(entry.getKey(), entry.getValue()))
                .toList();
    }

    default GuardrailsValidation mapToValidation(
            @NonNull UUID spanId, @NonNull List<GuardrailBatchItem> guardrailBatchItems) {
        return GuardrailsValidation.builder()
                .spanId(spanId)
                .checks(guardrailBatchItems.stream().map(this::mapToGuardrailValidationCheck).toList())
                .build();
    }

    default GuardrailsValidation.Check mapToGuardrailValidationCheck(@NonNull GuardrailBatchItem guardrailBatchItems) {
        return GuardrailsValidation.Check.builder()
                .name(guardrailBatchItems.name())
                .result(guardrailBatchItems.result())
                .build();
    }
}
