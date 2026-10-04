package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.ValueEntry;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackScoreEvaluatorRevisionTest {

    private static final String UPDATED_AT = "2026-10-03T12:00:00Z";

    @Test
    @DisplayName("score revision is set only when every entry has the same one")
    void agreedEvaluatorRevision() {
        assertThat(FeedbackScoreMapper.agreedEvaluatorRevision(Map.of(
                "a", entry("judge@1"),
                "b", entry("judge@1")))).isEqualTo("judge@1");

        assertThat(FeedbackScoreMapper.agreedEvaluatorRevision(Map.of(
                "a", entry("judge@1"),
                "b", entry("judge@2")))).isNull();

        assertThat(FeedbackScoreMapper.agreedEvaluatorRevision(Map.of(
                "a", entry("judge@1"),
                "b", entry(null)))).isNull();

        assertThat(FeedbackScoreMapper.agreedEvaluatorRevision(Map.of("a", entry(null)))).isNull();
        assertThat(FeedbackScoreMapper.agreedEvaluatorRevision(Map.of())).isNull();
        assertThat(FeedbackScoreMapper.agreedEvaluatorRevision(null)).isNull();
    }

    @Test
    @DisplayName("ClickHouse tuples read the revision from the 10th element, and older 9-element tuples still parse")
    void parseValueByAuthorFromTuples() {
        var withRevision = tuple("author-a", "judge@1");
        var emptyRevision = tuple("author-b", "");
        var legacy = tuple("author-c", null).subList(0, 9);

        var parsed = FeedbackScoreMapper.parseValueByAuthor(new LinkedHashMap<>(Map.of(
                "author-a", withRevision,
                "author-b", emptyRevision,
                "author-c", legacy)));

        assertThat(parsed.get("author-a").evaluatorRevision()).isEqualTo("judge@1");
        assertThat(parsed.get("author-b").evaluatorRevision()).isNull();
        assertThat(parsed.get("author-c").evaluatorRevision()).isNull();
        assertThat(parsed.get("author-c").author()).isEqualTo("author-c");
    }

    @Test
    @DisplayName("JSON scores (experiment items) read the revision and derive the score-level one")
    void parseScoresFromJson() {
        var json = """
                [{"name": "accuracy", "category_name": "", "value": 1, "reason": "", "source": "sdk",
                  "created_at": "%1$s", "last_updated_at": "%1$s", "created_by": "u", "last_updated_by": "u",
                  "value_by_author": {"u": {"value": 1, "reason": "", "category_name": "", "source": "sdk",
                    "last_updated_at": "%1$s", "span_type": "", "span_id": "", "source_queue_id": "",
                    "author": "u", "evaluator_revision": "judge@1"}}}]
                """.formatted(UPDATED_AT);

        var scores = FeedbackScoreMapper.getFeedbackScores(json);

        assertThat(scores).hasSize(1);
        assertThat(scores.getFirst().evaluatorRevision()).isEqualTo("judge@1");
        assertThat(scores.getFirst().valueByAuthor().get("u").evaluatorRevision()).isEqualTo("judge@1");
    }

    @Test
    @DisplayName("JSONEachRow row carries the revision, and an absent one as the column default")
    void jsonRowCarriesRevision() {
        var withRevision = item("judge@1");
        var withoutRevision = item(null);

        var row = FeedbackScoreJsonRowMapper.toJsonRow(withRevision, EntityType.TRACE, "user", "workspace", null);
        var emptyRow = FeedbackScoreJsonRowMapper.toJsonRow(withoutRevision, EntityType.TRACE, "user", "workspace",
                null);

        assertThat(row.get("evaluator_revision").asText()).isEqualTo("judge@1");
        assertThat(emptyRow.get("evaluator_revision").asText()).isEmpty();
        assertThat(JsonUtils.writeValueAsString(row)).contains("\"evaluator_revision\":\"judge@1\"");
    }

    @Test
    @DisplayName("single-entity score request keeps the revision when mapped to a batch item")
    void singleScoreMapsRevision() {
        var item = FeedbackScoreMapper.INSTANCE.toFeedbackScore(UUID.randomUUID(), UUID.randomUUID(),
                FeedbackScoreMapper.INSTANCE.toFeedbackScore(item("judge@1")));

        assertThat(item.evaluatorRevision()).isEqualTo("judge@1");
    }

    private static ValueEntry entry(String revision) {
        return ValueEntry.builder().value(BigDecimal.ONE).evaluatorRevision(revision).build();
    }

    private static List<Object> tuple(String author, String revision) {
        return Arrays.asList(BigDecimal.ONE, "", "", "sdk", Instant.parse(UPDATED_AT), "", "", "", author,
                revision);
    }

    private static FeedbackScoreBatchItem item(String revision) {
        return FeedbackScoreBatchItem.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("accuracy")
                .value(BigDecimal.ONE)
                .source(ScoreSource.SDK)
                .evaluatorRevision(revision)
                .build();
    }
}
