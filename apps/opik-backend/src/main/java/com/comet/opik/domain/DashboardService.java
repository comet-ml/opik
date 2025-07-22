package com.comet.opik.domain;

import com.comet.opik.api.dashboard.CreateDashboardRequest;
import com.comet.opik.api.dashboard.Dashboard;
import com.comet.opik.api.dashboard.DashboardLayout;
import com.comet.opik.api.dashboard.DashboardUpdate;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class DashboardService {

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;

    @RateLimited
    public Mono<Dashboard> create(CreateDashboardRequest request) {
        return makeMonoContextAware((userName, workspaceId) -> {
            var dashboard = Dashboard.builder()
                    .id(idGenerator.generateId())
                    .name(request.name())
                    .description(request.description())
                    .layout(DashboardLayout.builder().grid(List.of()).build())
                    .filters(Map.of())
                    .refreshInterval(30)
                    .createdAt(Instant.now())
                    .createdBy(userName)
                    .lastUpdatedAt(Instant.now())
                    .lastUpdatedBy(userName)
                    .build();
            return Mono.fromCallable(() -> template.inTransaction(WRITE, handle -> {
                try {
                    var dao = handle.attach(DashboardDAO.class);
                    // Manually serialize JSON objects
                    String layoutJson = JsonUtils.MAPPER.writeValueAsString(dashboard.layout());
                    String filtersJson = JsonUtils.MAPPER.writeValueAsString(dashboard.filters());
                    dao.save(dashboard, layoutJson, filtersJson, workspaceId);
                    return dashboard;
                } catch (Exception e) {
                    log.error("Failed to create dashboard", e);
                    throw new RuntimeException("Failed to create dashboard", e);
                }
            }));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Optional<Dashboard>> findById(UUID id) {
        return makeMonoContextAware((userName, workspaceId) -> {
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DashboardDAO.class);
                return dao.findById(id, workspaceId).map(this::convertToApiDashboard);
            }));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Dashboard> update(UUID id, DashboardUpdate request) {
        return makeMonoContextAware((userName, workspaceId) -> {
            return Mono.fromCallable(() -> template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DashboardDAO.class);
                // Manually serialize JSON objects if they exist in the update
                String layoutJson = request.layout() != null
                        ? JsonUtils.MAPPER.writeValueAsString(request.layout())
                        : null;
                String filtersJson = request.filters() != null
                        ? JsonUtils.MAPPER.writeValueAsString(request.filters())
                        : null;
                boolean updated = dao.update(id, request, layoutJson, filtersJson, userName, workspaceId);
                if (!updated) {
                    throw new IllegalArgumentException("Dashboard not found or update failed");
                }
                return dao.findById(id, workspaceId)
                        .map(this::convertToApiDashboard)
                        .orElseThrow(() -> new IllegalStateException("Dashboard disappeared after update"));
            }));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> delete(UUID id) {
        return makeMonoContextAware((userName, workspaceId) -> {
            return Mono.fromCallable(() -> template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DashboardDAO.class);
                boolean deleted = dao.deleteById(id, workspaceId);
                if (!deleted) {
                    throw new IllegalArgumentException("Dashboard not found");
                }
                return null;
            }));
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<Dashboard.DashboardPage> find(int page, int size, String search, String orderBy, String sortOrder) {
        return makeMonoContextAware((userName, workspaceId) -> {
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DashboardDAO.class);

                // Validate and set defaults
                var validOrderBy = (orderBy != null && !orderBy.isBlank()) ? orderBy : "created_at";
                var validSortOrder = (sortOrder != null && sortOrder.equalsIgnoreCase("asc")) ? "ASC" : "DESC";
                var offset = Math.max(0, page) * Math.max(1, size);

                // Get total count
                long total = dao.countByWorkspaceId(workspaceId, search);

                // Get dashboards
                List<Dashboard> dashboards = dao.findByWorkspaceId(
                        workspaceId, search, validOrderBy, validSortOrder, size, offset)
                        .stream()
                        .map(this::convertToApiDashboard)
                        .toList();

                return Dashboard.DashboardPage.builder()
                        .content(dashboards)
                        .page(page)
                        .size(size)
                        .total(total)
                        .build();
            }));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Dashboard>> findByWorkspaceId(String search, String sortField, String sortDirection, int limit,
            int offset) {
        return makeMonoContextAware((userName, workspaceId) -> {
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DashboardDAO.class);
                return dao.findByWorkspaceId(workspaceId, search, sortField, sortDirection, limit, offset)
                        .stream()
                        .map(this::convertToApiDashboard)
                        .toList();
            }));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Long> countByWorkspaceId(String search) {
        return makeMonoContextAware((userName, workspaceId) -> {
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DashboardDAO.class);
                return dao.countByWorkspaceId(workspaceId, search);
            }));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Dashboard convertToApiDashboard(DashboardDAO.DashboardEntity entity) {
        try {
            // Parse JSON strings back to objects
            DashboardLayout layout = JsonUtils.MAPPER.readValue(entity.layout(), DashboardLayout.class);
            Map<String, Object> filters = JsonUtils.MAPPER.readValue(entity.filters(),
                    new TypeReference<Map<String, Object>>() {
                    });

            return Dashboard.builder()
                    .id(entity.id())
                    .name(entity.name())
                    .description(entity.description())
                    .layout(layout)
                    .filters(filters)
                    .refreshInterval(entity.refreshInterval())
                    .createdAt(entity.createdAt())
                    .createdBy(entity.createdBy())
                    .lastUpdatedAt(entity.lastUpdatedAt())
                    .lastUpdatedBy(entity.lastUpdatedBy())
                    .build();
        } catch (Exception e) {
            log.error("Failed to convert DashboardEntity to Dashboard", e);
            throw new RuntimeException("Failed to convert dashboard entity", e);
        }
    }
}
