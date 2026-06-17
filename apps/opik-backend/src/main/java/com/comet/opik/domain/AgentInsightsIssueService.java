package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueDetail;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueUpdate;
import com.comet.opik.api.AgentInsightsIssueWithDetails;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AgentInsightsIssueServiceImpl.class)
public interface AgentInsightsIssueService {

    void reportIssues(AgentInsightsReport report);

    AgentInsightsIssue.AgentInsightsIssuePage findIssues(UUID projectId, LocalDate fromDate, LocalDate toDate,
            AgentInsightsIssueStatus status, List<SortingField> sortingFields, int page, int size);

    AgentInsightsIssueWithDetails getIssue(UUID issueId, UUID projectId, LocalDate fromDate, LocalDate toDate);

    LocalDate DEFAULT_FROM_DATE = LocalDate.of(1970, 1, 1);

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
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

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

        List<UUID> issueIds = report.issues().stream()
                .map(issue -> issue.id() != null ? issue.id() : idGenerator.generateId())
                .toList();
        List<UUID> detailIds = report.issues().stream()
                .map(issue -> idGenerator.generateId())
                .toList();
        List<String> metadata = report.issues().stream()
                .map(issue -> serializeMetadata(issue.metadata()))
                .toList();

        transactionTemplate.inTransaction(WRITE, handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            dao.upsertIssues(workspaceId, report.projectId(), userName, issueIds, report.issues());
            dao.upsertDetails(workspaceId, report.projectId(), report.reportDay(), userName,
                    detailIds, issueIds, report.issues(), metadata);

            return null;
        });
    }

    @Override
    public AgentInsightsIssue.AgentInsightsIssuePage findIssues(@NonNull UUID projectId, LocalDate fromDate,
            LocalDate toDate, AgentInsightsIssueStatus status, List<SortingField> sortingFields, int page,
            int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        DateWindow window = resolveWindow(fromDate, toDate);
        String sortFields = sortingQueryBuilder.toOrderBySql(sortingFields);

        log.info("Retrieving agent insights issues for project '{}' in workspace '{}', window '{}'..'{}', page {}",
                projectId, workspaceId, window.from(), window.to(), page);

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            int offset = (page - 1) * size;
            List<AgentInsightsIssue> issues = dao.findIssues(workspaceId, projectId, window.from(), window.to(),
                    status, sortFields, size, offset);
            long total = dao.countIssues(workspaceId, projectId, window.from(), window.to(), status);

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

        DateWindow window = resolveWindow(fromDate, toDate);

        log.info("Retrieving agent insights issue '{}' for project '{}' in workspace '{}', window '{}'..'{}'",
                issueId, projectId, workspaceId, window.from(), window.to());

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AgentInsightsIssueDAO dao = handle.attach(AgentInsightsIssueDAO.class);

            AgentInsightsIssueWithDetails issue = dao.findIssueById(workspaceId, projectId, issueId);
            if (issue == null) {
                throw new NotFoundException("Agent insights issue '%s' not found".formatted(issueId));
            }

            List<AgentInsightsIssueDetail> details = dao.findDetails(workspaceId, projectId, issueId, window.from(),
                    window.to());

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

    private DateWindow resolveWindow(LocalDate fromDate, LocalDate toDate) {
        LocalDate effectiveFrom = Objects.requireNonNullElse(fromDate, DEFAULT_FROM_DATE);
        LocalDate effectiveTo = Objects.requireNonNullElse(toDate, LocalDate.now(ZoneOffset.UTC));
        return new DateWindow(effectiveFrom, effectiveTo);
    }

    private record DateWindow(LocalDate from, LocalDate to) {
    }
}
