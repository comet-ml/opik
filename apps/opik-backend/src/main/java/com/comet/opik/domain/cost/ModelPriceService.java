package com.comet.opik.domain.cost;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.cache.CacheEvict;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import javax.annotation.Nullable;

import java.util.List;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(ModelPriceServiceImpl.class)
public interface ModelPriceService {

    String CACHE_NAME = "model_prices";

    @Builder(toBuilder = true)
    record ReplaceRequest(@NonNull List<JsonNode> rows, @Nullable Long expectedVersion) {
    }

    /** The organization's saved rows; an organization with none gets an empty list at version 0. */
    Mono<ModelPriceList> findOrganizationList(String organizationId);

    Mono<ModelPriceReplaceResult> replaceAll(String organizationId, ReplaceRequest request, String userName);

    /**
     * What {@link #replaceAll} would change, without writing anything. Created rows have no audit fields, and their
     * ids are made up for the preview: the save that follows gives them new ones.
     */
    Mono<ModelPriceReplaceResult> previewReplaceAll(String organizationId, ReplaceRequest request);

    /**
     * The price layers a workspace's spans are priced with: its organization's rows, or the built-in list only. Never
     * fails: when the organization's rows can't be loaded, it's the built-in list only.
     */
    Mono<PriceLayers> layersFor(String workspaceId);
}

@Singleton
@Slf4j
class ModelPriceServiceImpl implements ModelPriceService {

    private final TransactionTemplate transactionTemplate;
    private final WorkspaceOrganizationLookup organizationLookup;
    private final ModelPriceStore store;

    @Inject
    ModelPriceServiceImpl(@NonNull TransactionTemplate transactionTemplate, @NonNull IdGenerator idGenerator,
            @NonNull WorkspaceOrganizationLookup organizationLookup) {
        this.transactionTemplate = transactionTemplate;
        this.organizationLookup = organizationLookup;
        this.store = new ModelPriceStore(idGenerator::generateId);
    }

    // Returning a value even for an organization with no rows matters: the cache never stores an empty Mono, and most
    // organizations have no rows, so every batch would otherwise read MySQL.
    @Override
    @Cacheable(name = CACHE_NAME, key = "'organization-' + $organizationId", returnType = ModelPriceList.class)
    public Mono<ModelPriceList> findOrganizationList(@NonNull String organizationId) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> store.load(handle, organizationId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // A Mono, not a plain value, so the cache interceptor evicts inside the chain, before the response goes out; a
    // plain method evicts later in the background. A read that loaded the old list before this save committed can
    // still cache it after the eviction, and it stays until the model_prices TTL ends it.
    @Override
    @CacheEvict(name = CACHE_NAME, key = "'organization-' + $organizationId")
    public Mono<ModelPriceReplaceResult> replaceAll(@NonNull String organizationId, @NonNull ReplaceRequest request,
            @NonNull String userName) {
        return Mono.fromCallable(() -> {
            var validation = validate(request);
            var result = transactionTemplate.inTransaction(WRITE, handle -> store.replaceAll(handle, organizationId,
                    validation.rows(), request.expectedVersion(), userName));
            if (result.saved()) {
                log.info("Replaced model prices, organization '{}', version '{}'", organizationId, result.version());
            } else {
                log.info("Model prices unchanged, organization '{}', version '{}'", organizationId, result.version());
            }
            return result.toBuilder().warnings(validation.warnings()).build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ModelPriceReplaceResult> previewReplaceAll(@NonNull String organizationId,
            @NonNull ReplaceRequest request) {
        return Mono.fromCallable(() -> {
            var validation = validate(request);
            return transactionTemplate.inTransaction(READ_ONLY, handle -> store.previewReplaceAll(handle,
                    organizationId, validation.rows(), request.expectedVersion()))
                    .toBuilder()
                    .warnings(validation.warnings())
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PriceLayers> layersFor(@NonNull String workspaceId) {
        return organizationLookup.organizationOf(workspaceId)
                .flatMap(organizationId -> findOrganizationList(organizationId).map(ModelPriceList::toLayers))
                .defaultIfEmpty(PriceLayers.BUILT_IN_ONLY)
                .onErrorResume(error -> {
                    log.warn("Failed to load model prices, pricing with built-in prices, workspace '{}'", workspaceId,
                            error);
                    return Mono.just(PriceLayers.BUILT_IN_ONLY);
                });
    }

    private CustomModelPriceValidator.Result validate(ReplaceRequest request) {
        var validation = CustomModelPriceValidator.validate(request.rows());
        if (!validation.isValid()) {
            throw new ModelPriceValidationException(validation);
        }
        return validation;
    }
}
