package com.comet.opik.domain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shape of {@code SELECT_DATASET_ITEM_VERSIONS} (OPIK-8109).
 * <p>
 * The query reads a page of a dataset version in two phases so the wide {@code data} column never enters a
 * sort. Before this shape, a single-phase query sorted the whole version with {@code data} attached: on a
 * 120k-item version at ~138 KiB per item that peaked at 30 GiB and returned 500, even for {@code LIMIT 1}.
 * <p>
 * These assertions are structural on purpose. The memory behaviour they protect only manifests at dataset
 * sizes far beyond what an integration test can build, so the cheap, deterministic guard is to assert the
 * query keeps the payload out of phase 1 and that the three invariants below still hold. Each invariant
 * broke a draft of the query during OPIK-8109.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SELECT_DATASET_ITEM_VERSIONS query shape (OPIK-8109):")
class DatasetItemVersionQueryShapeTest {

    /** Matches the {@code data} column but not {@code dataset_id}, {@code dataset_item_id}, {@code data_hash}. */
    private static final Pattern BARE_DATA_COLUMN = Pattern.compile("\\bdata\\b");

    private static String sql;
    private static String phaseOne;
    private static String phaseTwo;

    @BeforeAll
    static void readQuery() throws Exception {
        Class<?> dao = Class.forName("com.comet.opik.domain.DatasetItemVersionDAOImpl");
        Field field = dao.getDeclaredField("SELECT_DATASET_ITEM_VERSIONS");
        field.setAccessible(true);
        sql = (String) field.get(null);

        // The CTE is closed by a line containing only ')'. Everything before it is phase 1.
        Matcher close = Pattern.compile("(?m)^\\s*\\)\\s*$").matcher(sql);
        assertThat(close.find())
                .as("query must contain a closing paren for the 'page' CTE")
                .isTrue();
        phaseOne = sql.substring(0, close.start());
        phaseTwo = sql.substring(close.end());
    }

    @Test
    @DisplayName("phase 1 resolves the page from narrow columns, without the data payload")
    void phaseOne__doesNotSelectThePayload() {
        assertThat(sql.stripLeading()).startsWith("WITH page AS (");

        assertThat(BARE_DATA_COLUMN.matcher(phaseOne).find())
                .as("""
                        phase 1 must not reference the 'data' column. Pulling the payload into the phase that \
                        sorts is exactly the OPIK-8109 regression: peak memory then scales with the version's \
                        total payload instead of the page size.""")
                .isFalse();

        assertThat(BARE_DATA_COLUMN.matcher(phaseTwo).find())
                .as("phase 2 is what actually returns the payload")
                .isTrue();
    }

    @Test
    @DisplayName("the page size is applied in phase 1, before any payload is read")
    void pageSize__isAppliedBeforeFetchingThePayload() {
        assertThat(phaseOne).contains(":limit");
        assertThat(phaseTwo)
                .as("phase 2 is already bounded by the ids from phase 1; a second LIMIT would mask a bug")
                .doesNotContain(":limit")
                .doesNotContain(":offset");
        assertThat(phaseTwo).contains("dataset_item_id IN (SELECT dataset_item_id FROM page)");
    }

    @Test
    @DisplayName("invariant 1: both phases order by dataset_item_id DESC first")
    void invariantOne__bothPhasesShareTheLeadingSortKey() {
        assertThat(orderByOf(phaseOne)).startsWith("dataset_item_id DESC");
        assertThat(orderByOf(phaseTwo)).startsWith("dataset_item_id DESC");
    }

    @Test
    @DisplayName("invariant 2: the leading sort key is made unique by LIMIT 1 BY dataset_item_id")
    void invariantTwo__leadingSortKeyIsUniquePerRow() {
        assertThat(phaseOne).contains("LIMIT 1 BY dataset_item_id");
        assertThat(phaseTwo).contains("LIMIT 1 BY dataset_item_id");
    }

    @Test
    @DisplayName("invariant 3: phase 2 repeats the dataset item filters")
    void invariantThree__phaseTwoRepeatsTheFilters() {
        assertThat(occurrences(phaseOne, "<dataset_item_filters>")).isEqualTo(1);
        assertThat(occurrences(phaseTwo, "<dataset_item_filters>"))
                .as("""
                        phase 2 must re-apply the filters. Without them, an id selected in phase 1 via an older \
                        row that matches the filter resolves in phase 2 to the newest row for that id, which may \
                        not match, so the caller receives rows it filtered out.""")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("truncation runs in phase 2, over the page rather than the whole version")
    void truncation__appliesToThePageOnly() {
        assertThat(phaseOne)
                .as("""
                        the image regex must not run in the sorting phase: replaceRegexpAll expands every value \
                        before substring trims it, which is why truncate=true was the worst case, not a \
                        mitigation.""")
                .doesNotContain("<if(truncate)>");
        assertThat(phaseTwo).contains("<if(truncate)>");
    }

    private static String orderByOf(String phase) {
        Matcher m = Pattern.compile("ORDER BY (.+)").matcher(phase);
        assertThat(m.find()).as("phase must have an ORDER BY").isTrue();
        return m.group(1).trim();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
