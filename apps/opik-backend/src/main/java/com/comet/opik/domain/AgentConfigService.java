package com.comet.opik.domain;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.AgentConfigEnvUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@ImplementedBy(AgentConfigServiceImpl.class)
public interface AgentConfigService {

    AgentBlueprint createOrUpdateConfig(@NonNull AgentConfigCreate request);

    AgentBlueprint getLatestBlueprint(@NonNull UUID projectId, UUID maskId);

    AgentBlueprint getBlueprintById(@NonNull UUID blueprintId, UUID maskId);

    AgentBlueprint getBlueprintByEnv(@NonNull UUID projectId, @NonNull String envName, UUID maskId);

    AgentBlueprint getDeltaById(@NonNull UUID blueprintId);

    void createOrUpdateEnvs(@NonNull AgentConfigEnvUpdate request);

    AgentBlueprint.BlueprintPage getHistory(@NonNull UUID projectId, int page, int size);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AgentConfigServiceImpl implements AgentConfigService {

    private final @NonNull Provider<com.comet.opik.infrastructure.auth.RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ProjectService projectService;

    @Override
    public AgentBlueprint createOrUpdateConfig(@NonNull AgentConfigCreate request) {
        Preconditions.checkArgument(request.projectId() != null || request.projectName() != null,
                "Either projectId or projectName must be provided");

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Creating or updating optimizer config for workspace '{}'", workspaceId);

        UUID projectId = resolveProjectId(request, workspaceId, userName);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            UUID configId = getOrCreateConfig(dao, request, projectId, workspaceId, userName);

            return createBlueprint(dao, request, configId, projectId, workspaceId, userName);
        });
    }

    private UUID resolveProjectId(AgentConfigCreate request, String workspaceId, String userName) {
        if (request.projectId() != null) {
            return request.projectId();
        }

        String projectName = WorkspaceUtils.getProjectName(request.projectName());

        Mono<Project> projectMono = projectService.getOrCreate(projectName)
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId));

        return projectMono.block().id();
    }

    private UUID getOrCreateConfig(
            AgentConfigDAO dao,
            AgentConfigCreate request,
            UUID projectId,
            String workspaceId,
            String userName) {

        AgentConfig existingConfig = dao.getConfigByProjectId(workspaceId, projectId);

        if (existingConfig != null) {
            return existingConfig.id();
        }

        UUID configId = Objects.requireNonNullElseGet(request.id(), idGenerator::generateId);

        log.info("Creating new config '{}' for project '{}' in workspace '{}'", configId, projectId, workspaceId);

        dao.insertConfig(configId, workspaceId, projectId, userName, userName);

        return configId;
    }

    private AgentBlueprint createBlueprint(
            AgentConfigDAO dao,
            AgentConfigCreate request,
            UUID configId,
            UUID projectId,
            String workspaceId,
            String userName) {

        UUID blueprintId = Objects.requireNonNullElseGet(request.blueprint().id(), idGenerator::generateId);

        log.info("Creating blueprint '{}' for config '{}'", blueprintId, configId);

        List<String> keys = request.blueprint().values().stream()
                .map(AgentConfigValue::key)
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
            AgentConfigDAO dao,
            List<AgentConfigValue> values,
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
    public AgentBlueprint getLatestBlueprint(@NonNull UUID projectId, UUID maskId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving latest blueprint for project '{}' in workspace '{}'", projectId, workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            AgentConfig config = dao.getConfigByProjectId(workspaceId, projectId);
            if (config == null) {
                throw new NotFoundException("No configuration found for project '" + projectId + "'");
            }

            return getBlueprintWithDetails(dao, projectId, workspaceId, null, maskId);
        });
    }

    @Override
    public AgentBlueprint getBlueprintById(@NonNull UUID blueprintId, UUID maskId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving blueprint '{}' in workspace '{}'", blueprintId, workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            UUID projectId = dao.getProjectIdByBlueprintId(workspaceId, blueprintId);
            if (projectId == null) {
                throw new NotFoundException("Blueprint '" + blueprintId + "' not found");
            }

            return getBlueprintWithDetails(dao, projectId, workspaceId, blueprintId, maskId);
        });
    }

    @Override
    public AgentBlueprint getBlueprintByEnv(@NonNull UUID projectId, @NonNull String envName, UUID maskId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving blueprint by environment '{}' for project '{}' in workspace '{}'", envName, projectId,
                workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            AgentConfig config = dao.getConfigByProjectId(workspaceId, projectId);
            if (config == null) {
                throw new NotFoundException("No configuration found for project '" + projectId + "'");
            }

            UUID blueprintId = dao.getBlueprintIdByEnvName(workspaceId, projectId, envName);
            if (blueprintId == null) {
                throw new NotFoundException("No blueprint found for environment '" + envName + "'");
            }

            return getBlueprintWithDetails(dao, projectId, workspaceId, blueprintId, maskId);
        });
    }

    private AgentBlueprint getBlueprintWithDetails(
            AgentConfigDAO dao,
            UUID projectId,
            String workspaceId,
            UUID blueprintId,
            UUID maskId) {

        AgentBlueprint blueprint = blueprintId != null
                ? dao.getBlueprintById(workspaceId, blueprintId)
                : dao.getLatestBlueprint(workspaceId, projectId);

        if (blueprint == null) {
            throw new NotFoundException("Blueprint not found");
        }

        List<AgentConfigValue> values = dao.getValuesByBlueprintId(
                workspaceId, projectId, blueprint.id());

        if (maskId != null) {
            values = applyMask(dao, workspaceId, projectId, maskId, values);
        }

        List<String> envs = dao.getEnvsByBlueprintId(
                workspaceId, projectId, blueprint.id());

        return blueprint.toBuilder()
                .values(values)
                .envs(envs)
                .build();
    }

    @Override
    public AgentBlueprint getDeltaById(@NonNull UUID blueprintId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving delta for blueprint '{}' in workspace '{}'", blueprintId, workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            AgentBlueprint blueprint = dao.getBlueprintById(workspaceId, blueprintId);
            if (blueprint == null) {
                throw new NotFoundException("Blueprint '" + blueprintId + "' not found");
            }

            List<AgentConfigValue> deltaValues = dao.getValuesDeltaByBlueprintId(
                    workspaceId, blueprint.projectId(), blueprintId);

            return blueprint.toBuilder()
                    .values(deltaValues)
                    .build();
        });
    }

    @Override
    public void createOrUpdateEnvs(@NonNull AgentConfigEnvUpdate request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        UUID projectId = request.projectId();

        log.info("Creating or updating {} environments for project '{}' in workspace '{}'",
                request.envs().size(), projectId, workspaceId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            AgentConfig config = dao.getConfigByProjectId(workspaceId, projectId);
            if (config == null) {
                throw new NotFoundException("No configuration found for project '" + projectId + "'");
            }

            List<String> envNames = request.envs().stream()
                    .map(AgentConfigEnv::envName)
                    .toList();

            List<AgentConfigEnv> existingEnvs = dao.getEnvsByNames(workspaceId, projectId, envNames);
            Set<String> existingEnvNames = existingEnvs.stream()
                    .map(AgentConfigEnv::envName)
                    .collect(Collectors.toSet());

            List<AgentConfigEnv> newEnvs = request.envs().stream()
                    .filter(env -> !existingEnvNames.contains(env.envName()))
                    .map(env -> env.toBuilder().id(idGenerator.generateId()).build())
                    .toList();

            List<AgentConfigEnv> envsToUpdate = request.envs().stream()
                    .filter(env -> existingEnvNames.contains(env.envName()))
                    .toList();

            if (!newEnvs.isEmpty()) {
                log.info("Inserting {} new environments", newEnvs.size());
                dao.batchInsertEnvs(workspaceId, projectId, config.id(), userName, userName, newEnvs);
            }

            if (!envsToUpdate.isEmpty()) {
                log.info("Updating {} existing environments", envsToUpdate.size());
                dao.batchUpdateEnvs(workspaceId, projectId, userName, envsToUpdate);
            }

            return null;
        });
    }

    @Override
    public AgentBlueprint.BlueprintPage getHistory(@NonNull UUID projectId, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving blueprint history for project '{}', page {}, size {}", projectId, page, size);

        return transactionTemplate.inTransaction(handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            AgentConfig config = dao.getConfigByProjectId(workspaceId, projectId);
            if (config == null) {
                throw new NotFoundException("No configuration found for project '" + projectId + "'");
            }

            int offset = (page - 1) * size;
            List<AgentBlueprint> blueprints = dao.getBlueprintHistory(workspaceId, projectId, size, offset);
            long total = dao.countBlueprints(workspaceId, projectId);

            return AgentBlueprint.BlueprintPage.builder()
                    .page(page)
                    .size(size)
                    .total(total)
                    .content(blueprints)
                    .build();
        });
    }

    private List<AgentConfigValue> applyMask(
            AgentConfigDAO dao,
            String workspaceId,
            UUID projectId,
            UUID maskId,
            List<AgentConfigValue> blueprintValues) {

        AgentBlueprint mask = dao.getBlueprintById(workspaceId, maskId);
        if (mask == null) {
            throw new NotFoundException("Mask blueprint '" + maskId + "' not found");
        }
        Preconditions.checkArgument(mask.type() == AgentBlueprint.BlueprintType.MASK,
                "Blueprint '%s' is not a mask", maskId);

        List<AgentConfigValue> maskDelta = dao.getValuesDeltaByBlueprintId(
                workspaceId, projectId, maskId);

        Map<String, AgentConfigValue> valueMap = blueprintValues.stream()
                .collect(Collectors.toMap(
                        AgentConfigValue::key,
                        v -> v));

        for (AgentConfigValue maskValue : maskDelta) {
            valueMap.put(maskValue.key(), maskValue);
        }

        return new ArrayList<>(valueMap.values());
    }
}
