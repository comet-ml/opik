package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPricesUsed;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.Handle;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import javax.annotation.Nullable;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(ModelUsageServiceImpl.class)
public interface ModelUsageService {

    int DAYS = ModelUsageStore.DAYS;

    /**
     * Saves which models a span write used, at most once a day per workspace, model and provider, in the background:
     * the write never waits for it or fails because of it. Only spans priced with an organization's prices are saved,
     * so nothing runs with custom model prices switched off or for a workspace whose organization is unknown.
     */
    void record(@NonNull PriceLayers layers, @NonNull String workspaceId, @Nullable String workspaceName,
            @NonNull Collection<UsedModel> models);

    /** Every model the organization's spans used in the last {@link #DAYS} days, the most used first. */
    Mono<CustomModelPricesUsed> findModelsUsed(@NonNull String organizationId);
}

@Singleton
@Slf4j
class ModelUsageServiceImpl implements ModelUsageService {

    private static final String KEY_PREFIX = "opik:model_prices_used:";
    // The day is part of every key, so this only bounds how long a claimed key lingers in Redis.
    private static final Duration CLAIM_TTL = Duration.ofHours(26);
    private static final Duration SAVE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);
    private static final String ORGANIZATION = "organization";
    private static final String BUILT_IN = "built_in";
    private static final String NONE = "none";

    private final TransactionTemplate transactionTemplate;
    private final RedissonReactiveClient redisClient;
    private final ModelPriceService modelPriceService;
    private final AtomicLong lastFailureLoggedAt = new AtomicLong();

    @Inject
    ModelUsageServiceImpl(@NonNull TransactionTemplate transactionTemplate,
            @NonNull RedissonReactiveClient redisClient, @NonNull ModelPriceService modelPriceService) {
        this.transactionTemplate = transactionTemplate;
        this.redisClient = redisClient;
        this.modelPriceService = modelPriceService;
    }

    @Override
    public void record(@NonNull PriceLayers layers, @NonNull String workspaceId, @Nullable String workspaceName,
            @NonNull Collection<UsedModel> models) {
        save(layers, workspaceId, workspaceName, models).subscribe();
    }

    Mono<Void> save(PriceLayers layers, String workspaceId, @Nullable String workspaceName,
            Collection<UsedModel> models) {
        String organizationId = layers.getOrganizationId();
        if (organizationId == null || models.isEmpty()) {
            return Mono.empty();
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String name = StringUtils.defaultString(workspaceName);
        return Flux.fromIterable(new LinkedHashSet<>(models))
                .filterWhen(model -> claim(modelKey(organizationId, today, workspaceId, model)))
                .collectList()
                .filter(claimed -> !claimed.isEmpty())
                .flatMap(claimed -> write(handle -> ModelUsageStore.save(handle, organizationId, today, workspaceId,
                        name, claimed))
                        .onErrorResume(error -> release(organizationId, today, workspaceId, claimed)
                                .then(Mono.error(error))))
                .then(Mono.defer(() -> deleteOldDaysOncePerDay(organizationId, today)))
                .timeout(SAVE_TIMEOUT)
                .onErrorResume(error -> {
                    logFailure(organizationId, error);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<CustomModelPricesUsed> findModelsUsed(@NonNull String organizationId) {
        var found = Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> ModelUsageStore.find(handle, organizationId, LocalDate.now(ZoneOffset.UTC))))
                .subscribeOn(Schedulers.boundedElastic());
        var layers = modelPriceService.findOrganizationList(organizationId).map(ModelPriceList::toLayers);
        return Mono.zip(found, layers).map(tuple -> CustomModelPricesUsed.builder()
                .days(DAYS)
                .truncated(tuple.getT1().truncated())
                .content(tuple.getT1().usages().stream()
                        .map(usage -> CustomModelPricesUsed.Model.builder()
                                .model(usage.model().modelName())
                                .provider(usage.model().provider())
                                .lastSeen(usage.lastSeen())
                                .daysUsed(usage.daysUsed())
                                .workspaces(usage.workspaces())
                                .priceSource(priceSource(usage.model(), tuple.getT2()))
                                .build())
                        .toList())
                .build());
    }

    private String priceSource(UsedModel model, PriceLayers layers) {
        return ModelPriceResolver.resolve(model.modelName(), StringUtils.trimToNull(model.provider()), layers)
                .map(match -> match.customRow() != null ? ORGANIZATION : BUILT_IN)
                .orElse(NONE);
    }

    private Mono<Boolean> claim(String key) {
        return redisClient.getBucket(key, StringCodec.INSTANCE).setIfAbsent("1", CLAIM_TTL);
    }

    // A failed write gives the claims back, so the next span write of the day tries again.
    private Mono<Void> release(String organizationId, LocalDate today, String workspaceId, List<UsedModel> claimed) {
        return Flux.fromIterable(claimed)
                .flatMap(model -> redisClient.getBucket(modelKey(organizationId, today, workspaceId, model),
                        StringCodec.INSTANCE).delete())
                .then();
    }

    private Mono<Void> deleteOldDaysOncePerDay(String organizationId, LocalDate today) {
        return claim(KEY_PREFIX + "clean-up:" + organizationId + ":" + today)
                .filter(Boolean::booleanValue)
                .flatMap(__ -> write(handle -> ModelUsageStore.deleteOldDays(handle, organizationId, today)));
    }

    private Mono<Void> write(Consumer<Handle> action) {
        return Mono.fromRunnable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
            action.accept(handle);
            return null;
        })).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void logFailure(String organizationId, Throwable error) {
        long now = System.currentTimeMillis();
        long last = lastFailureLoggedAt.get();
        if (now - last >= FAILURE_LOG_INTERVAL.toMillis() && lastFailureLoggedAt.compareAndSet(last, now)) {
            log.warn("Failed to save the models used, organization '{}'", organizationId, error);
        }
    }

    private static String modelKey(String organizationId, LocalDate today, String workspaceId, UsedModel model) {
        return KEY_PREFIX + organizationId + ":" + today + ":" + workspaceId + ":" + model.provider() + ":"
                + model.modelName();
    }
}
