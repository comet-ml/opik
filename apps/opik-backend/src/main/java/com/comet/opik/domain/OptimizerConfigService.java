package com.comet.opik.domain;

import com.comet.opik.api.OptimizerConfigCreate;
import com.comet.opik.api.Project;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@ImplementedBy(OptimizerConfigServiceImpl.class)
public interface OptimizerConfigService {

    OptimizerBlueprint createOrUpdateConfig(@NonNull OptimizerConfigCreate request);

    OptimizerBlueprint getLatestBlueprint(@NonNull UUID configId);

    OptimizerBlueprint getBlueprintById(@NonNull UUID configId, @NonNull UUID blueprintId);

    OptimizerBlueprint getBlueprintByTag(@NonNull UUID configId, @NonNull String tag);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class OptimizerConfigServiceImpl implements OptimizerConfigService {

    private final @NonNull Provider<com.comet.opik.infrastructure.auth.RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ProjectService projectService;

    @Override
    public OptimizerBlueprint createOrUpdateConfig(@NonNull OptimizerConfigCreate request) {
        Preconditions.checkArgument(request.projectId() != null || request.projectName() != null,
                "Either projectId or projectName must be provided");

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Creating or updating optimizer config for workspace '{}'", workspaceId);

        UUID projectId = resolveProjectId(request, workspaceId, userName);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            OptimizerConfigDAO dao = handle.attach(OptimizerConfigDAO.class);

            UUID configId = getOrCreateConfig(dao, request, projectId, workspaceId, userName);

            return createBlueprint(dao, request, configId, projectId, workspaceId, userName);
        });
    }

    private UUID resolveProjectId(OptimizerConfigCreate request, String workspaceId, String userName) {
        if (request.projectId() != null) {
            return request.projectId();
        }

        String projectName = WorkspaceUtils.getProjectName(request.projectName());

        Mono<Project> projectMono = projectService.getOrCreate(projectName)
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId));

        return projectMono.block().id();
    }

    private UUID getOrCreateConfig(
            OptimizerConfigDAO dao,
            OptimizerConfigCreate request,
            UUID projectId,
            String workspaceId,
            String userName) {

        OptimizerConfig existingConfig = dao.getConfigByWorkspaceId(workspaceId);

        if (existingConfig != null) {
            return existingConfig.id();
        }

        UUID configId = Objects.requireNonNullElseGet(request.id(), idGenerator::generateId);

        log.info("Creating new config '{}' for workspace '{}'", configId, workspaceId);

        dao.insertConfig(configId, workspaceId, projectId, userName, userName);

        return configId;
    }

    private OptimizerBlueprint createBlueprint(
            OptimizerConfigDAO dao,
            OptimizerConfigCreate request,
            UUID configId,
            UUID projectId,
            String workspaceId,
            String userName) {

        UUID blueprintId = Objects.requireNonNullElseGet(request.blueprint().id(), idGenerator::generateId);

        log.info("Creating blueprint '{}' for config '{}'", blueprintId, configId);

        List<String> keys = request.blueprint().values().stream()
                .map(OptimizerConfigValue::key)
                .toList();

        log.info("Closing values for keys: {}", keys);
        dao.closeValuesForKeys(workspaceId, projectId, blueprintId, keys);

        dao.insertBlueprint(
                blueprintId,
                workspaceId,
                projectId,
                configId,
                request.blueprint().type(),
                request.blueprint().description(),
                userName,
                userName);

        insertValues(dao, request.blueprint().values(), configId, projectId, blueprintId, workspaceId);

        return request.blueprint().toBuilder()
                .id(blueprintId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();
    }

    private void insertValues(
            OptimizerConfigDAO dao,
            List<OptimizerConfigValue> values,
            UUID configId,
            UUID projectId,
            UUID validFromBlueprintId,
            String workspaceId) {

        if (values == null || values.isEmpty()) {
            return;
        }

        values = values.stream()
                .map(v -> v.toBuilder()
                        .id(idGenerator.generateId())
                        .validFromBlueprintId(validFromBlueprintId)
                        .build())
                .toList();

        dao.batchInsertValues(workspaceId, projectId, configId, values);
    }

    @Override
    public OptimizerBlueprint getLatestBlueprint(@NonNull UUID configId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving latest blueprint for config '{}' in workspace '{}'", configId, workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            OptimizerConfigDAO dao = handle.attach(OptimizerConfigDAO.class);
            return getBlueprintWithDetails(dao, configId, workspaceId, null);
        });
    }

    @Override
    public OptimizerBlueprint getBlueprintById(@NonNull UUID configId, @NonNull UUID blueprintId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving blueprint '{}' for config '{}' in workspace '{}'", blueprintId, configId, workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            OptimizerConfigDAO dao = handle.attach(OptimizerConfigDAO.class);
            return getBlueprintWithDetails(dao, configId, workspaceId, blueprintId);
        });
    }

    @Override
    public OptimizerBlueprint getBlueprintByTag(@NonNull UUID configId, @NonNull String tag) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving blueprint by tag '{}' for config '{}' in workspace '{}'", tag, configId, workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            OptimizerConfigDAO dao = handle.attach(OptimizerConfigDAO.class);

            UUID blueprintId = dao.getBlueprintIdByEnvName(workspaceId, configId, tag);

            Preconditions.checkArgument(blueprintId != null,
                    "No blueprint found for tag '%s' in config '%s'", tag, configId);

            return getBlueprintWithDetails(dao, configId, workspaceId, blueprintId);
        });
    }

    private OptimizerBlueprint getBlueprintWithDetails(
            OptimizerConfigDAO dao,
            UUID configId,
            String workspaceId,
            UUID blueprintId) {

        OptimizerBlueprint blueprint = blueprintId != null
                ? dao.getBlueprintById(workspaceId, configId, blueprintId)
                : dao.getLatestBlueprint(workspaceId, configId);

        Preconditions.checkArgument(blueprint != null,
                "Blueprint not found for config '%s'", configId);

        java.util.List<OptimizerConfigValue> values = dao.getValuesByBlueprintId(
                workspaceId, configId, blueprint.id());

        java.util.List<String> tags = dao.getTagsByBlueprintId(
                workspaceId, configId, blueprint.id());

        return blueprint.toBuilder()
                .values(values)
                .tags(tags)
                .build();
    }
}
