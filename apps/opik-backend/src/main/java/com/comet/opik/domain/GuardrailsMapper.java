package com.comet.opik.domain;

import com.comet.opik.api.Guardrail;
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

    default List<GuardrailsValidation> mapToValidations(@NonNull List<Guardrail> guardrails) {
        if (guardrails.isEmpty()) {
            return List.of();
        }

        return guardrails.stream()
                // group by span ids
                .collect(groupingBy(Guardrail::secondaryId, mapping(Function.identity(), Collectors.toList())))
                .entrySet().stream()
                .map(entry -> mapToValidation(entry.getKey(), entry.getValue()))
                .toList();
    }

    default GuardrailsValidation mapToValidation(
            @NonNull UUID spanId, @NonNull List<Guardrail> guardrails) {
        return GuardrailsValidation.builder()
                .spanId(spanId)
                .checks(guardrails.stream().map(this::mapToGuardrailValidationCheck).toList())
                .build();
    }

    default GuardrailsValidation.Check mapToGuardrailValidationCheck(@NonNull Guardrail guardrailBatchItems) {
        return GuardrailsValidation.Check.builder()
                .name(guardrailBatchItems.name())
                .result(guardrailBatchItems.result())
                .build();
    }
}
