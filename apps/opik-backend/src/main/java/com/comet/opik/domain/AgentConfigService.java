package com.comet.opik.domain;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.AgentConfigEnvUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
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

    AgentBlueprint getBlueprintByName(UUID projectId, String name, UUID maskId);

    AgentBlueprint getBlueprintByEnv(UUID projectId, String envName, UUID maskId);

    AgentBlueprint getDeltaById(UUID blueprintId);

    void createOrUpdateEnvs(AgentConfigEnvUpdate request);

    void setEnvByBlueprintName(UUID projectId, String envName, String blueprintName);

    AgentBlueprint.BlueprintPage getHistory(UUID projectId, int page, int size);

    List<UUID> updateBlueprintsForNewPromptVersion(
            String workspaceId,
            UUID promptId,
            String newCommit,
            String userName,
            Set<UUID> excludeProjectIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AgentConfigServiceImpl implements AgentConfigService {

    private static final String BLUEPRINT_LOCK = "agent_blueprint";

    private final @NonNull Provider<com.comet.opik.infrastructure.auth.RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ProjectService projectService;
    private final @NonNull LockService lockService;

    @Override
    public AgentBlueprint createOrUpdateConfig(@NonNull AgentConfigCreate request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Creating or updating optimizer config for workspace '{}'", workspaceId);

        UUID projectId = resolveProjectId(request, workspaceId, userName);

        return lockService.executeWithLock(
                new LockService.Lock(workspaceId, BLUEPRINT_LOCK),
                Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
                    AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

                    UUID configId = getOrCreateConfig(dao, request, projectId, workspaceId, userName);

                    return createBlueprint(dao, request, configId, projectId, workspaceId, userName);
                }))).block();
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

        AgentBlueprint blueprint = request.blueprint().toBuilder()
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        String name = generateNextBlueprintName(dao, workspaceId, projectId);
        UUID blueprintId = createBlueprintSnapshot(dao, workspaceId, projectId, configId, name, blueprint);

        return blueprint.toBuilder()
                .id(blueprintId)
                .name(name)
                .build();
    }

    private UUID createBlueprintSnapshot(
            AgentConfigDAO dao,
            String workspaceId,
            UUID projectId,
            UUID configId,
            String name,
            AgentBlueprint blueprint) {

        UUID blueprintId = Objects.requireNonNullElseGet(blueprint.id(), idGenerator::generateId);

        log.info("Creating blueprint '{}' with name '{}' for config '{}'", blueprintId, name, configId);

        if (blueprint.type() == AgentBlueprint.BlueprintType.BLUEPRINT) {
            List<String> keys = blueprint.values().stream()
                    .map(AgentConfigValue::key)
                    .toList();

            dao.closeValuesForKeys(workspaceId, projectId, blueprintId, keys);
        }

        dao.insertBlueprint(
                blueprintId,
                workspaceId,
                projectId,
                configId,
                blueprint.type(),
                name,
                blueprint.description(),
                blueprint.createdBy(),
                blueprint.lastUpdatedBy());

        insertValues(dao, blueprint.values(), configId, projectId, blueprintId, workspaceId);

        return blueprintId;
    }

    private String generateNextBlueprintName(AgentConfigDAO dao, String workspaceId, UUID projectId) {
        long count = dao.countBlueprints(workspaceId, projectId);
        return "v" + (count + 1);
    }

    static String incrementBlueprintName(String name) {
        int version = Integer.parseInt(name.substring(1));
        return "v" + (version + 1);
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
    public AgentBlueprint getBlueprintByName(@NonNull UUID projectId, @NonNull String name, UUID maskId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving blueprint by name '{}' for project '{}' in workspace '{}'", name, projectId, workspaceId);

        return transactionTemplate.inTransaction(handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            AgentBlueprint blueprint = requireBlueprintByName(dao, workspaceId, projectId, name);

            return getBlueprintWithDetails(dao, projectId, workspaceId, blueprint.id(), maskId);
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

    private AgentBlueprint requireBlueprintByName(AgentConfigDAO dao, String workspaceId, UUID projectId,
            String name) {
        AgentBlueprint blueprint = dao.getBlueprintByNameAndType(workspaceId, projectId, name,
                AgentBlueprint.BlueprintType.BLUEPRINT);
        if (blueprint == null) {
            throw new NotFoundException("Blueprint with name '" + name + "' not found");
        }
        return blueprint;
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

        long distinctEnvNames = request.envs().stream()
                .map(AgentConfigEnv::envName)
                .distinct()
                .count();
        if (distinctEnvNames != request.envs().size()) {
            throw new BadRequestException("Duplicate env names in request");
        }

        log.info("Creating or updating {} environments for project '{}' in workspace '{}'",
                request.envs().size(), projectId, workspaceId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            validateBlueprintReferences(dao, workspaceId, projectId, request.envs());
            upsertEnvs(dao, workspaceId, projectId, userName, request.envs());

            return null;
        });
    }

    @Override
    public void setEnvByBlueprintName(@NonNull UUID projectId, @NonNull String envName,
            @NonNull String blueprintName) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Setting environment '{}' to blueprint '{}' for project '{}' in workspace '{}'",
                envName, blueprintName, projectId, workspaceId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

            AgentBlueprint blueprint = requireBlueprintByName(dao, workspaceId, projectId, blueprintName);

            upsertEnvs(dao, workspaceId, projectId, userName,
                    List.of(AgentConfigEnv.builder()
                            .envName(envName)
                            .blueprintId(blueprint.id())
                            .build()));

            return null;
        });
    }

    private void upsertEnvs(AgentConfigDAO dao, String workspaceId, UUID projectId, String userName,
            List<AgentConfigEnv> envs) {
        AgentConfig config = requireConfig(dao, workspaceId, projectId);

        List<String> envNames = envs.stream()
                .map(AgentConfigEnv::envName)
                .toList();

        List<AgentConfigEnv> existingEnvs = dao.getEnvsByNames(workspaceId, projectId, envNames);
        Map<String, AgentConfigEnv> existingByName = existingEnvs.stream()
                .collect(Collectors.toMap(AgentConfigEnv::envName, e -> e));

        List<UUID> idsToClose = new ArrayList<>();
        List<AgentConfigEnv> envsToInsert = new ArrayList<>();

        for (AgentConfigEnv requested : envs) {
            AgentConfigEnv existing = existingByName.get(requested.envName());
            if (existing == null) {
                envsToInsert.add(requested.toBuilder().id(idGenerator.generateId()).build());
            } else if (!existing.blueprintId().equals(requested.blueprintId())) {
                idsToClose.add(existing.id());
                envsToInsert.add(requested.toBuilder().id(idGenerator.generateId()).build());
            }
        }

        if (!idsToClose.isEmpty()) {
            log.info("Closing {} existing environments for project '{}' in workspace '{}'",
                    idsToClose.size(), projectId, workspaceId);
            dao.batchCloseEnvs(workspaceId, projectId, idsToClose);
        }

        if (!envsToInsert.isEmpty()) {
            log.info("Inserting {} environments for project '{}' in workspace '{}': {}",
                    envsToInsert.size(), projectId, workspaceId,
                    envsToInsert.stream().map(AgentConfigEnv::envName).toList());
            dao.batchInsertEnvs(workspaceId, projectId, config.id(), userName, envsToInsert);
        }
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

    @Override
    public List<UUID> updateBlueprintsForNewPromptVersion(
            @NonNull String workspaceId,
            @NonNull UUID promptId,
            @NonNull String newCommit,
            @NonNull String userName,
            Set<UUID> excludeProjectIds) {

        log.info(
                "Updating blueprints for new prompt version: promptId='{}', commit='{}', workspace='{}', excludeProjects='{}'",
                promptId, newCommit, workspaceId, excludeProjectIds);

        List<UUID> createdBlueprintIds = lockService.executeWithLock(
                new LockService.Lock(workspaceId, BLUEPRINT_LOCK),
                Mono.fromCallable(() -> transactionTemplate.inTransaction(WRITE, handle -> {
                    AgentConfigDAO dao = handle.attach(AgentConfigDAO.class);

                    List<AgentConfigDAO.BlueprintValueReference> references = dao
                            .findProjectsWithOutdatedPromptReferences(workspaceId, promptId, newCommit,
                                    excludeProjectIds);

                    if (references.isEmpty()) {
                        log.info("No blueprints to update for prompt '{}' with commit '{}'", promptId, newCommit);
                        return List.<UUID>of();
                    }

                    Map<UUID, List<AgentConfigDAO.BlueprintValueReference>> referencesByProject = references.stream()
                            .collect(Collectors.groupingBy(AgentConfigDAO.BlueprintValueReference::projectId));

                    log.info("Found projects with outdated prompt references: '{}'", referencesByProject.size());

                    List<AgentConfigDAO.BlueprintInsertData> blueprintInserts = new ArrayList<>();
                    List<AgentConfigDAO.ValueCloseRef> valueCloses = new ArrayList<>();
                    List<AgentConfigDAO.ValueInsertData> valueInserts = new ArrayList<>();

                    for (var entry : referencesByProject.entrySet()) {
                        List<AgentConfigDAO.BlueprintValueReference> refs = entry.getValue();
                        UUID configId = refs.getFirst().configId();
                        String name = incrementBlueprintName(refs.getFirst().latestBlueprintName());
                        UUID blueprintId = idGenerator.generateId();

                        blueprintInserts.add(AgentConfigDAO.BlueprintInsertData.builder()
                                .id(blueprintId)
                                .projectId(entry.getKey())
                                .configId(configId)
                                .type(AgentBlueprint.BlueprintType.BLUEPRINT)
                                .name(name)
                                .build());

                        for (var ref : refs) {
                            valueCloses.add(AgentConfigDAO.ValueCloseRef.builder()
                                    .projectId(ref.projectId())
                                    .validToBlueprintId(blueprintId)
                                    .key(ref.configKey())
                                    .build());

                            valueInserts.add(AgentConfigDAO.ValueInsertData.builder()
                                    .id(idGenerator.generateId())
                                    .projectId(ref.projectId())
                                    .configId(configId)
                                    .key(ref.configKey())
                                    .value(newCommit)
                                    .type(AgentConfigValue.ValueType.PROMPT)
                                    .validFromBlueprintId(blueprintId)
                                    .build());
                        }
                    }

                    dao.batchCloseValuesByKey(workspaceId, valueCloses);
                    dao.batchInsertBlueprints(workspaceId, userName, userName, blueprintInserts);
                    dao.batchInsertValuesMultiProject(workspaceId, valueInserts);

                    return blueprintInserts.stream()
                            .map(AgentConfigDAO.BlueprintInsertData::id)
                            .toList();
                }))).block();

        log.info("Completed blueprint updates for prompt, created blueprints: '{}'", createdBlueprintIds.size());
        return createdBlueprintIds;
    }
}
