package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPriceHistoryPage;
import com.comet.opik.api.modelprice.CustomModelPriceList;
import com.comet.opik.api.modelprice.CustomModelPriceResolution;
import com.comet.opik.api.modelprice.CustomModelPriceResolveRequest;
import com.comet.opik.api.modelprice.CustomModelPriceRow;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.infrastructure.cache.CacheEvict;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import javax.annotation.Nullable;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(ModelPriceServiceImpl.class)
public interface ModelPriceService {

    String CACHE_NAME = "model_prices";

    record ReplaceRequest(@NonNull List<JsonNode> rows, @Nullable Long expectedVersion) {
    }

    /** The organization's saved rows; an organization with none gets an empty list at version 0. */
    Mono<ModelPriceList> findOrganizationList(@NonNull String organizationId);

    Mono<ModelPriceReplaceResult> replaceAll(@NonNull String organizationId, @NonNull ReplaceRequest request,
            @NonNull String userName);

    /** What {@link #replaceAll} would change, without writing anything. */
    Mono<ModelPriceReplaceResult> previewReplaceAll(@NonNull String organizationId, @NonNull ReplaceRequest request);

    Mono<CustomModelPriceList> findRows(@NonNull String organizationId);

    Mono<CustomModelPriceHistoryPage> findHistory(@NonNull String organizationId, int page, int size);

    /** The price layers the current workspace's spans are priced with: its organization's rows, or built-in only. */
    Mono<PriceLayers> layersFor();

    Mono<CustomModelPriceResolution> resolve(@NonNull CustomModelPriceResolveRequest request);
}

@Singleton
@Slf4j
class ModelPriceServiceImpl implements ModelPriceService {

    private final TransactionTemplate transactionTemplate;
    private final OrganizationResolver organizationResolver;
    private final ModelPriceStore store;

    @Inject
    ModelPriceServiceImpl(@NonNull TransactionTemplate transactionTemplate, @NonNull IdGenerator idGenerator,
            @NonNull OrganizationResolver organizationResolver) {
        this.transactionTemplate = transactionTemplate;
        this.organizationResolver = organizationResolver;
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

    // A Mono, not a plain value, so the cache interceptor evicts inside the chain, before the response goes out. A
    // plain method evicts later in the background, and the next span batch could still read the old prices.
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
            return result.withWarnings(validation.warnings());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ModelPriceReplaceResult> previewReplaceAll(@NonNull String organizationId,
            @NonNull ReplaceRequest request) {
        return Mono.fromCallable(() -> {
            var validation = validate(request);
            return transactionTemplate.inTransaction(READ_ONLY, handle -> store.previewReplaceAll(handle,
                    organizationId, validation.rows(), request.expectedVersion()))
                    .withWarnings(validation.warnings());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<CustomModelPriceList> findRows(@NonNull String organizationId) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(ModelPriceDAO.class);
            long version = dao.findList(organizationId).map(ModelPriceDAO.ListHeader::version).orElse(0L);
            return CustomModelPriceList.builder()
                    .version(version)
                    .rows(dao.findRows(organizationId).stream().map(CustomModelPriceRow::from).toList())
                    .build();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<CustomModelPriceHistoryPage> findHistory(@NonNull String organizationId, int page, int size) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(ModelPriceDAO.class);
            var entries = dao.findHistory(organizationId, size, (long) (page - 1) * size).stream()
                    .map(row -> CustomModelPriceHistoryPage.Entry.builder()
                            .listVersion(row.listVersion())
                            .rowId(UUID.fromString(row.rowId()))
                            .action(row.action())
                            .before(ModelPriceStore.HistorySnapshot.rowOf(row.beforeRow()))
                            .after(ModelPriceStore.HistorySnapshot.rowOf(row.afterRow()))
                            .createdAt(row.createdAt())
                            .createdBy(row.createdBy())
                            .build())
                    .toList();
            return CustomModelPriceHistoryPage.builder()
                    .page(page)
                    .size(entries.size())
                    .total(dao.countHistory(organizationId))
                    .content(entries)
                    .build();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PriceLayers> layersFor() {
        return organizationResolver.organizationId()
                .flatMap(organizationId -> findOrganizationList(organizationId).map(ModelPriceList::toLayers))
                .defaultIfEmpty(PriceLayers.BUILT_IN_ONLY);
    }

    @Override
    public Mono<CustomModelPriceResolution> resolve(@NonNull CustomModelPriceResolveRequest request) {
        return layersFor().map(layers -> ModelPriceExplainer.explain(request, layers));
    }

    private CustomModelPriceValidator.Result validate(ReplaceRequest request) {
        var validation = CustomModelPriceValidator.validate(request.rows());
        if (!validation.isValid()) {
            throw new ModelPriceValidationException(validation);
        }
        return validation;
    }
}
