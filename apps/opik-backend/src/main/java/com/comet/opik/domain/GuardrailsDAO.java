package com.comet.opik.domain;

import com.comet.opik.api.GuardrailBatchItem;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import reactor.core.publisher.Mono;

import java.util.List;

@ImplementedBy(GuardrailsDAOImpl.class)
public interface GuardrailsDAO {
    Mono<Long> addGuardrails(EntityType entityType, List<GuardrailBatchItem> guardrails);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class GuardrailsDAOImpl implements GuardrailsDAO {

    @Override
    public Mono<Long> addGuardrails(EntityType entityType, List<GuardrailBatchItem> guardrails) {
        throw new NotImplementedException();
    }
}
