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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AgentInsightsIssueServiceImpl.class)
public interface AgentInsightsIssueService {

    void reportIssues(AgentInsightsReport report);

    AgentInsightsIssue.AgentInsightsIssuePage findIssues(UUID projectId, LocalDate fromDate, LocalDate toDate,
            AgentInsightsIssueStatus status, AgentInsightsSortBy sortBy, int page, int size);

    AgentInsightsIssueWithDetails getIssue(UUID issueId, UUID projectId, LocalDate fromDate, LocalDate toDate);

    void updateStatus(UUID issueId, AgentInsightsIssueUpdate update);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AgentInsightsIssueServiceImpl implements AgentInsightsIssueService {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ProjectService projectService;

    @Override
    public void reportIssues(@NonNull AgentInsightsReport report) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        long distinctNames = report.issues().stream()
                .map(AgentInsightsReport.ReportedIssue::name)
                .distinct()
                .count();
        if (distinctNames != report.issues().size()) {
            throw new BadRequestException("Duplicate issue names in request");
        }

        projectService.get(report.projectId(), workspaceId);

        log.info("Storing '{}' agent insights issues for project '{}' on report day '{}' in workspace '{}'",
                report.issues().size(), report.projectId(), report.reportDay(), workspaceId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            List<UUID> candidateIds = report.issues().stream()
                    .map(issue -> idGenerator.generateId())
                    .toList();
            dao.upsertIssues(workspaceId, report.projectId(), userName, candidateIds, report.issues());

            List<String> names = report.issues().stream()
                    .map(AgentInsightsReport.ReportedIssue::name)
                    .toList();
            Map<String, UUID> idsByName = dao.findIdsByNames(workspaceId, report.projectId(), names).stream()
                    .collect(Collectors.toMap(AgentInsightsIssueDAO.IssueIdName::name,
                            AgentInsightsIssueDAO.IssueIdName::id));

            List<UUID> issueIds = names.stream().map(idsByName::get).toList();
            List<UUID> detailIds = report.issues().stream()
                    .map(issue -> idGenerator.generateId())
                    .toList();
            List<String> metadata = report.issues().stream()
                    .map(issue -> issue.metadata() == null ? null : JsonUtils.writeValueAsString(issue.metadata()))
                    .toList();

            dao.upsertDetails(workspaceId, report.projectId(), report.reportDay(), userName,
                    detailIds, issueIds, report.issues(), metadata);

            return null;
        });
    }

    @Override
    public AgentInsightsIssue.AgentInsightsIssuePage findIssues(@NonNull UUID projectId, @NonNull LocalDate fromDate,
            @NonNull LocalDate toDate, AgentInsightsIssueStatus status, AgentInsightsSortBy sortBy, int page,
            int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        validateDateRange(fromDate, toDate);
        String orderBy = toOrderByClause(Objects.requireNonNullElse(sortBy, AgentInsightsSortBy.LAST_SEEN));

        log.info("Retrieving agent insights issues for project '{}' in workspace '{}', window '{}'..'{}', page {}",
                projectId, workspaceId, fromDate, toDate, page);

        return transactionTemplate.inTransaction(handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            int offset = (page - 1) * size;
            List<AgentInsightsIssue> issues = dao.findIssues(workspaceId, projectId, fromDate, toDate, status,
                    orderBy, size, offset);
            long total = dao.countIssues(workspaceId, projectId, fromDate, toDate, status);

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
            @NonNull LocalDate fromDate, @NonNull LocalDate toDate) {
        String workspaceId = requestContext.get().getWorkspaceId();

        validateDateRange(fromDate, toDate);

        log.info("Retrieving agent insights issue '{}' for project '{}' in workspace '{}', window '{}'..'{}'",
                issueId, projectId, workspaceId, fromDate, toDate);

        return transactionTemplate.inTransaction(handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            AgentInsightsIssueWithDetails issue = dao.findIssueById(workspaceId, projectId, issueId);
            if (issue == null) {
                throw new NotFoundException("Agent insights issue '%s' not found".formatted(issueId));
            }

            List<AgentInsightsIssueDetail> details = dao.findDetails(workspaceId, projectId, issueId, fromDate,
                    toDate);

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
