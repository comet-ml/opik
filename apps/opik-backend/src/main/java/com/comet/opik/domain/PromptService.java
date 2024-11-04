package com.comet.opik.domain;

import com.comet.opik.api.Prompt;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;

import static com.comet.opik.api.Prompt.PromptPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(PromptServiceImpl.class)
public interface PromptService {
    Prompt prompt(Prompt prompt);

    PromptPage find(String name, int page, int size);
}

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PromptServiceImpl implements PromptService {

    public static final String ALREADY_EXISTS = "Prompt id or name already exists";
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public Prompt prompt(Prompt prompt) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newPrompt = prompt.toBuilder()
                .id(prompt.id() == null ? idGenerator.generateId() : prompt.id())
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        IdGenerator.validateVersion(newPrompt.id(), "prompt");

        return EntityConstraintHandler
                .handle(() -> savePrompt(workspaceId, newPrompt))
                .withError(this::newConflict);
    }

    private Prompt savePrompt(String workspaceId, Prompt newPrompt) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            promptDAO.save(workspaceId, newPrompt);

            return promptDAO.findById(newPrompt.id(), workspaceId);
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

    private EntityAlreadyExistsException newConflict() {
        log.info(ALREADY_EXISTS);
        return new EntityAlreadyExistsException(new ErrorMessage(ALREADY_EXISTS));
    }

}
