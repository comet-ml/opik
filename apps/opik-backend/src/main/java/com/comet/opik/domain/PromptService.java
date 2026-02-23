package com.comet.opik.domain;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersion.PromptVersionPage;
import com.comet.opik.api.PromptVersionBatchUpdate;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingFactoryPromptVersions;
import com.comet.opik.api.sorting.SortingFactoryPrompts;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.TemplateParseUtils;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.api.AlertEventType.PROMPT_COMMITTED;
import static com.comet.opik.api.AlertEventType.PROMPT_CREATED;
import static com.comet.opik.api.AlertEventType.PROMPT_DELETED;
import static com.comet.opik.api.Prompt.PromptPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static java.util.stream.Collectors.toMap;

@ImplementedBy(PromptServiceImpl.class)
public interface PromptService {
    Prompt create(Prompt promptRequest);

    PromptPage find(String name, int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters);

    PromptVersion createPromptVersion(CreatePromptVersion promptVersion);

    void update(@NonNull UUID id, Prompt prompt);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    Prompt getById(UUID id);

    List<Prompt> getByIds(Set<UUID> ids);

    PromptVersionPage getVersionsByPromptId(
            UUID promptId,
            String search,
            int page,
            int size,
            List<SortingField> sortingFields,
            List<? extends Filter> filters);

    PromptVersion getVersionById(UUID id);

    int updateVersions(PromptVersionBatchUpdate update);

    Mono<Map<UUID, PromptVersion>> findVersionByIds(Set<UUID> ids);

    PromptVersion retrievePromptVersion(String name, String commit);

    PromptVersion restorePromptVersion(UUID promptId, UUID versionId);

    Mono<Map<UUID, PromptVersionInfo>> getVersionsInfoByVersionsIds(Set<UUID> versionsIds);
}

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PromptServiceImpl implements PromptService {

    private static final String ALREADY_EXISTS = "Prompt id or name already exists";
    private static final String VERSION_ALREADY_EXISTS = "Prompt version already exists";
    private static final String PROMPT_NOT_FOUND = "Prompt not found";
    private static final String PROMPT_VERSION_NOT_FOUND = "Prompt version not found";

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SortingFactoryPrompts sortingFactory;
    private final @NonNull SortingFactoryPromptVersions sortingFactoryPromptVersions;
    private final @NonNull EventBus eventBus;

    @Override
    public Prompt create(@NonNull Prompt promptRequest) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String workspaceName = requestContext.get().getWorkspaceName();
        String userName = requestContext.get().getUserName();

        var newPrompt = promptRequest.toBuilder()
                .id(promptRequest.id() == null ? idGenerator.generateId() : promptRequest.id())
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        Prompt createdPrompt = EntityConstraintHandler
                .handle(() -> savePrompt(workspaceId, newPrompt))
                .withError(this::newPromptConflict);

        log.info("Prompt created with id '{}' name '{}', on workspace_id '{}'", createdPrompt.id(),
                createdPrompt.name(),
                workspaceId);

        if (!StringUtils.isEmpty(promptRequest.template())) {
            EntityConstraintHandler
                    .handle(() -> createPromptVersionFromPromptRequest(createdPrompt, workspaceId, promptRequest))
                    .withRetry(3, this::newVersionConflict);
        }

        eventBus.post(AlertEvent.builder()
                .eventType(PROMPT_CREATED)
                .userName(userName)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .payload(newPrompt)
                .build());

        return createdPrompt;
    }

    private PromptVersion createPromptVersionFromPromptRequest(Prompt createdPrompt,
            String workspaceId,
            Prompt promptRequest) {
        log.info("Creating prompt version for prompt id '{}'", createdPrompt.id());

        var createdVersion = transactionTemplate.inTransaction(WRITE, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            UUID versionId = idGenerator.generateId();
            PromptVersion promptVersion = PromptVersion.builder()
                    .id(versionId)
                    .promptId(createdPrompt.id())
                    .commit(CommitUtils.getCommit(versionId))
                    .template(promptRequest.template())
                    .metadata(promptRequest.metadata())
                    .changeDescription(promptRequest.changeDescription())
                    .type(promptRequest.type())
                    .createdBy(createdPrompt.createdBy())
                    .build();

            IdGenerator.validateVersion(promptVersion.id(), "prompt");

            promptVersionDAO.save(workspaceId, promptVersion);

            PromptVersion savedVersion = promptVersionDAO.findByIds(List.of(versionId), workspaceId).getFirst();
            return savedVersion;
        });

        log.info("Created Prompt version for prompt id '{}'", createdPrompt.id());

        return createdVersion;
    }

    private Prompt savePrompt(String workspaceId, Prompt prompt) {

        IdGenerator.validateVersion(prompt.id(), "prompt");

        transactionTemplate.inTransaction(WRITE, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            promptDAO.save(workspaceId, prompt);

            return null;
        });

        return getById(prompt.id());
    }

    @Override
    public PromptPage find(String name, int page, int size, List<SortingField> sortingFields,
            List<? extends Filter> filters) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        String filtersSQL = Optional.ofNullable(filters)
                .flatMap(f -> filterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.PROMPT))
                .orElse(null);

        Map<String, Object> filterMapping = Optional.ofNullable(filters)
                .map(filterQueryBuilder::toStateSQLMapping)
                .orElse(Map.of());

        return transactionTemplate.inTransaction(handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            long total = promptDAO.count(name, workspaceId, filtersSQL, filterMapping);

            var offset = (page - 1) * size;

            List<Prompt> content = promptDAO.find(name, workspaceId, offset, size, sortingFieldsSql, filtersSQL,
                    filterMapping);

            return PromptPage.builder()
                    .page(page)
                    .size(content.size())
                    .content(content)
                    .total(total)
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        });
    }

    private Prompt getOrCreatePrompt(String workspaceId, String name, String userName,
            TemplateStructure templateStructure) {

        Prompt prompt = findByName(workspaceId, name);

        if (prompt != null) {
            // For existing prompts, ignore the templateStructure parameter and use the existing prompt's structure.
            // Template structure is immutable after prompt creation.
            log.debug(
                    "Prompt '{}' already exists with template_structure '{}'. Ignoring requested template_structure '{}'.",
                    name, prompt.templateStructure().getValue(),
                    templateStructure != null ? templateStructure.getValue() : null);
            return prompt;
        }

        var newPrompt = Prompt.builder()
                .id(idGenerator.generateId())
                .name(name)
                .templateStructure(templateStructure)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        return EntityConstraintHandler
                .handle(() -> savePrompt(workspaceId, newPrompt))
                .onErrorDo(() -> findByName(workspaceId, name));
    }

    private Prompt findByName(String workspaceId, String name) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            return promptDAO.findByName(name, workspaceId);
        });
    }

    @Override
    public PromptVersion createPromptVersion(@NonNull CreatePromptVersion createPromptVersion) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String workspaceName = requestContext.get().getWorkspaceName();
        String userName = requestContext.get().getUserName();

        UUID id = createPromptVersion.version().id() == null
                ? idGenerator.generateId()
                : createPromptVersion.version().id();
        String commit = createPromptVersion.version().commit() == null
                ? CommitUtils.getCommit(id)
                : createPromptVersion.version().commit();

        IdGenerator.validateVersion(id, "prompt version");

        TemplateStructure templateStructure = createPromptVersion.templateStructure();

        Prompt prompt = getOrCreatePrompt(workspaceId, createPromptVersion.name(), userName, templateStructure);

        EntityConstraintHandler<PromptVersion> handler = EntityConstraintHandler.handle(() -> {
            PromptVersion promptVersion = createPromptVersion.version().toBuilder()
                    .promptId(prompt.id())
                    .createdBy(userName)
                    .id(id)
                    .commit(commit)
                    .build();

            var savedPromptVersion = savePromptVersion(workspaceId, promptVersion);
            postPromptCommittedEvent(savedPromptVersion, workspaceId, workspaceName, userName);

            return savedPromptVersion;
        });

        if (createPromptVersion.version().commit() != null) {
            return handler.withError(this::newVersionConflict);
        } else {
            // only retry if commit is not provided
            return handler.onErrorDo(() -> {
                var savedPromptVersion = retryableCreateVersion(workspaceId, createPromptVersion, prompt, userName);
                postPromptCommittedEvent(savedPromptVersion, workspaceId, workspaceName, userName);

                return savedPromptVersion;
            });
        }
    }

    @Override
    public void update(@NonNull UUID id, @NonNull Prompt prompt) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        EntityConstraintHandler
                .handle(() -> updatePrompt(id, prompt, userName, workspaceId))
                .withError(this::newPromptConflict);
    }

    private Prompt updatePrompt(UUID id, Prompt prompt, String userName, String workspaceId) {
        Prompt updatedPrompt = prompt.toBuilder()
                .lastUpdatedBy(userName)
                .id(id)
                .build();

        return transactionTemplate.inTransaction(WRITE, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            if (promptDAO.update(workspaceId, updatedPrompt, updatedPrompt.tags()) > 0) {
                log.info("Updated prompt with id '{}'", id);
            } else {
                log.info("Prompt with id '{}' not found", id);
                throw new NotFoundException(PROMPT_NOT_FOUND);
            }

            return updatedPrompt;
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String workspaceName = requestContext.get().getWorkspaceName();
        String userName = requestContext.get().getUserName();

        var prompt = getById(id);

        transactionTemplate.inTransaction(WRITE, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            if (promptDAO.delete(id, workspaceId) > 0) {

                PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

                promptVersionDAO.deleteByPromptId(id, workspaceId);

                log.info("Deleted prompt with id '{}'", id);

            } else {
                log.info("Prompt with id '{}' not found", id);
            }

            return null;
        });

        postPromptsDeletedEvent(List.of(prompt), workspaceId, workspaceName, userName);
    }

    @Override
    public void delete(Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        var prompts = getByIds(ids);

        String workspaceId = requestContext.get().getWorkspaceId();
        String workspaceName = requestContext.get().getWorkspaceName();
        String userName = requestContext.get().getUserName();

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(PromptDAO.class).delete(ids, workspaceId);
            return null;
        });

        postPromptsDeletedEvent(prompts, workspaceId, workspaceName, userName);
    }

    private PromptVersion retryableCreateVersion(String workspaceId, CreatePromptVersion request, Prompt prompt,
            String userName) {
        return EntityConstraintHandler.handle(() -> {
            UUID newId = idGenerator.generateId();

            PromptVersion promptVersion = request.version().toBuilder()
                    .promptId(prompt.id())
                    .createdBy(userName)
                    .id(newId)
                    .commit(CommitUtils.getCommit(newId))
                    .build();

            return savePromptVersion(workspaceId, promptVersion);

        }).withRetry(3, this::newVersionConflict);
    }

    private PromptVersion savePromptVersion(String workspaceId, PromptVersion promptVersion) {
        log.info("Creating prompt version for prompt id '{}'", promptVersion.promptId());

        IdGenerator.validateVersion(promptVersion.id(), "prompt version");

        transactionTemplate.inTransaction(WRITE, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            promptVersionDAO.save(workspaceId, promptVersion);
            promptDAO.updateLastUpdatedAt(promptVersion.promptId(), workspaceId, promptVersion.createdBy());

            return null;
        });

        log.info("Created Prompt version for prompt id '{}'", promptVersion.promptId());

        PromptVersion savedVersion = getById(workspaceId, promptVersion.id());
        return savedVersion;
    }

    private PromptVersion getById(String workspaceId, UUID id) {
        PromptVersion promptVersion = transactionTemplate.inTransaction(READ_ONLY, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            return promptVersionDAO.findByIds(List.of(id), workspaceId).getFirst();
        });

        return promptVersion.toBuilder()
                .variables(getVariables(promptVersion.template(), promptVersion.type()))
                .build();
    }

    @Override
    public Prompt getById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            Prompt prompt = promptDAO.findById(id, workspaceId);

            if (prompt == null) {
                throw new NotFoundException(PROMPT_NOT_FOUND);
            }

            return prompt.toBuilder()
                    .latestVersion(
                            Optional.ofNullable(prompt.latestVersion())
                                    .map(promptVersion -> promptVersion.toBuilder()
                                            .variables(getVariables(promptVersion.template(), promptVersion.type()))
                                            .build())
                                    .orElse(null))
                    .build();
        });
    }

    @Override
    public List<Prompt> getByIds(@NonNull Set<UUID> ids) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            return promptDAO.findByIds(ids, workspaceId);
        });
    }

    @Override
    public Mono<Map<UUID, PromptVersion>> findVersionByIds(@NonNull Set<UUID> ids) {

        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }

        return makeMonoContextAware((userName, workspaceId) -> Mono.fromCallable(() -> {
            return transactionTemplate.inTransaction(READ_ONLY, handle -> {
                PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

                return promptVersionDAO.findByIds(ids, workspaceId).stream()
                        .collect(toMap(PromptVersion::id, promptVersion -> promptVersion.toBuilder()
                                .variables(getVariables(promptVersion.template(), promptVersion.type()))
                                .build()));
            });
        })
                .flatMap(versions -> {
                    if (versions.size() != ids.size()) {
                        return Mono.error(new NotFoundException(PROMPT_VERSION_NOT_FOUND));
                    }
                    return Mono.just(versions);
                })
                .subscribeOn(Schedulers.boundedElastic()));
    }

    public PromptVersion getVersionById(@NonNull String workspaceId, @NonNull UUID id) {
        PromptVersion promptVersion = transactionTemplate.inTransaction(READ_ONLY, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            return promptVersionDAO.findByIds(List.of(id), workspaceId).stream().findFirst().orElse(null);
        });

        if (promptVersion == null) {
            throw new NotFoundException(PROMPT_VERSION_NOT_FOUND);
        }

        return promptVersion.toBuilder()
                .variables(getVariables(promptVersion.template(), promptVersion.type()))
                .build();
    }

    private Set<String> getVariables(String template, PromptType type) {
        if (template == null) {
            return null;
        }

        return TemplateParseUtils.extractVariables(template, type);
    }

    private EntityAlreadyExistsException newConflict(String alreadyExists) {
        log.info(alreadyExists);
        return new EntityAlreadyExistsException(new ErrorMessage(409, alreadyExists));
    }

    private EntityAlreadyExistsException newVersionConflict() {
        return newConflict(VERSION_ALREADY_EXISTS);
    }

    private EntityAlreadyExistsException newPromptConflict() {
        return newConflict(ALREADY_EXISTS);
    }

    @Override
    public PromptVersionPage getVersionsByPromptId(
            @NonNull UUID promptId,
            String search,
            int page,
            int size,
            @NonNull List<SortingField> sortingFields,
            List<? extends Filter> filters) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var sortingFieldMapping = sortingFactoryPromptVersions.newFieldMapping(sortingFields);
        var sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields, sortingFieldMapping);
        var filtersSQL = Optional.ofNullable(filters)
                .flatMap(filter -> filterQueryBuilder.toAnalyticsDbFilters(filter, FilterStrategy.PROMPT_VERSION))
                .orElse(null);
        var filterMapping = Optional.ofNullable(filters)
                .map(filter -> filterQueryBuilder.toStateSQLMapping(filter, FilterStrategy.PROMPT_VERSION))
                .orElse(Map.of());
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(PromptVersionDAO.class);
            var total = dao.findCount(workspaceId, promptId, search, filtersSQL, filterMapping);
            var offset = (page - 1) * size;
            var content = dao.find(
                    workspaceId, promptId, search, offset, size, sortingFieldsSql, filtersSQL, filterMapping);
            return PromptVersionPage.builder()
                    .page(page)
                    .size(content.size())
                    .content(content)
                    .total(total)
                    .sortableBy(sortingFactoryPromptVersions.getSortableFields())
                    .build();
        });
    }

    @Override
    public PromptVersion getVersionById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return getVersionById(workspaceId, id);
    }

    @Override
    public int updateVersions(@NonNull PromptVersionBatchUpdate update) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating prompt versions on workspaceId '{}', size '{}', mergeTags '{}'",
                workspaceId, update.ids().size(), update.mergeTags());
        int updatedCount = transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(PromptVersionDAO.class);
            return dao.update(workspaceId, update.ids(), update, update.mergeTags());
        });
        log.info("Successfully updated prompt versions on workspaceId '{}', size '{}', mergeTags '{}'",
                workspaceId, updatedCount, update.mergeTags());
        return updatedCount;
    }

    @Override
    public PromptVersion retrievePromptVersion(@NonNull String name, String commit) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            Prompt prompt = promptDAO.findByName(name, workspaceId);

            if (prompt == null) {
                throw new NotFoundException(PROMPT_NOT_FOUND);
            }

            PromptVersion promptVersion;
            if (commit == null) {
                // Fetch latest version directly from prompt_versions table
                List<PromptVersion> versions = promptVersionDAO.find(workspaceId, prompt.id(), 0, 1);
                if (versions.isEmpty()) {
                    throw new NotFoundException(PROMPT_VERSION_NOT_FOUND);
                }
                promptVersion = versions.getFirst();
            } else {
                promptVersion = promptVersionDAO.findByCommit(prompt.id(), commit, workspaceId);

                if (promptVersion == null) {
                    throw new NotFoundException(PROMPT_VERSION_NOT_FOUND);
                }
            }

            return promptVersion.toBuilder()
                    .variables(getVariables(promptVersion.template(), promptVersion.type()))
                    .build();
        });
    }

    @Override
    public PromptVersion restorePromptVersion(@NonNull UUID promptId, @NonNull UUID versionId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Restoring prompt version with id '{}' for prompt id '{}' on workspace_id '{}'",
                versionId, promptId, workspaceId);

        // Get the version to restore
        PromptVersion versionToRestore = getVersionById(versionId);

        // Verify the version belongs to the specified prompt
        if (!versionToRestore.promptId().equals(promptId)) {
            throw new NotFoundException("Prompt version not found for the specified prompt");
        }

        // Get the prompt to get its name
        Prompt prompt = getById(promptId);

        // Create a new version with the content from the old version
        UUID newVersionId = idGenerator.generateId();
        String newCommit = CommitUtils.getCommit(newVersionId);

        PromptVersion newVersion = versionToRestore.toBuilder()
                .id(newVersionId)
                .commit(newCommit)
                .createdBy(userName)
                .changeDescription("Restored from version " + versionToRestore.commit())
                .tags(null) // Don't propagate tags to restored version
                .build();

        PromptVersion restoredVersion = EntityConstraintHandler
                .handle(() -> savePromptVersion(workspaceId, newVersion))
                .onErrorDo(() -> retryableCreateVersion(workspaceId,
                        CreatePromptVersion.builder()
                                .name(prompt.name())
                                .version(newVersion)
                                .build(),
                        prompt, userName));

        log.info("Successfully restored prompt version with id '{}' for prompt id '{}' on workspace_id '{}'",
                versionId, promptId, workspaceId);

        return restoredVersion;
    }

    @Override
    public Mono<Map<UUID, PromptVersionInfo>> getVersionsInfoByVersionsIds(@NonNull Set<UUID> versionsIds) {

        if (versionsIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return makeMonoContextAware((userName, workspaceId) -> Mono
                .fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
                    PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

                    return promptVersionDAO.findPromptVersionInfoByVersionsIds(versionsIds, workspaceId).stream()
                            .collect(toMap(PromptVersionInfo::id, Function.identity()));
                })).subscribeOn(Schedulers.boundedElastic()));
    }

    private void postPromptCommittedEvent(PromptVersion promptVersion, String workspaceId, String workspaceName,
            String userName) {
        eventBus.post(AlertEvent.builder()
                .eventType(PROMPT_COMMITTED)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .userName(userName)
                .payload(promptVersion)
                .build());
    }

    private void postPromptsDeletedEvent(List<Prompt> prompts, String workspaceId, String workspaceName,
            String userName) {
        eventBus.post(AlertEvent.builder()
                .eventType(PROMPT_DELETED)
                .userName(userName)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .payload(prompts)
                .build());
    }
}
