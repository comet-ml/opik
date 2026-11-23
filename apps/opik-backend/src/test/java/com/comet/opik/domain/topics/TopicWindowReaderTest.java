package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicWindowReaderTest {

    @ParameterizedTest(name = "caller's count {0}")
    @ValueSource(longs = {1, 20, 1_000})
    @DisplayName("the window comes out in time order, each text with its own vector and trace, whatever the count")
    void timeOrderWhateverTheCount(long count) {
        int d = TopicEmbedding.DIMENSIONS;
        var random = new Random(count);
        var start = Instant.parse("2026-11-01T00:00:00Z");
        var rows = new ArrayList<TopicWindowDAO.WindowRow>();
        for (int i = 0; i < 20; i++) {
            var vector = new float[d];
            vector[0] = i;
            rows.add(TopicWindowDAO.WindowRow.builder()
                    .traceId(new UUID(0, i))
                    .traceCreatedAt(start.plusSeconds(i))
                    .taskVersion(1)
                    .text("text " + i)
                    .vector(vector)
                    .build());
        }
        rows.add(rows.get(3).toBuilder().text("  ").traceId(new UUID(1, 0)).build());
        Collections.shuffle(rows, random);
        var dao = mock(TopicWindowDAO.class);
        when(dao.readWindow(any(), any(), any(), any(), anyInt(), anyLong())).thenReturn(Flux.fromIterable(rows));
        var reader = new TopicWindowReader(TestConfigUtils.loadConfigTest().getTopics(), dao);
        var run = TopicRun.of(new UUID(2, 0), "workspace", "workspace", new UUID(3, 0),
                LocalDate.of(2026, 11, 20), 30);

        var window = reader.read(run, count).block();

        assertThat(window.texts()).hasSize(20);
        assertThat(window.vectors()).hasSize(20 * d);
        for (int i = 0; i < 20; i++) {
            assertThat(window.texts()[i]).isEqualTo("text " + i);
            assertThat(window.vectors()[i * d]).isEqualTo(i);
            assertThat(window.traces().get(i).traceCreatedAt()).isEqualTo(start.plusSeconds(i));
        }
        assertThat(window.stats().get("skipped_rows").asInt()).isEqualTo(1);
        assertThat(window.stats().get("window_traces").asLong()).isEqualTo(count);
    }
}
