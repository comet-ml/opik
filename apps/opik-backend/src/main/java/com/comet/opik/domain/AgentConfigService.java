package com.comet.opik.domain;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.AgentConfigEnvUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.utils.WorkspaceUtils;
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

    AgentBlueprint createOrUpdateConfig(AgentConfigCreate request);

    AgentBlueprint getLatestBlueprint(UUID projectId, UUID maskId);

    AgentBlueprint getBlueprintById(UUID blueprintId, UUID maskId);

    AgentBlueprint getBlueprintByEnv(UUID projectId, String envName, UUID maskId);

    AgentBlueprint getDeltaById(UUID blueprintId);

    void createOrUpdateEnvs(AgentConfigEnvUpdate request);

    AgentBlueprint.BlueprintPage getHistory(UUID projectId, int page, int size);
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
            projectService.get(request.projectId(), workspaceId);
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

            requireConfig(dao, workspaceId, projectId);

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

            requireConfig(dao, workspaceId, projectId);

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
                ? dao.getBlueprintByIdAndType(workspaceId, blueprintId, projectId,
                        AgentBlueprint.BlueprintType.BLUEPRINT)
                : dao.getLatestBlueprint(workspaceId, projectId, AgentBlueprint.BlueprintType.BLUEPRINT);

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

    private AgentConfig requireConfig(AgentConfigDAO dao, String workspaceId, UUID projectId) {
        AgentConfig config = dao.getConfigByProjectId(workspaceId, projectId);
        if (config == null) {
            throw new NotFoundException("No configuration found for project '" + projectId + "'");
        }
        return config;
    }

    private void validateBlueprintReferences(
            AgentConfigDAO dao,
            String workspaceId,
            UUID projectId,
            List<AgentConfigEnv> envs) {

        List<UUID> blueprintIds = envs.stream()
                .map(AgentConfigEnv::blueprintId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (blueprintIds.isEmpty()) {
            log.info("No blueprint IDs to validate for project '{}' in workspace '{}'", projectId, workspaceId);
            return;
        }

        List<AgentConfigDAO.BlueprintProject> blueprints = dao.getBlueprintsByIds(workspaceId, projectId, blueprintIds);

        if (blueprints.size() != blueprintIds.size()) {
            Set<UUID> foundIds = blueprints.stream()
                    .map(AgentConfigDAO.BlueprintProject::id)
                    .collect(Collectors.toSet());
            List<UUID> missingIds = blueprintIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .toList();
            throw new NotFoundException("Blueprints not found: " + missingIds);
        }
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

            AgentConfig config = requireConfig(dao, workspaceId, projectId);
            validateBlueprintReferences(dao, workspaceId, projectId, request.envs());

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
                log.info("Inserting {} new environments for project '{}' in workspace '{}': {}",
                        newEnvs.size(), projectId, workspaceId,
                        newEnvs.stream().map(AgentConfigEnv::envName).toList());
                dao.batchInsertEnvs(workspaceId, projectId, config.id(), userName, userName, newEnvs);
            }

            if (!envsToUpdate.isEmpty()) {
                log.info("Updating {} existing environments for project '{}' in workspace '{}': {}",
                        envsToUpdate.size(), projectId, workspaceId,
                        envsToUpdate.stream().map(AgentConfigEnv::envName).toList());
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

            requireConfig(dao, workspaceId, projectId);

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

        AgentBlueprint mask = dao.getBlueprintByIdAndType(workspaceId, maskId, projectId,
                AgentBlueprint.BlueprintType.MASK);
        if (mask == null) {
            throw new NotFoundException("Mask blueprint '" + maskId + "' not found in project '" + projectId + "'");
        }

        List<AgentConfigValue> maskDelta = dao.getValuesDeltaByBlueprintId(
                workspaceId, mask.projectId(), maskId);

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
