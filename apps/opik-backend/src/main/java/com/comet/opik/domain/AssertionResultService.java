package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScoreItem;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.List;

@ImplementedBy(AssertionResultServiceImpl.class)
public interface AssertionResultService {

    Mono<Long> insertBatch(@NonNull EntityType entityType, @NonNull List<? extends FeedbackScoreItem> assertionScores);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AssertionResultServiceImpl implements AssertionResultService {

    private final @NonNull AssertionResultDAO assertionResultDAO;

    @Override
    public Mono<Long> insertBatch(@NonNull EntityType entityType,
            @NonNull List<? extends FeedbackScoreItem> assertionScores) {
        return assertionResultDAO.insertBatch(entityType, assertionScores);
    }
}
