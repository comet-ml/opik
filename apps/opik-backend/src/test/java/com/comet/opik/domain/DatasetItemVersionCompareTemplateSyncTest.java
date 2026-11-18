package com.comet.opik.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS template sync:")
class DatasetItemVersionCompareTemplateSyncTest {

    private static final Pattern TRACE_JOINS_BLOCK = Pattern.compile(
            "INNER JOIN trace_data AS t ON ei2\\.trace_id = t\\.id\\s.*?\\) s ON t\\.id = s\\.trace_id",
            Pattern.DOTALL);

    /**
     * The feedback-score, comment and span cost/usage subqueries joined onto each trace are copied into the raw
     * {@code UNION ALL} branch and the default-sorted raw late-materialization branch. This pins both copies to
     * the same SQL, so a change to one (feedback-score or comment tuple shape, span scope) can't miss the other
     * and leave only default-sorted raw pages diverging.
     */
    @Test
    @DisplayName("the raw branch and the raw late-materialization branch join the same trace subqueries")
    void rawBranchesJoinTheSameTraceSubqueries() throws Exception {
        var blocks = TRACE_JOINS_BLOCK.matcher(compareTemplate()).results()
                .map(match -> match.group().replaceAll("\\s+", " "))
                .toList();

        assertThat(blocks).hasSize(2);
        assertThat(blocks.getLast()).isEqualTo(blocks.getFirst());
    }

    private static String compareTemplate() throws Exception {
        Field field = Class.forName("com.comet.opik.domain.DatasetItemVersionDAOImpl")
                .getDeclaredField("SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS");
        field.setAccessible(true);
        return (String) field.get(null);
    }
}
