package com.comet.opik.domain;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.Prompt.PromptPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(PromptServiceImpl.class)
public interface PromptService {
    Prompt create(Prompt prompt);

    PromptPage find(String name, int page, int size);

    PromptVersion createPromptVersion(CreatePromptVersion promptVersion);

    void update(@NonNull UUID id, Prompt prompt);

    void delete(UUID id);

}

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PromptServiceImpl implements PromptService {

    private static final String ALREADY_EXISTS = "Prompt id or name already exists";
    private static final String VERSION_ALREADY_EXISTS = "Prompt version already exists";

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public Prompt create(@NonNull Prompt prompt) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newPrompt = prompt.toBuilder()
                .id(prompt.id() == null ? idGenerator.generateId() : prompt.id())
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        Prompt createdPrompt = EntityConstraintHandler
                .handle(() -> savePrompt(workspaceId, newPrompt))
                .withError(this::newPromptConflict);

        log.info("Prompt created with id '{}' name '{}', on workspace_id '{}'", createdPrompt.id(),
                createdPrompt.name(),
                workspaceId);

        if (!StringUtils.isEmpty(prompt.template())) {
            EntityConstraintHandler
                    .handle(() -> createPromptVersionFromPromptRequest(createdPrompt, workspaceId, prompt.template()))
                    .withRetry(3, this::newVersionConflict);
        }

        return createdPrompt;
    }

    private PromptVersion createPromptVersionFromPromptRequest(Prompt createdPrompt, String workspaceId,
            String template) {
        log.info("Creating prompt version for prompt id '{}'", createdPrompt.id());

        var createdVersion = transactionTemplate.inTransaction(WRITE, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            UUID versionId = idGenerator.generateId();
            PromptVersion promptVersion = PromptVersion.builder()
                    .id(versionId)
                    .promptId(createdPrompt.id())
                    .commit(CommitUtils.getCommit(versionId))
                    .template(template)
                    .createdBy(createdPrompt.createdBy())
                    .build();

            IdGenerator.validateVersion(promptVersion.id(), "prompt");

            promptVersionDAO.save(workspaceId, promptVersion);

            return promptVersionDAO.findById(versionId, workspaceId);
        });

        log.info("Created Prompt version for prompt id '{}'", createdPrompt.id());

        return createdVersion;
    }

    private Prompt savePrompt(String workspaceId, Prompt prompt) {

        IdGenerator.validateVersion(prompt.id(), "prompt");

        return transactionTemplate.inTransaction(WRITE, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            promptDAO.save(workspaceId, prompt);

            return promptDAO.findById(prompt.id(), workspaceId);
        });
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
                ? CommitGenerator.generateCommit(id)
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
                throw new NotFoundException("Prompt not found");
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

    private PromptVersion retryableCreateVersion(String workspaceId, CreatePromptVersion request, Prompt prompt,
            String userName) {
        return EntityConstraintHandler.handle(() -> {
            UUID newId = idGenerator.generateId();

            PromptVersion promptVersion = request.version().toBuilder()
                    .promptId(prompt.id())
                    .createdBy(userName)
                    .id(newId)
                    .commit(CommitGenerator.generateCommit(newId))
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

            return promptVersionDAO.findById(id, workspaceId);
        });

        return promptVersion.toBuilder()
                .variables(getVariables(promptVersion.template()))
                .build();
    }

    private Set<String> getVariables(String template) {
        return MustacheVariableExtractor.extractVariables(template);
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
}
