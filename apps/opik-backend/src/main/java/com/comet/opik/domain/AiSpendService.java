package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.sorting.SpendUserSortingFactory;
import com.comet.opik.api.spend.Impact;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendRecommendationsResponse;
import com.comet.opik.api.spend.SpendUserPage;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@ImplementedBy(AiSpendServiceImpl.class)
public interface AiSpendService {

    Mono<WorkspaceMetricsSummaryResponse> getSummary(SpendMetricRequest request);

    Mono<SpendCompositionResponse> getComposition(SpendMetricRequest request);

    Mono<SpendBreakdownResponse> getBreakdown(SpendMetricRequest request, String laneKey);

    Mono<SpendUserPage> getUsers(SpendMetricRequest request, List<SortingField> sortingFields, int page, int size);

    Mono<SpendRecommendationsResponse> getRecommendations(SpendMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendServiceImpl implements AiSpendService {

    private static final String DOCS_URL = "https://www.comet.com/docs/opik/";

    private final @NonNull AiSpendDAO aiSpendDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull SpendUserSortingFactory spendUserSortingFactory;

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
        Optional<SpendLane> inputLane = SpendLane.fromKey(laneKey).filter(SpendLane::hasBreakdown);
        if (inputLane.isPresent()) {
            return resolveProject(request).flatMap(resolved -> aiSpendDAO.getBreakdown(resolved, inputLane.get()));
        }

        OutputLane outputLane = OutputLane.fromKey(laneKey)
                .filter(OutputLane::hasBreakdown)
                .orElseThrow(() -> new BadRequestException("No breakdown available for lane: %s".formatted(laneKey)));
        return resolveProject(request).flatMap(resolved -> aiSpendDAO.getOutputBreakdown(resolved, outputLane));
    }

    @Override
    public Mono<SpendUserPage> getUsers(@NonNull SpendMetricRequest request,
            @NonNull List<SortingField> sortingFields, int page, int size) {
        return resolveProject(request)
                .flatMap(resolved -> aiSpendDAO.getUsers(resolved, sortingFields, page, size))
                .map(userPage -> userPage.toBuilder()
                        .sortableBy(spendUserSortingFactory.getSortableFields())
                        .build());
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

    /**
     * Placeholder rule set: each rule fires when its lane's share of its side exceeds {@code minShare}, and the
     * estimated saving is a fraction ({@code factor}) of that lane's token-weighted slice of total cost. Tuned with
     * product later.
     */
    private static final List<Rule> RECOMMENDATION_RULES = List.of(
            new Rule("compact_threshold", "Tighten /compact threshold on long sessions",
                    "Prior assistant context is the largest input lane. Compacting earlier re-sends less history per turn.",
                    Impact.HIGH, SpendLane.Side.INPUT, "prior_assistant", 0.4, 0.25),
            new Rule("thinking_effort", "Lower thinking effort on routine sessions",
                    "Thinking dominates output tokens. Reducing effort on routine work cuts output cost with little quality impact.",
                    Impact.MEDIUM, SpendLane.Side.OUTPUT, "thinking", 0.5, 0.3),
            new Rule("fewer_tools", "Trim unused tool/MCP schemas",
                    "Tool schemas (built-in and MCP) add overhead on every session whether used or not. Enabling only the tools you actually use reduces prompt cost.",
                    Impact.LOW, SpendLane.Side.INPUT, "tools", 0.0, 0.5));

    private record Rule(String id, String title, String body, Impact impact, SpendLane.Side side, String laneKey,
            double minShare, double factor) {
    }

    private SpendRecommendationsResponse buildRecommendations(SpendCompositionResponse composition) {
        long inputTotal = sideTotal(composition.input());
        long outputTotal = sideTotal(composition.output());
        long grandTotal = inputTotal + outputTotal;
        BigDecimal totalCost = composition.harness().stream()
                .map(SpendCompositionResponse.HarnessEntry::totalEstimatedCost)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<SpendRecommendationsResponse.Item> items = RECOMMENDATION_RULES.stream()
                .map(rule -> {
                    var side = rule.side() == SpendLane.Side.INPUT ? composition.input() : composition.output();
                    long sideTotal = rule.side() == SpendLane.Side.INPUT ? inputTotal : outputTotal;
                    long laneTokens = laneTokens(side, rule.laneKey());
                    if (ratio(laneTokens, sideTotal) <= rule.minShare()) {
                        return null;
                    }
                    return SpendRecommendationsResponse.Item.builder()
                            .id(rule.id())
                            .title(rule.title())
                            .body(rule.body())
                            .impact(rule.impact())
                            .estSaving(estimate(totalCost, laneTokens, grandTotal, rule.factor()))
                            .docsUrl(DOCS_URL)
                            .relatedLaneKey(rule.laneKey())
                            .build();
                })
                .filter(Objects::nonNull)
                .toList();

        BigDecimal totalSavings = items.stream()
                .map(SpendRecommendationsResponse.Item::estSaving)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return SpendRecommendationsResponse.builder().totalSavings(totalSavings).items(items).build();
    }

    private long sideTotal(SpendCompositionResponse.Side side) {
        return Optional.ofNullable(side).map(SpendCompositionResponse.Side::totalTokens).orElse(0L);
    }

    private long laneTokens(SpendCompositionResponse.Side side, String key) {
        if (side == null || side.lanes() == null) {
            return 0L;
        }
        return side.lanes().stream()
                .filter(lane -> key.equals(lane.key()))
                .map(SpendCompositionResponse.Lane::totalTokens)
                .filter(Objects::nonNull)
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
