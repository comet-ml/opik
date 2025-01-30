package com.comet.opik.domain;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersion.PromptVersionPage;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.TemplateParseUtils;
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

import static com.comet.opik.api.Prompt.PromptPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static java.util.stream.Collectors.toMap;

@ImplementedBy(PromptServiceImpl.class)
public interface PromptService {
    Prompt create(Prompt promptRequest);

    PromptPage find(String name, int page, int size);

    PromptVersion createPromptVersion(CreatePromptVersion promptVersion);

    void update(@NonNull UUID id, Prompt prompt);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    Prompt getById(UUID id);

    PromptVersionPage getVersionsByPromptId(UUID promptId, int page, int size);

    PromptVersion getVersionById(UUID id);

    Mono<Map<UUID, PromptVersion>> findVersionByIds(Set<UUID> ids);

    PromptVersion retrievePromptVersion(String name, String commit);

    Mono<Map<UUID, String>> getVersionsCommitByVersionsIds(Set<UUID> versionsIds);
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

    @Override
    public Prompt create(@NonNull Prompt promptRequest) {

        String workspaceId = requestContext.get().getWorkspaceId();
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

            return promptVersionDAO.findByIds(List.of(versionId), workspaceId).getFirst();
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
    public PromptPage find(String name, int page, int size) {

        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            long total = promptDAO.count(name, workspaceId);

            var offset = (page - 1) * size;

            List<Prompt> content = promptDAO.find(name, workspaceId, offset, size);

            return PromptPage.builder()
                    .page(page)
                    .size(content.size())
                    .content(content)
                    .total(total)
                    .build();
        });
    }

    private Prompt getOrCreatePrompt(String workspaceId, String name, String userName) {

        Prompt prompt = findByName(workspaceId, name);

        if (prompt != null) {
            return prompt;
        }

        var newPrompt = Prompt.builder()
                .id(idGenerator.generateId())
                .name(name)
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
        String userName = requestContext.get().getUserName();

        UUID id = createPromptVersion.version().id() == null
                ? idGenerator.generateId()
                : createPromptVersion.version().id();
        String commit = createPromptVersion.version().commit() == null
                ? CommitUtils.getCommit(id)
                : createPromptVersion.version().commit();

        IdGenerator.validateVersion(id, "prompt version");

        Prompt prompt = getOrCreatePrompt(workspaceId, createPromptVersion.name(), userName);

        EntityConstraintHandler<PromptVersion> handler = EntityConstraintHandler.handle(() -> {
            PromptVersion promptVersion = createPromptVersion.version().toBuilder()
                    .promptId(prompt.id())
                    .createdBy(userName)
                    .id(id)
                    .commit(commit)
                    .build();

            return savePromptVersion(workspaceId, promptVersion);
        });

        if (createPromptVersion.version().commit() != null) {
            return handler.withError(this::newVersionConflict);
        } else {
            // only retry if commit is not provided
            return handler.onErrorDo(() -> retryableCreateVersion(workspaceId, createPromptVersion, prompt, userName));
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

            if (promptDAO.update(workspaceId, updatedPrompt) > 0) {
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
    }

    @Override
    public void delete(Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(PromptDAO.class).delete(ids, workspaceId);
            return null;
        });
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

            promptVersionDAO.save(workspaceId, promptVersion);

            return null;
        });

        log.info("Created Prompt version for prompt id '{}'", promptVersion.promptId());

        return getById(workspaceId, promptVersion.id());
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
    public PromptVersionPage getVersionsByPromptId(@NonNull UUID promptId, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            long total = promptVersionDAO.countByPromptId(promptId, workspaceId);

            var offset = (page - 1) * size;

            List<PromptVersion> content = promptVersionDAO.findByPromptId(promptId, workspaceId, size, offset);

            return PromptVersionPage.builder()
                    .page(page)
                    .size(content.size())
                    .content(content)
                    .total(total)
                    .build();
        });
    }

    @Override
    public PromptVersion getVersionById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return getVersionById(workspaceId, id);
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

            if (commit == null) {
                return getById(prompt.id()).latestVersion();
            }

            PromptVersion promptVersion = promptVersionDAO.findByCommit(prompt.id(), commit, workspaceId);

            if (promptVersion == null) {
                throw new NotFoundException(PROMPT_VERSION_NOT_FOUND);
            }

            return promptVersion.toBuilder()
                    .variables(getVariables(promptVersion.template(), promptVersion.type()))
                    .build();
        });
    }

    @Override
    public Mono<Map<UUID, String>> getVersionsCommitByVersionsIds(@NonNull Set<UUID> versionsIds) {

        if (versionsIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return makeMonoContextAware((userName, workspaceId) -> Mono
                .fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY, handle -> {
                    PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

                    return promptVersionDAO.findCommitByVersionsIds(versionsIds, workspaceId).stream()
                            .collect(toMap(PromptVersionId::id, PromptVersionId::commit));
                })).subscribeOn(Schedulers.boundedElastic()));
    }
}
