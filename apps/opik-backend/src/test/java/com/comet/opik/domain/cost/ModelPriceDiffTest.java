package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceDiffTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-01T10:00:00Z");

    private static Supplier<UUID> sequentialIds() {
        var next = new AtomicInteger();
        return () -> new UUID(0, next.incrementAndGet());
    }

    private static CustomModelPriceValidator.ValidRow requested(String modelName, String inputPerMillion) {
        return new CustomModelPriceValidator.ValidRow(CustomModelPrice.builder()
                .modelName(modelName)
                .provider("openai")
                .kind(CustomModelPrice.Kind.FIXED)
                .inputPerMillion(new BigDecimal(inputPerMillion))
                .build(), null);
    }

    private static ModelPriceRow stored(String modelName, String inputPerMillion) {
        return ModelPriceRow.of(UUID.randomUUID(), requested(modelName, inputPerMillion)).toBuilder()
                .createdAt(CREATED_AT)
                .createdBy("alice")
                .build();
    }

    @Test
    void ofSortsRequestedRowsIntoCreatedUpdatedDeletedAndUnchanged() {
        var same = stored("gpt-5-mini", "1.0000000000");
        var changed = stored("gpt-4o", "2.00");
        var dropped = stored("gpt-4.1", "3.00");

        var diff = ModelPriceDiff.of(List.of(same, changed, dropped),
                List.of(requested("gpt-5-mini", "1.00"), requested("gpt-4o", "2.50"), requested("o3", "4.00")),
                sequentialIds());

        assertThat(diff.unchanged()).containsExactly(same);
        assertThat(diff.updated()).singleElement().satisfies(change -> {
            assertThat(change.before()).isEqualTo(changed);
            assertThat(change.after().id()).isEqualTo(changed.id());
            assertThat(change.after().inputPerMillion()).isEqualByComparingTo("2.50");
            assertThat(change.after().createdAt()).isEqualTo(CREATED_AT);
            assertThat(change.after().createdBy()).isEqualTo("alice");
        });
        assertThat(diff.deleted()).containsExactly(dropped);
        assertThat(diff.created()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(new UUID(0, 1));
            assertThat(row.modelName()).isEqualTo("o3");
        });
        assertThat(diff.rowsAfter()).extracting(ModelPriceRow::modelName)
                .containsExactly("gpt-4o", "gpt-5-mini", "o3");
    }

    @Test
    void ofTheStoredRowsAgainIsEmpty() {
        var row = stored("gpt-5-mini", "1.00");

        var diff = ModelPriceDiff.of(List.of(row), List.of(requested("gpt-5-mini", "1.00")), sequentialIds());

        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.rowsAfter()).containsExactly(row);
    }

    @Test
    void ofAChangedNoteIsAnUpdate() {
        var row = stored("gpt-5-mini", "1.00");
        var withNote = new CustomModelPriceValidator.ValidRow(requested("gpt-5-mini", "1.00").price(),
                "Contract 2026-Q4");

        var diff = ModelPriceDiff.of(List.of(row), List.of(withNote), sequentialIds());

        assertThat(diff.updated()).singleElement()
                .satisfies(change -> assertThat(change.after().note()).isEqualTo("Contract 2026-Q4"));
    }

    @Test
    void ofAnEmptyRequestDeletesEveryStoredRow() {
        var first = stored("gpt-5-mini", "1.00");
        var second = stored("gpt-4o", "2.00");

        var diff = ModelPriceDiff.of(List.of(first, second), List.of(), sequentialIds());

        assertThat(diff.deleted()).containsExactly(first, second);
        assertThat(diff.rowsAfter()).isEmpty();
    }
}
