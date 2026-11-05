package com.comet.opik.domain.cost;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.LocalDate;
import java.util.List;

@RegisterConstructorMapper(ModelUsageDAO.UsedModelRow.class)
@RegisterConstructorMapper(ModelUsageDAO.UsedModelWorkspaceRow.class)
interface ModelUsageDAO {

    record UsedModelRow(String modelName, String provider, LocalDate lastSeen, int daysUsed) {
    }

    record UsedModelWorkspaceRow(String modelName, String provider, String workspaceId, String workspaceName) {
    }

    // IGNORE: two span writes racing past the daily Redis check write the same row, and either one is enough.
    @SqlBatch("INSERT IGNORE INTO model_prices_used (organization_id, used_on, workspace_id, workspace_name," +
            " model_name, provider)" +
            " VALUES (:organizationId, :usedOn, :workspaceId, :workspaceName, :model.modelName, :model.provider)")
    void insert(@Bind("organizationId") String organizationId, @Bind("usedOn") LocalDate usedOn,
            @Bind("workspaceId") String workspaceId, @Bind("workspaceName") String workspaceName,
            @BindMethods("model") List<UsedModel> models);

    @SqlUpdate("DELETE FROM model_prices_used WHERE organization_id = :organizationId AND used_on < :before")
    int deleteBefore(@Bind("organizationId") String organizationId, @Bind("before") LocalDate before);

    @SqlQuery("SELECT model_name, provider, MAX(used_on) AS last_seen, COUNT(DISTINCT used_on) AS days_used" +
            " FROM model_prices_used" +
            " WHERE organization_id = :organizationId AND used_on >= :since" +
            " GROUP BY model_name, provider" +
            " ORDER BY days_used DESC, last_seen DESC, model_name, provider" +
            " LIMIT :limit")
    List<UsedModelRow> findModels(@Bind("organizationId") String organizationId, @Bind("since") LocalDate since,
            @Bind("limit") int limit);

    // Only the workspaces of the models findModels returns (same order, same limit), each under its latest name.
    @SqlQuery("SELECT DISTINCT used.model_name, used.provider, used.workspace_id," +
            " FIRST_VALUE(used.workspace_name) OVER (PARTITION BY used.workspace_id" +
            " ORDER BY used.used_on DESC, used.workspace_name) AS workspace_name" +
            " FROM model_prices_used used" +
            " JOIN (SELECT model_name, provider FROM model_prices_used" +
            " WHERE organization_id = :organizationId AND used_on >= :since" +
            " GROUP BY model_name, provider" +
            " ORDER BY COUNT(DISTINCT used_on) DESC, MAX(used_on) DESC, model_name, provider" +
            " LIMIT :limit) top_models" +
            " ON top_models.model_name = used.model_name AND top_models.provider = used.provider" +
            " WHERE used.organization_id = :organizationId AND used.used_on >= :since")
    List<UsedModelWorkspaceRow> findWorkspaces(@Bind("organizationId") String organizationId,
            @Bind("since") LocalDate since, @Bind("limit") int limit);
}
