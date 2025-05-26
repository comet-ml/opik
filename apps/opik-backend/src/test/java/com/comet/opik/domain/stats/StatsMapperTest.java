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
        values.put("total_estimated_cost_avg", new BigDecimal("1.25"));
        values.put("total_estimated_cost_sum", new BigDecimal("2.50"));

        Row row = new SimpleRow(values);

        ProjectStats stats = StatsMapper.mapProjectStats(row, "trace_count");
        Map<String, Object> mapped = stats.stats()
                .stream()
                .collect(Collectors.toMap(ProjectStats.ProjectStatItem::getName,
                        ProjectStats.ProjectStatItem::getValue));

        assertThat(mapped.get(StatsMapper.TOTAL_ESTIMATED_COST)).isEqualTo(1.25);
        assertThat(mapped.get(StatsMapper.TOTAL_ESTIMATED_COST_SUM)).isEqualTo(2.50);
    }
}
