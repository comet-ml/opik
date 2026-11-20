package com.comet.opik.domain.view;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.view.View;
import com.comet.opik.api.view.ViewAuthor;
import com.comet.opik.api.view.ViewCreate;
import com.comet.opik.api.view.ViewUpdate;
import com.comet.opik.api.view.ViewVersion;
import com.comet.opik.api.view.ViewVersionCreate;
import com.comet.opik.api.view.ViewVersionPage;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(ViewServiceImpl.class)
public interface ViewService {

    View create(ViewCreate request);

    ViewVersion createVersion(UUID id, ViewVersionCreate request);

    View get(UUID id);

    ViewVersionPage getVersions(UUID id, int page, int size);

    ViewVersion getVersion(UUID id, int version);

    View setCurrentVersion(UUID id, int version);

    void deleteVersion(UUID id, int version);

    View update(UUID id, ViewUpdate update);

    void delete(UUID id);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ViewServiceImpl implements ViewService {

    private static final String VIEW_NOT_FOUND = "View not found";
    private static final String VIEW_VERSION_NOT_FOUND = "View version not found";

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public View create(@NonNull ViewCreate request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        UUID id = idGenerator.generateId();
        ViewVersion version = ViewVersion.builder()
                .viewId(id)
                .version(1)
                .author(ViewAuthor.orDefault(request.author()))
                .summary(request.summary())
                .type(request.type())
                .engineVersion(request.engineVersion())
                .inputs(request.inputs())
                .libraries(request.libraries() == null ? Set.of() : request.libraries())
                .html(request.html())
                .createdBy(userName)
                .build();
        View view = View.builder()
                .id(id)
                .area(request.area())
                .name(request.name())
                .description(request.description())
                .currentVersion(version.version())
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        View created = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ViewDAO.class);
            dao.save(view, workspaceId);
            dao.saveVersion(version, workspaceId);
            return loadView(dao, id, workspaceId);
        });
        log.info("Created view, id '{}', area '{}', author '{}', engineVersion '{}', workspaceId '{}'", id,
                request.area(), version.author(), version.engineVersion(), workspaceId);
        return created;
    }

    @Override
    public ViewVersion createVersion(@NonNull UUID id, @NonNull ViewVersionCreate request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        ViewVersion draft = ViewVersion.builder()
                .viewId(id)
                .author(ViewAuthor.orDefault(request.author()))
                .summary(request.summary())
                .type(request.type())
                .engineVersion(request.engineVersion())
                .inputs(request.inputs())
                .libraries(request.libraries() == null ? Set.of() : request.libraries())
                .html(request.html())
                .createdBy(userName)
                .build();

        ViewVersion saved = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ViewDAO.class);
            lockView(dao, id, workspaceId);
            dao.bumpLastVersion(id, workspaceId);
            var version = draft.toBuilder().version(dao.findLastVersion(id, workspaceId)).build();
            dao.saveVersion(version, workspaceId);
            dao.setCurrentVersion(id, workspaceId, version.version(), userName);
            return dao.findVersion(id, version.version(), workspaceId).orElseThrow();
        });
        log.info("Created view version, id '{}', version '{}', author '{}', engineVersion '{}', workspaceId '{}'", id,
                saved.version(), saved.author(), saved.engineVersion(), workspaceId);
        return saved;
    }

    @Override
    public View get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return template.inTransaction(READ_ONLY, handle -> loadView(handle.attach(ViewDAO.class), id, workspaceId));
    }

    @Override
    public ViewVersionPage getVersions(@NonNull UUID id, int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(ViewDAO.class);
            findView(dao, id, workspaceId);
            var content = dao.findVersions(id, workspaceId, size, (page - 1L) * size);
            return ViewVersionPage.builder()
                    .content(content)
                    .page(page)
                    .size(content.size())
                    .total(dao.countVersions(id, workspaceId))
                    .build();
        });
    }

    @Override
    public ViewVersion getVersion(@NonNull UUID id, int version) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(ViewDAO.class);
            findView(dao, id, workspaceId);
            return dao.findVersion(id, version, workspaceId)
                    .orElseThrow(() -> new NotFoundException(VIEW_VERSION_NOT_FOUND));
        });
    }

    @Override
    public View setCurrentVersion(@NonNull UUID id, int version) {
        String workspaceId = requestContext.get().getWorkspaceId();
        View view = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ViewDAO.class);
            lockView(dao, id, workspaceId);
            dao.findVersion(id, version, workspaceId)
                    .orElseThrow(() -> new NotFoundException(VIEW_VERSION_NOT_FOUND));
            dao.setCurrentVersion(id, workspaceId, version, requestContext.get().getUserName());
            return loadView(dao, id, workspaceId);
        });
        log.info("Selected view version, id '{}', version '{}', workspaceId '{}'", id, version, workspaceId);
        return view;
    }

    @Override
    public void deleteVersion(@NonNull UUID id, int version) {
        String workspaceId = requestContext.get().getWorkspaceId();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ViewDAO.class);
            int current = lockView(dao, id, workspaceId);
            if (version == current) {
                throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorMessage(List.of(
                                "Version %d is the current version; select another version before deleting it"
                                        .formatted(version))))
                        .build());
            }
            if (dao.deleteVersion(id, version, workspaceId) == 0) {
                throw new NotFoundException(VIEW_VERSION_NOT_FOUND);
            }
            dao.updateLastUpdatedAt(id, workspaceId, requestContext.get().getUserName());
            return null;
        });
        log.info("Deleted view version, id '{}', version '{}', workspaceId '{}'", id, version, workspaceId);
    }

    @Override
    public View update(@NonNull UUID id, @NonNull ViewUpdate update) {
        if (update.name() == null && update.description() == null) {
            return get(id);
        }
        String workspaceId = requestContext.get().getWorkspaceId();
        View view = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ViewDAO.class);
            if (dao.update(id, workspaceId, update, requestContext.get().getUserName()) == 0) {
                throw new NotFoundException(VIEW_NOT_FOUND);
            }
            return loadView(dao, id, workspaceId);
        });
        log.info("Updated view, id '{}', workspaceId '{}'", id, workspaceId);
        return view;
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ViewDAO.class);
            lockView(dao, id, workspaceId);
            dao.deleteVersions(id, workspaceId);
            return dao.softDelete(id, workspaceId, requestContext.get().getUserName());
        });
        log.info("Deleted view, id '{}', workspaceId '{}'", id, workspaceId);
    }

    private static View loadView(ViewDAO dao, UUID id, String workspaceId) {
        View view = findView(dao, id, workspaceId);
        return view.toBuilder()
                .version(dao.findVersion(id, view.currentVersion(), workspaceId).orElseThrow())
                .build();
    }

    private static View findView(ViewDAO dao, UUID id, String workspaceId) {
        return dao.findById(id, workspaceId).orElseThrow(() -> new NotFoundException(VIEW_NOT_FOUND));
    }

    private static int lockView(ViewDAO dao, UUID id, String workspaceId) {
        return dao.lockById(id, workspaceId).orElseThrow(() -> new NotFoundException(VIEW_NOT_FOUND));
    }
}
