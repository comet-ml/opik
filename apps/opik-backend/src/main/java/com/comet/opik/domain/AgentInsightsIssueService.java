package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueDetail;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueUpdate;
import com.comet.opik.api.AgentInsightsIssueWithDetails;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.AgentInsightsSortBy;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AgentInsightsIssueServiceImpl.class)
public interface AgentInsightsIssueService {

    void reportIssues(AgentInsightsReport report);

    AgentInsightsIssue.AgentInsightsIssuePage findIssues(UUID projectId, LocalDate fromDate, LocalDate toDate,
            AgentInsightsIssueStatus status, AgentInsightsSortBy sortBy, int page, int size);

    AgentInsightsIssueWithDetails getIssue(UUID issueId, UUID projectId, LocalDate fromDate, LocalDate toDate);

    LocalDate DEFAULT_FROM_DATE = LocalDate.of(1970, 1, 1);
    LocalDate DEFAULT_TO_DATE = LocalDate.of(9999, 12, 31);

    void updateStatus(UUID issueId, AgentInsightsIssueUpdate update);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AgentInsightsIssueServiceImpl implements AgentInsightsIssueService {

    private static final int MAX_METADATA_BYTES = 65_535;

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ProjectService projectService;

    @Override
    public void reportIssues(@NonNull AgentInsightsReport report) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var explicitIds = report.issues().stream()
                .map(AgentInsightsReport.ReportedIssue::id)
                .filter(Objects::nonNull)
                .toList();
        if (explicitIds.size() != explicitIds.stream().distinct().count()) {
            throw new BadRequestException("Duplicate issue ids in request");
        }

        projectService.get(report.projectId(), workspaceId);

        log.info("Storing '{}' agent insights issues for project '{}' on report day '{}' in workspace '{}'",
                report.issues().size(), report.projectId(), report.reportDay(), workspaceId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            List<UUID> issueIds = report.issues().stream()
                    .map(issue -> issue.id() != null ? issue.id() : idGenerator.generateId())
                    .toList();
            dao.upsertIssues(workspaceId, report.projectId(), userName, issueIds, report.issues());

            List<UUID> detailIds = report.issues().stream()
                    .map(issue -> idGenerator.generateId())
                    .toList();
            List<String> metadata = report.issues().stream()
                    .map(issue -> serializeMetadata(issue.metadata()))
                    .toList();

            dao.upsertDetails(workspaceId, report.projectId(), report.reportDay(), userName,
                    detailIds, issueIds, report.issues(), metadata);

            return null;
        });
    }

    @Override
    public AgentInsightsIssue.AgentInsightsIssuePage findIssues(@NonNull UUID projectId, LocalDate fromDate,
            LocalDate toDate, AgentInsightsIssueStatus status, AgentInsightsSortBy sortBy, int page,
            int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        LocalDate effectiveFrom = Objects.requireNonNullElse(fromDate, DEFAULT_FROM_DATE);
        LocalDate effectiveTo = Objects.requireNonNullElse(toDate, DEFAULT_TO_DATE);
        validateDateRange(effectiveFrom, effectiveTo);
        String orderBy = toOrderByClause(Objects.requireNonNullElse(sortBy, AgentInsightsSortBy.LAST_SEEN));

        log.info("Retrieving agent insights issues for project '{}' in workspace '{}', window '{}'..'{}', page {}",
                projectId, workspaceId, effectiveFrom, effectiveTo, page);

        return transactionTemplate.inTransaction(handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            int offset = (page - 1) * size;
            List<AgentInsightsIssue> issues = dao.findIssues(workspaceId, projectId, effectiveFrom, effectiveTo,
                    status, orderBy, size, offset);
            long total = dao.countIssues(workspaceId, projectId, effectiveFrom, effectiveTo, status);

            return AgentInsightsIssue.AgentInsightsIssuePage.builder()
                    .page(page)
                    .size(size)
                    .total(total)
                    .content(issues)
                    .build();
        });
    }

    @Override
    public AgentInsightsIssueWithDetails getIssue(@NonNull UUID issueId, @NonNull UUID projectId,
            LocalDate fromDate, LocalDate toDate) {
        String workspaceId = requestContext.get().getWorkspaceId();

        LocalDate effectiveFrom = Objects.requireNonNullElse(fromDate, DEFAULT_FROM_DATE);
        LocalDate effectiveTo = Objects.requireNonNullElse(toDate, DEFAULT_TO_DATE);
        validateDateRange(effectiveFrom, effectiveTo);

        log.info("Retrieving agent insights issue '{}' for project '{}' in workspace '{}', window '{}'..'{}'",
                issueId, projectId, workspaceId, effectiveFrom, effectiveTo);

        return transactionTemplate.inTransaction(handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            AgentInsightsIssueWithDetails issue = dao.findIssueById(workspaceId, projectId, issueId);
            if (issue == null) {
                throw new NotFoundException("Agent insights issue '%s' not found".formatted(issueId));
            }

            List<AgentInsightsIssueDetail> details = dao.findDetails(workspaceId, projectId, issueId, effectiveFrom,
                    effectiveTo);

            return issue.toBuilder()
                    .details(details)
                    .build();
        });
    }

    @Override
    public void updateStatus(@NonNull UUID issueId, @NonNull AgentInsightsIssueUpdate update) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Updating agent insights issue '{}' status to '{}' for project '{}' in workspace '{}'",
                issueId, update.status(), update.projectId(), workspaceId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            int updated = dao.updateStatus(workspaceId, update.projectId(), issueId, update.status(), userName);
            if (updated == 0) {
                throw new NotFoundException("Agent insights issue '%s' not found".formatted(issueId));
            }

            return null;
        });
    }

    // The metadata column is TEXT (65,535 bytes); reject oversized payloads at the boundary as 400 rather than
    // letting MySQL fail the INSERT with a data-truncation error surfaced as 500.
    private String serializeMetadata(JsonNode metadata) {
        if (metadata == null) {
            return null;
        }
        String serialized = JsonUtils.writeValueAsString(metadata);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new BadRequestException(
                    "Issue metadata exceeds the %d byte limit".formatted(MAX_METADATA_BYTES));
        }
        return serialized;
    }

    private void validateDateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate.isAfter(toDate)) {
            throw new BadRequestException("from_date '%s' must not be after to_date '%s'"
                    .formatted(fromDate, toDate));
        }
    }

    // Hardcoded fragments keyed by enum: user input never reaches the SQL text
    private String toOrderByClause(AgentInsightsSortBy sortBy) {
        return switch (sortBy) {
            case LAST_SEEN -> "last_seen DESC, total_occurrences DESC, i.id DESC";
            case TOTAL_OCCURRENCES -> "total_occurrences DESC, last_seen DESC, i.id DESC";
        };
    }
}
