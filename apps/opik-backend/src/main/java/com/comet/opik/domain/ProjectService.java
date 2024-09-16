package com.comet.opik.domain;

import com.comet.opik.api.Page;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectCriteria;
import com.comet.opik.api.ProjectUpdate;
import com.comet.opik.api.error.CannotDeleteProjectException;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplate.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplate.WRITE;

@ImplementedBy(ProjectServiceImpl.class)
public interface ProjectService {

    String DEFAULT_PROJECT = "Default Project";
    String DEFAULT_WORKSPACE_NAME = "default";
    String DEFAULT_WORKSPACE_ID = "0190babc-62a0-71d2-832a-0feffa4676eb";
    String DEFAULT_USER = "admin";

    Project create(Project project);

    Project update(UUID id, ProjectUpdate project);

    Project get(UUID id);

    Project get(UUID id, String workspaceId);

    void delete(UUID id);

    Page<Project> find(int page, int size, ProjectCriteria criteria);

    List<Project> findByNames(String workspaceId, List<String> names);

    Project getOrCreate(String workspaceId, String projectName, String userName);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectServiceImpl implements ProjectService {

    private static final String PROJECT_ALREADY_EXISTS = "Project already exists";
    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;

    private NotFoundException createNotFoundError() {
        String message = "Project not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    public Project create(@NonNull Project project) {
        UUID projectId = idGenerator.generateId();
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        return createProject(project, projectId, userName, workspaceId);
    }

    private Project createProject(Project project, UUID projectId, String userName, String workspaceId) {
        IdGenerator.validateVersion(projectId, "project");

        var newProject = project.toBuilder()
                .id(projectId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        try {
            return template.inTransaction(WRITE, handle -> {

                var repository = handle.attach(ProjectDAO.class);

                repository.save(workspaceId, newProject);

                return repository.findById(projectId, workspaceId);
            });
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw newConflict();
            } else {
                throw e;
            }
        }
    }

    private EntityAlreadyExistsException newConflict() {
        log.info(PROJECT_ALREADY_EXISTS);
        return new EntityAlreadyExistsException(new ErrorMessage(List.of(PROJECT_ALREADY_EXISTS)));
    }

    @Override
    public Project update(@NonNull UUID id, @NonNull ProjectUpdate projectUpdate) {
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        try {
            return template.inTransaction(WRITE, handle -> {

                var repository = handle.attach(ProjectDAO.class);

                Project project = repository.fetch(id, workspaceId)
                        .orElseThrow(this::createNotFoundError);

                repository.update(project.id(),
                        workspaceId,
                        projectUpdate.name(),
                        projectUpdate.description(),
                        userName);

                return repository.findById(id, workspaceId);
            });

        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw newConflict();
            } else {
                throw e;
            }
        }
    }

    @Override
    public Project get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return get(id, workspaceId);
    }

    @Override
    public Project get(@NonNull UUID id, @NonNull String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.fetch(id, workspaceId).orElseThrow(this::createNotFoundError);
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {

            var repository = handle.attach(ProjectDAO.class);
            Optional<Project> project = repository.fetch(id, workspaceId);

            if (project.isEmpty()) {
                // Void return
                return null;
            }

            if (project.get().name().equalsIgnoreCase(DEFAULT_PROJECT)) {
                var message = "Cannot delete default project";
                log.info(message);
                throw new CannotDeleteProjectException(new ErrorMessage(List.of(message)));
            }

            repository.delete(id, workspaceId);

            // Void return
            return null;
        });
    }

    @Override
    public Page<Project> find(int page, int size, @NonNull ProjectCriteria criteria) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {

            ProjectDAO repository = handle.attach(ProjectDAO.class);

            int offset = (page - 1) * size;

            List<Project> projects = repository.find(size, offset, workspaceId, criteria.projectName());

            return new Project.ProjectPage(page, projects.size(),
                    repository.findCount(workspaceId, criteria.projectName()), projects);
        });
    }

    @Override
    public List<Project> findByNames(String workspaceId, List<String> names) {

        if (names.isEmpty()) {
            return List.of();
        }

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.findByNames(workspaceId, names);
        });
    }

    @Override
    public Project getOrCreate(@NonNull String workspaceId, @NonNull String projectName, @NonNull String userName) {

        return findByNames(workspaceId, List.of(projectName))
                .stream()
                .findFirst()
                .orElseGet(() -> {
                    log.info("Creating project with name '{}' on workspaceId '{}'", projectName, workspaceId);
                    var project = Project.builder()
                            .name(projectName)
                            .build();

                    UUID projectId = idGenerator.generateId();

                    project = createProject(project, projectId, userName, workspaceId);

                    log.info("Created project with id '{}', name '{}' on workspaceId '{}'", projectId, projectName,
                            workspaceId);
                    return project;
                });
    }

}
