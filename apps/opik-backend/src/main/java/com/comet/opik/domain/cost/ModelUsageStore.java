package com.comet.opik.domain.cost;

import com.comet.opik.api.modelprice.CustomModelPricesUsed;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.jdbi.v3.core.Handle;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** The SQL side of {@link ModelUsageService}, on a plain JDBI handle so it can be tested against MySQL alone. */
@UtilityClass
class ModelUsageStore {

    static final int DAYS = 30;
    static final int MAX_MODELS = 1_000;

    @Builder(toBuilder = true)
    record Usage(@NonNull UsedModel model, @NonNull LocalDate lastSeen, int daysUsed,
            @NonNull List<CustomModelPricesUsed.Workspace> workspaces) {
    }

    @Builder(toBuilder = true)
    record Found(@NonNull List<Usage> usages, boolean truncated) {
    }

    void save(@NonNull Handle handle, @NonNull String organizationId, @NonNull LocalDate day,
            @NonNull String workspaceId, @NonNull String workspaceName, @NonNull List<UsedModel> models) {
        handle.attach(ModelUsageDAO.class).insert(organizationId, day, workspaceId, workspaceName, models);
    }

    /** Drops the days that have left the window, so an organization keeps at most {@code DAYS + 1} days of rows. */
    int deleteOldDays(@NonNull Handle handle, @NonNull String organizationId, @NonNull LocalDate today) {
        return handle.attach(ModelUsageDAO.class).deleteBefore(organizationId, today.minusDays(DAYS));
    }

    Found find(@NonNull Handle handle, @NonNull String organizationId, @NonNull LocalDate today) {
        var dao = handle.attach(ModelUsageDAO.class);
        LocalDate since = today.minusDays(DAYS - 1);
        var models = dao.findModels(organizationId, since, MAX_MODELS + 1);
        var workspacesByModel = dao.findWorkspaces(organizationId, since, MAX_MODELS).stream()
                .collect(Collectors.groupingBy(row -> new UsedModel(row.modelName(), row.provider()),
                        Collectors.mapping(row -> CustomModelPricesUsed.Workspace.builder()
                                .id(row.workspaceId())
                                .name(row.workspaceName())
                                .build(), Collectors.toList())));
        var usages = models.stream()
                .limit(MAX_MODELS)
                .map(row -> {
                    var model = new UsedModel(row.modelName(), row.provider());
                    return Usage.builder()
                            .model(model)
                            .lastSeen(row.lastSeen())
                            .daysUsed(row.daysUsed())
                            .workspaces(workspacesByModel.getOrDefault(model, List.of()).stream()
                                    .sorted(Comparator.comparing(CustomModelPricesUsed.Workspace::name)
                                            .thenComparing(CustomModelPricesUsed.Workspace::id))
                                    .toList())
                            .build();
                })
                .toList();
        return Found.builder().usages(usages).truncated(models.size() > MAX_MODELS).build();
    }
}
