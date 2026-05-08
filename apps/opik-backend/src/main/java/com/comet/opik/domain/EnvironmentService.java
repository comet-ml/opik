package com.comet.opik.domain;

import com.comet.opik.api.Environment;
import com.comet.opik.api.Environment.EnvironmentPage;
import com.comet.opik.api.EnvironmentUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.EnvironmentConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.lock.LockService;
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
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(EnvironmentServiceImpl.class)
public interface EnvironmentService {

    Environment create(Environment environment);

    void bulkCreate(Set<String> names, String workspaceId, String userName);

    Environment update(UUID id, EnvironmentUpdate environmentUpdate);

    Environment get(UUID id);

    EnvironmentPage find();

    void delete(Set<UUID> ids);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class EnvironmentServiceImpl implements EnvironmentService {

    private static final String ENVIRONMENT_NAME_CONFLICT_TEMPLATE = "Environment with name '%s' already exists in the workspace";
    private static final String ENVIRONMENT_LIMIT_REACHED_TEMPLATE = "Workspace already has the maximum of %d environments";
    private static final String ENVIRONMENT_CREATE_LOCK = "environment_create";
    private static final String ENVIRONMENT_CREATED_EVENT = "environment_created";
    private static final String SOURCE_MANUAL = "manual";
    private static final String SOURCE_INGESTION = "ingestion";
    private static final Pattern NAME_PATTERN = Pattern.compile(Environment.NAME_PATTERN);

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull AnalyticsService analyticsService;
    private final @NonNull LockService lockService;
    private final @NonNull @Config("environment") EnvironmentConfig environmentConfig;

    @Override
    public Environment create(@NonNull Environment environment) {
        UUID id = Objects.requireNonNullElseGet(environment.id(), idGenerator::generateId);
        IdGenerator.validateVersion(id, "environment");

        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        var newEnvironment = environment.toBuilder()
                .id(id)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        Environment saved = lockService.executeWithLock(
                new LockService.Lock(workspaceId, ENVIRONMENT_CREATE_LOCK),
                Mono.fromCallable(() -> {
                    try {
                        return template.inTransaction(WRITE, handle -> {
                            var repository = handle.attach(EnvironmentDAO.class);

                            long count = repository.countByWorkspace(workspaceId);
                            if (count >= environmentConfig.getMaxPerWorkspace()) {
                                throw newLimitReached();
                            }

                            repository.save(workspaceId, newEnvironment);
                            return newEnvironment;
                        });
                    } catch (UnableToExecuteStatementException e) {
                        if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                            throw newNameConflict(newEnvironment.name());
                        }
                        throw e;
                    }
                }).subscribeOn(Schedulers.boundedElastic()))
                .block();

        trackEnvironmentCreatedEvent(saved, workspaceId, userName, SOURCE_MANUAL, Instant.now());

        return get(saved.id(), workspaceId);
    }

    @Override
    public void bulkCreate(@NonNull Set<String> names, @NonNull String workspaceId, @NonNull String userName) {
        List<String> validNames = names.stream()
                .filter(StringUtils::isNotBlank)
                .filter(name -> name.length() <= 150)
                .filter(name -> NAME_PATTERN.matcher(name).matches())
                .toList();

        if (validNames.isEmpty()) {
            return;
        }

        List<Environment> created = lockService.executeWithLock(
                new LockService.Lock(workspaceId, ENVIRONMENT_CREATE_LOCK),
                Mono.fromCallable(() -> template.inTransaction(WRITE, handle -> {
                    var repository = handle.attach(EnvironmentDAO.class);

                    Set<String> existingLowercaseNames = repository.findAllNames(workspaceId).stream()
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .collect(Collectors.toSet());

                    long availableSlots = (long) environmentConfig.getMaxPerWorkspace() - existingLowercaseNames.size();
                    if (availableSlots <= 0) {
                        log.info("Skipping environment auto-create for workspace_id '{}': cap '{}' reached",
                                workspaceId, environmentConfig.getMaxPerWorkspace());
                        return List.<Environment>of();
                    }

                    List<Environment> environments = validNames.stream()
                            .filter(name -> !existingLowercaseNames.contains(name.toLowerCase(Locale.ROOT)))
                            .limit(availableSlots)
                            .map(name -> Environment.builder()
                                    .id(idGenerator.generateId())
                                    .name(name)
                                    .build())
                            .toList();

                    if (environments.isEmpty()) {
                        return List.<Environment>of();
                    }

                    repository.saveBatch(workspaceId, userName, environments);
                    return environments;
                })).subscribeOn(Schedulers.boundedElastic()))
                .block();

        if (created == null || created.isEmpty()) {
            return;
        }

        log.info("Auto-created '{}' environments for workspace_id '{}'", created.size(), workspaceId);

        Instant now = Instant.now();
        created.forEach(env -> trackEnvironmentCreatedEvent(env, workspaceId, userName, SOURCE_INGESTION, now));
    }

    private void trackEnvironmentCreatedEvent(Environment environment, String workspaceId, String userName,
            String source, Instant date) {
        analyticsService.trackEvent(ENVIRONMENT_CREATED_EVENT, Map.of(
                "environment_id", environment.id().toString(),
                "environment_name", environment.name(),
                "workspace_id", workspaceId,
                "user_name", userName,
                "source", source,
                "date", date.toString()));
    }

    @Override
    public Environment update(@NonNull UUID id, @NonNull EnvironmentUpdate update) {
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        try {
            template.inTransaction(WRITE, handle -> {
                var repository = handle.attach(EnvironmentDAO.class);

                Environment existing = repository.fetch(id, workspaceId)
                        .orElseThrow(this::createNotFoundError);

                repository.update(existing.id(),
                        workspaceId,
                        update.name(),
                        update.description(),
                        update.color(),
                        update.position(),
                        userName);

                return null;
            });

            analyticsService.trackEvent("environment_updated", Map.of(
                    "environment_id", id.toString(),
                    "workspace_id", workspaceId,
                    "user_name", userName,
                    "date", Instant.now().toString()));

            return get(id, workspaceId);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw newNameConflict(update.name());
            }
            throw e;
        }
    }

    @Override
    public Environment get(@NonNull UUID id) {
        return get(id, requestContext.get().getWorkspaceId());
    }

    private Environment get(UUID id, String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(EnvironmentDAO.class);
            return repository.fetch(id, workspaceId).orElseThrow(this::createNotFoundError);
        });
    }

    @Override
    public EnvironmentPage find() {
        String workspaceId = requestContext.get().getWorkspaceId();

        List<Environment> environments = template.inTransaction(READ_ONLY,
                handle -> handle.attach(EnvironmentDAO.class).findAll(workspaceId));

        if (environments.isEmpty()) {
            return EnvironmentPage.empty();
        }

        return new EnvironmentPage(1, environments.size(), environments.size(), environments,
                List.of("created_at"));
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        if (ids.isEmpty()) {
            log.info("ids list is empty for environments delete, on workspace_id '{}'", workspaceId);
            return;
        }

        template.inTransaction(WRITE, handle -> {
            handle.attach(EnvironmentDAO.class).delete(ids, workspaceId);
            return null;
        });

        analyticsService.trackEvent("environment_deleted", Map.of(
                "environment_ids", ids.stream().map(UUID::toString).toList().toString(),
                "workspace_id", workspaceId,
                "user_name", userName,
                "date", Instant.now().toString()));
    }

    private NotFoundException createNotFoundError() {
        String message = "Environment not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    private EntityAlreadyExistsException newNameConflict(String name) {
        String message = ENVIRONMENT_NAME_CONFLICT_TEMPLATE.formatted(name);
        log.info(message);
        return new EntityAlreadyExistsException(new ErrorMessage(List.of(message)));
    }

    private ClientErrorException newLimitReached() {
        String message = ENVIRONMENT_LIMIT_REACHED_TEMPLATE.formatted(environmentConfig.getMaxPerWorkspace());
        log.info(message);
        return new ClientErrorException(
                Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorMessage(List.of(message)))
                        .build());
    }
}
