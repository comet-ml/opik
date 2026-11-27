package com.comet.opik.domain.cost;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceDiffTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final Instant CREATED_AT = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final String CREATED_BY = RandomStringUtils.secure().nextAlphanumeric(10);
    private static final String PROVIDER = randomName();

    private static String randomName() {
        return RandomStringUtils.secure().nextAlphanumeric(10).toLowerCase(Locale.ROOT);
    }

    private static Supplier<UUID> sequentialIds() {
        var next = new AtomicInteger();
        return () -> new UUID(0, next.incrementAndGet());
    }

    private static CustomModelPriceValidator.ValidRow requested(String modelName, String inputPerMillion) {
        return new CustomModelPriceValidator.ValidRow(CustomModelPrice.builder()
                .modelName(modelName)
                .provider(PROVIDER)
                .kind(CustomModelPrice.Kind.FIXED)
                .inputPerMillion(new BigDecimal(inputPerMillion))
                .build(), null);
    }

    private static ModelPriceRow stored(String modelName, String inputPerMillion) {
        return ModelPriceRow.of(ID_GENERATOR.generateId(), requested(modelName, inputPerMillion)).toBuilder()
                .createdAt(CREATED_AT)
                .createdBy(CREATED_BY)
                .build();
    }

    @Test
    void ofSortsRequestedRowsIntoCreatedUpdatedDeletedAndUnchanged() {
        var same = stored(randomName(), "1.0000000000");
        var changed = stored(randomName(), "2.00");
        var dropped = stored(randomName(), "3.00");
        var newModel = randomName();

        var diff = ModelPriceDiff.of(List.of(same, changed, dropped),
                List.of(requested(same.modelName(), "1.00"), requested(changed.modelName(), "2.50"),
                        requested(newModel, "4.00")),
                sequentialIds());

        var updatedAfter = changed.toBuilder().inputPerMillion(new BigDecimal("2.50")).build();
        var created = ModelPriceRow.of(new UUID(0, 1), requested(newModel, "4.00"));
        assertThat(diff).isEqualTo(ModelPriceDiff.builder()
                .created(List.of(created))
                .updated(List.of(ModelPriceDiff.Change.builder().before(changed).after(updatedAfter).build()))
                .deleted(List.of(dropped))
                .unchanged(List.of(same))
                .build());
        assertThat(diff.rowsAfter())
                .containsExactlyInAnyOrder(same, updatedAfter, created)
                .isSortedAccordingTo(Comparator.comparing(ModelPriceRow::modelName));
    }

    @Test
    void ofTheStoredRowsAgainIsEmpty() {
        var row = stored(randomName(), "1.00");

        var diff = ModelPriceDiff.of(List.of(row), List.of(requested(row.modelName(), "1.00")), sequentialIds());

        assertThat(diff.isEmpty()).isTrue();
        assertThat(diff.rowsAfter()).containsExactly(row);
    }

    @Test
    void ofAChangedNoteIsAnUpdate() {
        var row = stored(randomName(), "1.00");
        var note = RandomStringUtils.secure().nextAlphanumeric(20);
        var withNote = new CustomModelPriceValidator.ValidRow(requested(row.modelName(), "1.00").price(), note);

        var diff = ModelPriceDiff.of(List.of(row), List.of(withNote), sequentialIds());

        assertThat(diff.updated()).containsExactly(ModelPriceDiff.Change.builder()
                .before(row)
                .after(row.toBuilder().note(note).build())
                .build());
    }

    @Test
    void ofAnEmptyRequestDeletesEveryStoredRow() {
        var first = stored(randomName(), "1.00");
        var second = stored(randomName(), "2.00");

        var diff = ModelPriceDiff.of(List.of(first, second), List.of(), sequentialIds());

        assertThat(diff.deleted()).containsExactly(first, second);
        assertThat(diff.rowsAfter()).isEmpty();
    }
}
