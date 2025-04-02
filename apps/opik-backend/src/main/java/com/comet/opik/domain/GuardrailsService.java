package com.comet.opik.domain;

import com.comet.opik.api.GuardrailBatchItem;
import com.comet.opik.api.GuardrailsCheck;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@ImplementedBy(GuardrailsServiceImpl.class)
public interface GuardrailsService {
    Flux<GuardrailsCheck> getTraceGuardrails(UUID traceId);
    Mono<Void> addTraceGuardrails(List<GuardrailBatchItem> guardrails);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailsServiceImpl implements GuardrailsService {

    @Override
    public Flux<GuardrailsCheck> getTraceGuardrails(UUID traceId) {
        throw new NotImplementedException();
    }

    @Override
    public Mono<Void> addTraceGuardrails(List<GuardrailBatchItem> guardrails) {
        throw new NotImplementedException();
    }
}
