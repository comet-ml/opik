package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.WorkspaceConfigurationRowMapper;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Optional;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(WorkspaceConfigurationDAOImpl.class)
public interface WorkspaceConfigurationDAO {

    WorkspaceConfiguration upsertConfiguration(String workspaceId, WorkspaceConfiguration configuration);

    Optional<WorkspaceConfiguration> getConfiguration(String workspaceId);

    void deleteConfiguration(String workspaceId);

    @RegisterRowMapper(WorkspaceConfigurationRowMapper.class)
    interface WorkspaceConfigurationSqlDAO {

        @SqlUpdate("""
                INSERT INTO workspace_configurations (workspace_id, timeout_mark_thread_as_inactive, created_at, last_updated_at, created_by, last_updated_by)
                VALUES (:workspace_id, :timeout_seconds, NOW(6), NOW(6), :user_name, :user_name)
                ON DUPLICATE KEY UPDATE
                    timeout_mark_thread_as_inactive = VALUES(timeout_mark_thread_as_inactive),
                    last_updated_at = NOW(6),
                    last_updated_by = VALUES(last_updated_by)
                """)
        int upsertConfiguration(@Bind("workspace_id") String workspaceId,
                @Bind("timeout_seconds") Long timeoutSeconds,
                @Bind("user_name") String userName);

        @SqlQuery("""
                SELECT timeout_mark_thread_as_inactive as timeoutToMarkThreadAsInactive
                FROM workspace_configurations
                WHERE workspace_id = :workspace_id
                """)
        Optional<WorkspaceConfiguration> getConfiguration(@Bind("workspace_id") String workspaceId);

        @SqlUpdate("""
                DELETE FROM workspace_configurations
                WHERE workspace_id = :workspace_id
                """)
        int deleteConfiguration(@Bind("workspace_id") String workspaceId);
    }
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class WorkspaceConfigurationDAOImpl implements WorkspaceConfigurationDAO {

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public WorkspaceConfiguration upsertConfiguration(@NonNull String workspaceId,
            @NonNull WorkspaceConfiguration configuration) {
        log.info("Upserting workspace configuration for workspace '{}'", workspaceId);

        String userName = requestContext.get().getUserName();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspaceConfigurationSqlDAO.class);

            Long timeoutSeconds = configuration.timeoutToMarkThreadAsInactive() != null
                    ? configuration.timeoutToMarkThreadAsInactive().toSeconds()
                    : null;

            dao.upsertConfiguration(workspaceId, timeoutSeconds, userName);

            log.info("Upserted workspace configuration for workspace '{}'", workspaceId);
            return configuration;
        });
    }

    @Override
    public Optional<WorkspaceConfiguration> getConfiguration(@NonNull String workspaceId) {
        log.info("Getting workspace configuration for workspace '{}'", workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(WorkspaceConfigurationSqlDAO.class);

            Optional<WorkspaceConfiguration> configuration = dao.getConfiguration(workspaceId);

            if (configuration.isPresent()) {
                log.info("Found workspace configuration for workspace '{}'", workspaceId);
                return configuration;
            }

            log.info("No workspace configuration found for workspace '{}'", workspaceId);
            return Optional.empty();
        });
    }

    @Override
    public void deleteConfiguration(@NonNull String workspaceId) {
        log.info("Deleting workspace configuration for workspace '{}'", workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspaceConfigurationSqlDAO.class);
            dao.deleteConfiguration(workspaceId);

            log.info("Deleted workspace configuration for workspace '{}'", workspaceId);
            return null;
        });
    }
}