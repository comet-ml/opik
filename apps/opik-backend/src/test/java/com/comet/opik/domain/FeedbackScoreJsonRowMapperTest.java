package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code metadata} cell of the JSONEachRow row. The integration suite cannot read it back, since no
 * API read returns score metadata yet, and the cell has to be the one the R2DBC binder writes for
 * {@code bulkInsert.v2ClientEnabled} to stay safe to flip.
 */
class FeedbackScoreJsonRowMapperTest {

    // Instance, not static: PodamFactory is not fully thread-safe, and instance-per-class is the
    // convention across the service's tests.
    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private final String user = RandomStringUtils.secure().nextAlphanumeric(20);
    private final String workspaceId = UUID.randomUUID().toString();

    private FeedbackScoreBatchItem score(Map<String, Object> metadata) {
        // The project id is resolved from the name above the DAO, so Podam leaves it null.
        return factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                .projectId(UUID.randomUUID())
                .metadata(metadata)
                .build();
    }

    @Test
    @DisplayName("metadata is written as JSON text for the String column, not as a nested object")
    void metadataIsWrittenAsJsonText() {
        Map<String, Object> metadata = Map.of(
                "evaluator_revision", RandomStringUtils.secure().nextAlphanumeric(12),
                "passed", true,
                "config", Map.of("threshold_pct", 80, "labels", List.of("pass", "fail")));

        var row = FeedbackScoreJsonRowMapper.toJsonRow(score(metadata), EntityType.TRACE, user, workspaceId, null);

        var cell = row.get("metadata");
        assertThat(cell.isTextual()).isTrue();
        assertThat(JsonUtils.readValue(cell.asText(), new TypeReference<Map<String, Object>>() {
        })).isEqualTo(metadata);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("absent or empty metadata is written as the column default, an empty string")
    void absentMetadataIsTheColumnDefault(Map<String, Object> metadata) {
        var row = FeedbackScoreJsonRowMapper.toJsonRow(score(metadata), EntityType.TRACE, user, workspaceId, null);

        assertThat(row.get("metadata").isTextual()).isTrue();
        assertThat(row.get("metadata").asText()).isEmpty();
    }
}
