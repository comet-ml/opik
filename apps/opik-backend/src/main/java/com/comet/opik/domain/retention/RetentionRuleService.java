package com.comet.opik.domain.retention;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.retention.RetentionLevel;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.api.retention.RetentionRule.RetentionRulePage;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class RetentionRuleServiceImpl implements RetentionRuleService {

    private static final String RULE_NOT_FOUND = "Retention rule not found";

    /** ClickHouse error code for TOO_MANY_ROWS (max_rows_to_read exceeded). */
    private static final int CH_TOO_MANY_ROWS = 158;

    private final TransactionTemplate template;
    private final Provider<RequestContext> requestContext;
    private final IdGenerator idGenerator;
    private final SpanDAO spanDAO;
    private final InstantToUUIDMapper uuidMapper;
    private final RetentionConfig config;

    @Inject
    RetentionRuleServiceImpl(
            @NonNull TransactionTemplate template,
            @NonNull Provider<RequestContext> requestContext,
            @NonNull IdGenerator idGenerator,
            @NonNull SpanDAO spanDAO,
            @NonNull InstantToUUIDMapper uuidMapper,
            @NonNull @Config("retention") RetentionConfig config) {
        this.template = template;
        this.requestContext = requestContext;
        this.idGenerator = idGenerator;
        this.spanDAO = spanDAO;
        this.uuidMapper = uuidMapper;
        this.config = config;
    }

    @Override
    public RetentionRule create(@NonNull RetentionRule rule) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        UUID id = rule.id() != null ? rule.id() : idGenerator.generateId();
        IdGenerator.validateVersion(id, "retention_rule");

        RetentionLevel level = inferLevel(rule);
        boolean applyToPast = Optional.ofNullable(rule.applyToPast()).orElse(true);

        // Estimate velocity and set up catch-up if applyToPast
        final Long catchUpVelocity;
        final UUID catchUpCursor;
        final boolean catchUpDone;

        if (applyToPast && config.getCatchUp().isEnabled() && rule.retention() != RetentionPeriod.UNLIMITED) {
            var estimation = estimateVelocity(workspaceId, rule.retention(), Instant.now());
            catchUpVelocity = estimation.velocity();
            catchUpCursor = estimation.startCursor();
            catchUpDone = false;
        } else {
            catchUpVelocity = null;
            catchUpCursor = null;
            catchUpDone = true;
        }

        var newRule = rule.toBuilder()
                .id(id)
                .workspaceId(workspaceId)
                .level(level)
                .applyToPast(applyToPast)
                .enabled(true)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .catchUpVelocity(catchUpVelocity)
                .catchUpCursor(catchUpCursor)
                .catchUpDone(catchUpDone)
                .build();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(RetentionRuleDAO.class);

            // Auto-deactivate any existing active rule for the same scope and level
            dao.deactivateByScope(workspaceId,
                    newRule.projectId(),
                    level,
                    userName);

            dao.save(workspaceId, newRule);
            log.info("Created retention rule '{}' (level='{}') for project '{}' in workspace '{}', velocity='{}'",
                    id, level, newRule.projectId(), workspaceId, catchUpVelocity);

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

    private record VelocityEstimation(long velocity, UUID startCursor) {
    }

    /**
     * Estimate the span velocity for a workspace and compute the catch-up start cursor.
     * If the estimation query fails with TOO_MANY_ROWS, uses a configurable default velocity.
     */
    private VelocityEstimation estimateVelocity(String workspaceId, RetentionPeriod period, Instant now) {
        var catchUpConfig = config.getCatchUp();

        // Compute the cutoff for this retention period (start-of-day UTC)
        Instant cutoff = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .minusDays(period.getDays())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        UUID cutoffId = uuidMapper.toLowerBound(cutoff);

        // Start cursor at service launch date
        UUID startCursor = uuidMapper.toLowerBound(
                catchUpConfig.getServiceStartDate().atStartOfDay(ZoneOffset.UTC).toInstant());

        try {
            Long velocity = spanDAO.estimateVelocityForRetention(workspaceId, cutoffId).block();
            long vel = velocity != null ? velocity : 0L;
            log.info("Retention velocity estimated for workspace '{}': '{}' spans/week", workspaceId, vel);
            return new VelocityEstimation(vel, startCursor);
        } catch (Exception e) {
            if (isTooManyRowsException(e)) {
                log.info("Retention velocity estimation hit row limit for workspace '{}', using default '{}'",
                        workspaceId, catchUpConfig.getDefaultVelocity());
                return new VelocityEstimation(catchUpConfig.getDefaultVelocity(), startCursor);
            }
            throw e;
        }
    }

    /**
     * Check if the exception chain contains a ClickHouse TOO_MANY_ROWS error (code 158).
     */
    private static boolean isTooManyRowsException(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("Code: " + CH_TOO_MANY_ROWS)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
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
