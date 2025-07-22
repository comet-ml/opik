package com.comet.opik.domain;

import com.comet.opik.api.dashboard.CreateDashboardRequest;
import com.comet.opik.api.dashboard.Dashboard;
import com.comet.opik.api.dashboard.DashboardLayout;
import com.comet.opik.api.dashboard.DashboardUpdate;
import com.comet.opik.api.dashboard.WidgetLayout;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.AsyncUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.dashboard.Dashboard.DashboardPage;

@ImplementedBy(DashboardService.DashboardServiceImpl.class)
public interface DashboardService {

    Mono<Dashboard> create(CreateDashboardRequest request);

    Mono<Dashboard> findById(UUID id);

    Mono<Dashboard> update(UUID id, DashboardUpdate update);

    Mono<Void> delete(UUID id);

    Mono<DashboardPage> find(int page, int size, String search, String orderBy, String sortOrder);

    Mono<List<Dashboard>> findAllByWorkspace();

    @Singleton
    @Slf4j
    @RequiredArgsConstructor(onConstructor_ = @Inject)
    class DashboardServiceImpl implements DashboardService {

        private final @NonNull Provider<RequestContext> requestContext;
        private final @NonNull TransactionTemplate template;
        private final @NonNull IdGenerator idGenerator;

        @Override
        @WithSpan
        public Mono<Dashboard> create(CreateDashboardRequest request) {
            return AsyncUtils.makeFluxContextAware(bindRequestContext -> {
                var context = requestContext.get();
                var dashboard = Dashboard.builder()
                        .id(idGenerator.generateId())
                        .name(request.name())
                        .description(request.description())
                        .layout(DashboardLayout.builder()
                                .grid(List.of())
                                .build())
                        .filters(new HashMap<>())
                        .refreshInterval(30)
                        .createdAt(Instant.now())
                        .createdBy(context.getUserName())
                        .lastUpdatedAt(Instant.now())
                        .lastUpdatedBy(context.getUserName())
                        .build();

                return template.inTransaction(handle -> {
                    var workspaceId = context.getWorkspaceId();
                    var dao = handle.attach(DashboardDAO.class);
                    dao.save(dashboard, workspaceId);
                    return dashboard;
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        @WithSpan
        public Mono<Dashboard> findById(UUID id) {
            return AsyncUtils.makeFluxContextAware(bindRequestContext -> {
                var context = requestContext.get();
                var workspaceId = context.getWorkspaceId();

                return template.inTransaction(handle -> {
                    var dao = handle.attach(DashboardDAO.class);
                    return dao.findById(id, workspaceId)
                            .orElseThrow(() -> new NotFoundException(
                                    ErrorMessage.builder()
                                            .message("Dashboard not found")
                                            .build()
                                            .formatted()));
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        @WithSpan
        public Mono<Dashboard> update(UUID id, DashboardUpdate update) {
            return AsyncUtils.makeFluxContextAware(bindRequestContext -> {
                var context = requestContext.get();
                var workspaceId = context.getWorkspaceId();

                return template.inTransaction(handle -> {
                    var dao = handle.attach(DashboardDAO.class);
                    
                    // First check if dashboard exists
                    var existingDashboard = dao.findById(id, workspaceId)
                            .orElseThrow(() -> new NotFoundException(
                                    ErrorMessage.builder()
                                            .message("Dashboard not found")
                                            .build()
                                            .formatted()));

                    // Update the dashboard
                    boolean updated = dao.update(id, update, context.getUserName(), workspaceId);
                    if (!updated) {
                        throw new NotFoundException(
                                ErrorMessage.builder()
                                        .message("Dashboard not found or could not be updated")
                                        .build()
                                        .formatted());
                    }

                    // Return the updated dashboard
                    return dao.findById(id, workspaceId)
                            .orElseThrow(() -> new NotFoundException(
                                    ErrorMessage.builder()
                                            .message("Dashboard not found after update")
                                            .build()
                                            .formatted()));
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        @WithSpan
        public Mono<Void> delete(UUID id) {
            return AsyncUtils.makeFluxContextAware(bindRequestContext -> {
                var context = requestContext.get();
                var workspaceId = context.getWorkspaceId();

                return template.inTransaction(handle -> {
                    var dao = handle.attach(DashboardDAO.class);
                    boolean deleted = dao.deleteById(id, workspaceId);
                    if (!deleted) {
                        throw new NotFoundException(
                                ErrorMessage.builder()
                                        .message("Dashboard not found")
                                        .build()
                                        .formatted());
                    }
                    return null;
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        @WithSpan
        public Mono<DashboardPage> find(int page, int size, String search, String orderBy, String sortOrder) {
            return AsyncUtils.makeFluxContextAware(bindRequestContext -> {
                var context = requestContext.get();
                var workspaceId = context.getWorkspaceId();

                return template.inTransaction(handle -> {
                    var dao = handle.attach(DashboardDAO.class);
                    
                    // Validate and set defaults
                    var validOrderBy = (orderBy != null && !orderBy.isBlank()) ? orderBy : "created_at";
                    var validSortOrder = (sortOrder != null && sortOrder.equalsIgnoreCase("asc")) ? "ASC" : "DESC";
                    var offset = Math.max(0, page) * Math.max(1, size);

                    // Get total count
                    long total = dao.countDashboards(workspaceId, search);

                    // Get dashboards
                    List<Dashboard> dashboards = dao.find(
                            workspaceId, search, size, offset, validOrderBy, validSortOrder);

                    return DashboardPage.builder()
                            .content(dashboards)
                            .page(page)
                            .size(size)
                            .total(total)
                            .build();
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }

        @Override
        @WithSpan
        public Mono<List<Dashboard>> findAllByWorkspace() {
            return AsyncUtils.makeFluxContextAware(bindRequestContext -> {
                var context = requestContext.get();
                var workspaceId = context.getWorkspaceId();

                return template.inTransaction(handle -> {
                    var dao = handle.attach(DashboardDAO.class);
                    return dao.findAllByWorkspaceId(workspaceId);
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }
    }
}
