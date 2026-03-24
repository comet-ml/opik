package com.comet.opik.domain.retention;

import com.comet.opik.api.retention.RetentionLevel;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.api.retention.RetentionRule.RetentionRulePage;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(RetentionRuleServiceImpl.class)
public interface RetentionRuleService {

    RetentionRule create(@NonNull RetentionRule rule);

    RetentionRule findById(@NonNull UUID id);

    RetentionRulePage find(int page, int size, boolean includeInactive);

    void deactivate(@NonNull UUID id);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class RetentionRuleServiceImpl implements RetentionRuleService {

    private static final String RULE_NOT_FOUND = "Retention rule not found";

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public RetentionRule create(@NonNull RetentionRule rule) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        UUID id = rule.id() != null ? rule.id() : idGenerator.generateId();
        IdGenerator.validateVersion(id, "retention_rule");

        RetentionLevel level = inferLevel(rule);

        var newRule = rule.toBuilder()
                .id(id)
                .workspaceId(workspaceId)
                .level(level)
                .applyToPast(Optional.ofNullable(rule.applyToPast()).orElse(true))
                .enabled(true)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);

            // Auto-deactivate any existing active rule for the same scope and level
            dao.deactivateByScope(workspaceId,
                    newRule.projectId(),
                    level,
                    userName);

            dao.save(workspaceId, newRule);
            log.info("Created retention rule '{}' (level={}) for project '{}' in workspace '{}'",
                    id, level, newRule.projectId(), workspaceId);

            return dao.findById(id, workspaceId).orElseThrow();
        });
    }

    @Override
    public RetentionRule findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding retention rule '{}' in workspace '{}'", id, workspaceId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);
            return dao.findById(id, workspaceId)
                    .orElseThrow(() -> {
                        log.warn("Retention rule '{}' not found in workspace '{}'", id, workspaceId);
                        return new NotFoundException(RULE_NOT_FOUND);
                    });
        });
    }

    @Override
    public RetentionRulePage find(int page, int size, boolean includeInactive) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding retention rules in workspace '{}', page '{}', size '{}', includeInactive '{}'",
                workspaceId, page, size, includeInactive);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);

            int offset = (page - 1) * size;
            long total = dao.count(workspaceId, includeInactive);
            List<RetentionRule> content = dao.find(workspaceId, includeInactive, size, offset);

            log.info("Found '{}' retention rules in workspace '{}'", total, workspaceId);
            return RetentionRulePage.builder()
                    .content(content)
                    .page(page)
                    .size(content.size())
                    .total(total)
                    .build();
        });
    }

    @Override
    public void deactivate(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Deactivating retention rule '{}' in workspace '{}'", id, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);

            int result = dao.deactivate(id, workspaceId, userName);

            if (result == 0) {
                // Could be not found or already deactivated — check existence
                dao.findById(id, workspaceId)
                        .orElseThrow(() -> {
                            log.warn("Retention rule '{}' not found in workspace '{}'", id, workspaceId);
                            return new NotFoundException(RULE_NOT_FOUND);
                        });
                log.info("Retention rule '{}' was already deactivated in workspace '{}'", id, workspaceId);
            } else {
                log.info("Deactivated retention rule '{}' in workspace '{}'", id, workspaceId);
            }

            return null;
        });
    }

    /**
     * Infer the retention level from request properties:
     * - organization_level=true (with null project_id) → ORGANIZATION
     * - project_id set (with organization_level absent or false) → PROJECT
     * - otherwise → WORKSPACE
     */
    private RetentionLevel inferLevel(RetentionRule rule) {
        boolean orgLevel = Boolean.TRUE.equals(rule.organizationLevel());

        if (orgLevel && rule.projectId() != null) {
            throw new BadRequestException("Cannot set organization_level=true with a project_id");
        }

        if (orgLevel) {
            return RetentionLevel.ORGANIZATION;
        }

        if (rule.projectId() != null) {
            return RetentionLevel.PROJECT;
        }

        return RetentionLevel.WORKSPACE;
    }
}
