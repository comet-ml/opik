package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendRecommendationsResponse;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.api.spend.SpendUserRow;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ImplementedBy(AiSpendServiceImpl.class)
public interface AiSpendService {

    List<String> USER_SORTABLE_FIELDS = List.of("total_estimated_cost", "requests", "skills", "mcps", "mcp_calls");

    Mono<WorkspaceMetricsSummaryResponse> getSummary(SpendMetricRequest request);

    Mono<SpendCompositionResponse> getComposition(SpendMetricRequest request);

    Mono<SpendBreakdownResponse> getBreakdown(SpendMetricRequest request, String laneKey);

    Mono<SpendUserPage> getUsers(SpendMetricRequest request, int page, int size);

    Mono<SpendRecommendationsResponse> getRecommendations(SpendMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendServiceImpl implements AiSpendService {

    private static final String DOCS_URL = "https://www.comet.com/docs/opik/";
    private static final double HIGH_SPEND_FACTOR = 2.0;

    private final @NonNull AiSpendDAO aiSpendDAO;
    private final @NonNull ProjectService projectService;

    @Override
    public Mono<WorkspaceMetricsSummaryResponse> getSummary(@NonNull SpendMetricRequest request) {
        return resolveProject(request)
                .flatMap(aiSpendDAO::getSummary)
                .map(results -> WorkspaceMetricsSummaryResponse.builder().results(results).build());
    }

    @Override
    public Mono<SpendCompositionResponse> getComposition(@NonNull SpendMetricRequest request) {
        return resolveProject(request).flatMap(aiSpendDAO::getComposition);
    }

    @Override
    public Mono<SpendBreakdownResponse> getBreakdown(@NonNull SpendMetricRequest request, @NonNull String laneKey) {
        SpendLane lane = SpendLane.fromKey(laneKey)
                .filter(SpendLane::hasBreakdown)
                .orElseThrow(() -> new BadRequestException("No breakdown available for lane: %s".formatted(laneKey)));
        return resolveProject(request).flatMap(resolved -> aiSpendDAO.getBreakdown(resolved, lane));
    }

    @Override
    public Mono<SpendUserPage> getUsers(@NonNull SpendMetricRequest request, int page, int size) {
        return resolveProject(request)
                .flatMap(aiSpendDAO::getUsers)
                .map(users -> buildUserPage(users, page, size));
    }

    @Override
    public Mono<SpendRecommendationsResponse> getRecommendations(@NonNull SpendMetricRequest request) {
        return resolveProject(request)
                .flatMap(aiSpendDAO::getComposition)
                .map(this::buildRecommendations);
    }

    private Mono<SpendMetricRequest> resolveProject(SpendMetricRequest request) {
        return projectService.resolveProjectIdAndVerifyVisibility(request.projectId(), request.projectName())
                .map(projectId -> request.toBuilder().resolvedProjectId(projectId).build());
    }

    private SpendUserPage buildUserPage(List<SpendUserRow> users, int page, int size) {
        double avgSpend = users.stream()
                .map(SpendUserRow::totalEstimatedCost)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0);

        List<SpendUserRow> flagged = users.stream()
                .map(user -> user.toBuilder().flags(computeFlags(user, avgSpend)).build())
                .sorted(Comparator.comparing(
                        (SpendUserRow user) -> Optional.ofNullable(user.totalEstimatedCost()).orElse(BigDecimal.ZERO))
                        .reversed())
                .toList();

        int total = flagged.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<SpendUserRow> content = flagged.subList(fromIndex, toIndex);

        return SpendUserPage.builder()
                .page(page)
                .size(content.size())
                .total(total)
                .content(content)
                .sortableBy(USER_SORTABLE_FIELDS)
                .build();
    }

    private List<String> computeFlags(SpendUserRow user, double avgSpend) {
        double spend = Optional.ofNullable(user.totalEstimatedCost()).orElse(BigDecimal.ZERO).doubleValue();
        if (avgSpend > 0 && spend >= avgSpend * HIGH_SPEND_FACTOR) {
            return List.of("high_spend");
        }
        return List.of();
    }

    private SpendRecommendationsResponse buildRecommendations(SpendCompositionResponse composition) {
        long inputTotal = Optional.ofNullable(composition.input())
                .map(SpendCompositionResponse.Side::totalTokens).orElse(0L);
        long outputTotal = Optional.ofNullable(composition.output())
                .map(SpendCompositionResponse.Side::totalTokens).orElse(0L);
        BigDecimal totalCost = composition.harness().stream()
                .map(SpendCompositionResponse.HarnessEntry::totalEstimatedCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long priorAssistant = laneTokens(composition.input(), "prior_assistant");
        long thinking = laneTokens(composition.output(), "thinking");
        long mcpServers = laneTokens(composition.input(), "mcp_servers");

        List<SpendRecommendationsResponse.Item> items = new ArrayList<>();

        if (inputTotal > 0 && ratio(priorAssistant, inputTotal) > 0.4) {
            items.add(SpendRecommendationsResponse.Item.builder()
                    .id("compact_threshold")
                    .title("Tighten /compact threshold on long sessions")
                    .body("Prior assistant context is the largest input lane. Compacting earlier re-sends less history per turn.")
                    .impact("high")
                    .estSaving(estimate(totalCost, priorAssistant, inputTotal + outputTotal, 0.25))
                    .docsUrl(DOCS_URL)
                    .relatedLaneKey("prior_assistant")
                    .build());
        }

        if (outputTotal > 0 && ratio(thinking, outputTotal) > 0.5) {
            items.add(SpendRecommendationsResponse.Item.builder()
                    .id("thinking_effort")
                    .title("Lower thinking effort on routine sessions")
                    .body("Thinking dominates output tokens. Reducing effort on routine work cuts output cost with little quality impact.")
                    .impact("medium")
                    .estSaving(estimate(totalCost, thinking, inputTotal + outputTotal, 0.3))
                    .docsUrl(DOCS_URL)
                    .relatedLaneKey("thinking")
                    .build());
        }

        if (mcpServers > 0) {
            items.add(SpendRecommendationsResponse.Item.builder()
                    .id("fewer_mcps")
                    .title("Disable unused MCP servers")
                    .body("MCP servers add schema overhead on every session whether used or not. Enabling only active servers reduces prompt cost.")
                    .impact("low")
                    .estSaving(estimate(totalCost, mcpServers, inputTotal + outputTotal, 0.5))
                    .docsUrl(DOCS_URL)
                    .relatedLaneKey("mcp_servers")
                    .build());
        }

        BigDecimal totalSavings = items.stream()
                .map(SpendRecommendationsResponse.Item::estSaving)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return SpendRecommendationsResponse.builder()
                .totalSavings(totalSavings)
                .items(items)
                .build();
    }

    private long laneTokens(SpendCompositionResponse.Side side, String key) {
        if (side == null || side.lanes() == null) {
            return 0L;
        }
        return side.lanes().stream()
                .filter(lane -> key.equals(lane.key()))
                .map(SpendCompositionResponse.Lane::totalTokens)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(0L);
    }

    private double ratio(long part, long total) {
        return total == 0 ? 0.0 : (double) part / total;
    }

    private BigDecimal estimate(BigDecimal totalCost, long laneTokens, long grandTotalTokens, double factor) {
        if (totalCost == null || grandTotalTokens == 0) {
            return BigDecimal.ZERO;
        }
        return totalCost
                .multiply(BigDecimal.valueOf((double) laneTokens / grandTotalTokens))
                .multiply(BigDecimal.valueOf(factor))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
