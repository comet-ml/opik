package com.comet.opik.domain.stats;

import com.comet.opik.api.ProjectStats;
import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StatsMapperTest {

    @RequiredArgsConstructor
    private static class SimpleRowMetadata implements RowMetadata {
        private final Set<String> names = new HashSet<>();

        SimpleRowMetadata(Collection<String> names) {
            this.names.addAll(names);
        }

        @Override
        public ColumnMetadata getColumnMetadata(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ColumnMetadata getColumnMetadata(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<? extends ColumnMetadata> getColumnMetadatas() {
            return List.of();
        }

        @Override
        public boolean contains(String name) {
            return names.contains(name);
        }
    }

    private static class SimpleRow implements Row {
        private final Map<String, Object> data;
        private final RowMetadata metadata;

        SimpleRow(Map<String, Object> data) {
            this.data = data;
            this.metadata = new SimpleRowMetadata(data.keySet());
        }

        @Override
        public <T> T get(int index, Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T get(String name, Class<T> type) {
            Object value = data.get(name);
            return value == null ? null : type.cast(value);
        }

        @Override
        public RowMetadata getMetadata() {
            return metadata;
        }
    }

    @Test
    void mapProjectStats_returnsTotals() {
        Map<String, Object> values = new HashMap<>();
        values.put("trace_count", 1L);
        values.put("duration", List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        values.put("input", 0L);
        values.put("output", 0L);
        values.put("metadata", 0L);
        values.put("tags", 0.0);
        values.put("llm_span_count_avg", 2.0);
        values.put("span_count_avg", 3.0);
        values.put("total_estimated_cost_avg", new BigDecimal("1.25"));
        values.put("total_estimated_cost_sum", new BigDecimal("2.50"));

        Row row = new SimpleRow(values);

        ProjectStats stats = StatsMapper.mapProjectStats(row, "trace_count");
        Map<String, Object> mapped = stats.stats()
                .stream()
                .collect(Collectors.toMap(ProjectStats.ProjectStatItem::getName,
                        ProjectStats.ProjectStatItem::getValue));

        assertThat(mapped.get(StatsMapper.LLM_SPAN_COUNT)).isEqualTo(2.0);
        assertThat(mapped.get(StatsMapper.SPAN_COUNT)).isEqualTo(3.0);
        assertThat(mapped.get(StatsMapper.TOTAL_ESTIMATED_COST)).isEqualTo(1.25);
        assertThat(mapped.get(StatsMapper.TOTAL_ESTIMATED_COST_SUM)).isEqualTo(2.50);
    }

    @Test
    void mapProjectScoresStats_withSpanFeedbackScores_returnsSpanFeedbackScoresStats() {
        // Split-B (SELECT_FEEDBACK_SCORES_STATS / SELECT_SPAN_FEEDBACK_SCORES_STATS) materialises
        // feedback aggregates in their own row mapped via mapProjectScoresStats — mapProjectStats
        // (split-A) no longer carries feedback columns.
        Map<String, Object> values = new HashMap<>();
        Map<String, BigDecimal> spanFeedbackScores = new HashMap<>();
        spanFeedbackScores.put("accuracy", new BigDecimal("0.85"));
        spanFeedbackScores.put("relevance", new BigDecimal("0.80"));
        values.put(StatsMapper.SPAN_FEEDBACK_SCORE, spanFeedbackScores);

        Row row = new SimpleRow(values);

        ProjectStats stats = StatsMapper.mapProjectScoresStats(row);
        Map<String, Object> mapped = stats.stats()
                .stream()
                .collect(Collectors.toMap(ProjectStats.ProjectStatItem::getName,
                        ProjectStats.ProjectStatItem::getValue));

        assertThat(mapped.get("%s.%s".formatted(StatsMapper.SPAN_FEEDBACK_SCORE, "accuracy")))
                .isEqualTo(0.85);
        assertThat(mapped.get("%s.%s".formatted(StatsMapper.SPAN_FEEDBACK_SCORE, "relevance")))
                .isEqualTo(0.80);
    }

    @Test
    void mapProjectStats_whenRowHasFeedbackScoresColumn_emitsFeedbackScoreStats() {
        // Thread stats inline feedback_scores in the same row and call mapProjectStats directly
        // (no merge with mapProjectScoresStats). The mapper must emit them when present so the
        // thread stats endpoint keeps returning feedback aggregates.
        Map<String, Object> values = new HashMap<>();
        values.put("trace_count", 1L);
        values.put("duration", List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        values.put("input", 0L);
        values.put("output", 0L);
        values.put("metadata", 0L);
        values.put("tags", 0.0);
        values.put("llm_span_count_avg", 0.0);
        values.put("span_count_avg", 0.0);
        values.put("total_estimated_cost_avg", BigDecimal.ZERO);
        values.put("total_estimated_cost_sum", BigDecimal.ZERO);

        Map<String, BigDecimal> feedbackScores = new HashMap<>();
        feedbackScores.put("accuracy", new BigDecimal("0.85"));
        values.put(StatsMapper.FEEDBACK_SCORE, feedbackScores);

        Row row = new SimpleRow(values);

        ProjectStats stats = StatsMapper.mapProjectStats(row, "trace_count");
        Map<String, Object> mapped = stats.stats()
                .stream()
                .collect(Collectors.toMap(ProjectStats.ProjectStatItem::getName,
                        ProjectStats.ProjectStatItem::getValue));

        assertThat(mapped.get("%s.%s".formatted(StatsMapper.FEEDBACK_SCORE, "accuracy")))
                .isEqualTo(0.85);
    }

    @Test
    void mapProjectStats_whenRowOmitsFeedbackScoresColumn_doesNotEmitFeedbackScoreStats() {
        // Split-A SELECT_TRACES_SPANS_STATS / SELECT_SPANS_STATS rows don't include the
        // feedback_scores column — those aggregates come from the companion split-B query and
        // are spliced in by StatsMerger.
        Map<String, Object> values = new HashMap<>();
        values.put("trace_count", 1L);
        values.put("duration", List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        values.put("input", 0L);
        values.put("output", 0L);
        values.put("metadata", 0L);
        values.put("tags", 0.0);
        values.put("llm_span_count_avg", 2.0);
        values.put("span_count_avg", 3.0);
        values.put("total_estimated_cost_avg", new BigDecimal("1.25"));
        values.put("total_estimated_cost_sum", new BigDecimal("2.50"));

        Row row = new SimpleRow(values);

        ProjectStats stats = StatsMapper.mapProjectStats(row, "trace_count");
        Map<String, Object> mapped = stats.stats()
                .stream()
                .collect(Collectors.toMap(ProjectStats.ProjectStatItem::getName,
                        ProjectStats.ProjectStatItem::getValue));

        assertThat(mapped.keySet())
                .noneMatch(k -> k.startsWith(StatsMapper.FEEDBACK_SCORE)
                        || k.startsWith(StatsMapper.SPAN_FEEDBACK_SCORE));
    }
}
