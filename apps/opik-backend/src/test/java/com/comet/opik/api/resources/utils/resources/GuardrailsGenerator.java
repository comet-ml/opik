package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Guardrail;
import com.comet.opik.podam.PodamFactoryUtils;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GuardrailsGenerator {
    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    public List<Guardrail> generateGuardrailsForTrace(UUID traceId, UUID spanId, String projectName) {
        return PodamFactoryUtils.manufacturePojoList(factory, Guardrail.class).stream()
                .map(guardrail -> guardrail.toBuilder()
                        .id(null)
                        .entityId(traceId)
                        .secondaryId(spanId)
                        .projectName(projectName)
                        .build())
                // deduplicate by guardrail name
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                Guardrail::name,
                                Function.identity(),
                                (existing, replacement) -> existing),
                        map -> new ArrayList<>(map.values())));
    }
}
