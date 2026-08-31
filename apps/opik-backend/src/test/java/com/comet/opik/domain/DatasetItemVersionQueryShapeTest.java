package com.comet.opik.domain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shape of {@code SELECT_DATASET_ITEM_VERSIONS} (OPIK-8109).
 * <p>
 * The query reads a page of a dataset version in two phases so the wide {@code data} column never enters a
 * sort. Before this shape, a single-phase query sorted the whole version with {@code data} attached: on a
 * version whose total payload exceeded the per-query memory cap returned 500 even for {@code LIMIT 1}.
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
    private static String countSql;

    @BeforeAll
    static void readQuery() throws Exception {
        Class<?> dao = Class.forName("com.comet.opik.domain.DatasetItemVersionDAOImpl");
        Field field = dao.getDeclaredField("SELECT_DATASET_ITEM_VERSIONS");
        field.setAccessible(true);
        sql = (String) field.get(null);

        Field countField = dao.getDeclaredField("SELECT_DATASET_ITEM_VERSIONS_COUNT");
        countField.setAccessible(true);
        countSql = (String) countField.get(null);

        allQueries = new LinkedHashMap<>();
        for (Field f : dao.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                Object value = f.get(null);
                if (value instanceof String text && text.contains("dataset_item_versions")) {
                    allQueries.put(f.getName(), text);
                }
            }
        }

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
                        phase 1's own projection must not reference the 'data' column. Pulling the payload into \
                        the phase that sorts is exactly the OPIK-8109 regression: peak memory then scales with \
                        the version's total payload instead of the page size. Note this asserts the template: a \
                        caller filtering on DATA or FULL_DATA still expands to 'data' inside \
                        <dataset_item_filters>, which streams the payload without sorting it.""")
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
    @DisplayName("invariant 4: phase 1 projects the aliases the filters bind to")
    void invariantFour__phaseOneProjectsFilterVisibleAliases() {
        // FilterQueryBuilder emits bare column names for these fields, and ClickHouse binds WHERE to
        // SELECT aliases. Phase 1 must therefore alias them exactly as phase 2 does, or a filtered page
        // silently selects on this table's physical columns instead.
        assertThat(phaseOne)
                .contains("dataset_item_id AS id")
                .contains("item_created_at AS created_at")
                .contains("item_last_updated_at AS last_updated_at")
                .contains("item_created_by AS created_by")
                .contains("item_last_updated_by AS last_updated_by");
    }

    @Test
    @DisplayName("both phases order by the same expression, so tied rows cannot diverge")
    void orderBy__isTextuallyIdenticalAcrossPhases() {
        assertThat(orderByOf(phaseOne)).isEqualTo(orderByOf(phaseTwo));
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

    @Test
    @DisplayName("the count query resolves filters against the same item-level aliases as the page")
    void countQuery__projectsTheSameFilterVisibleAliases() {
        // The page and its total resolve the same bare column names. When only the page carried the aliases,
        // a filtered page could report a total its own rows could not account for. This assertion is
        // the cheap half of that guard: the behavioural test needs testcontainers and a divergent fixture,
        // this one fails in milliseconds against a string.
        assertThat(countSql)
                .contains("dataset_item_id AS id")
                .contains("item_created_at AS created_at")
                .contains("item_last_updated_at AS last_updated_at")
                .contains("item_created_by AS created_by")
                .contains("item_last_updated_by AS last_updated_by");

        assertThat(countSql)
                .as("* EXCEPT keeps data, source, tags, trace_id and span_id resolvable for their own filters")
                .contains("* EXCEPT");

        assertThat(countSql.indexOf("item_created_at AS created_at"))
                .as("the filters must be applied inside the subquery that defines the aliases, not outside it")
                .isLessThan(countSql.indexOf("<dataset_item_filters>"));
    }

    /**
     * Matches an alias that takes a {@code dataset_item_versions} snapshot-row column and presents it under a
     * name that is read as item-level.
     * <p>
     * Scoped to the {@code div_dedup} qualifier deliberately. Unqualified matching flags legitimate aliases:
     * {@code eia.created_at AS created_at} is the experiment item's own timestamp on a different table, and
     * {@code COPY_ITEMS_FROM_LEGACY} maps {@code dataset_items.created_at} to {@code item_created_at}, where
     * the legacy column really is the authoring time. {@code div_dedup} is this file's convention for the
     * deduplicated {@code dataset_item_versions} subquery, which is exactly where the confusion lives.
     * <p>
     * No lookbehind is needed: {@code _} is a word character, so {@code \bcreated_at} cannot match inside
     * {@code item_created_at}.
     */
    private static final Pattern ROW_COLUMN_ALIASED_TO_ITEM_NAME = Pattern.compile(
            "div_dedup\\.(created_at|last_updated_at|created_by|last_updated_by)\\s+[Aa][Ss]\\s+"
                    + "(item_)?(created_at|last_updated_at|created_by|last_updated_by)\\b");

    /** Every SQL constant on the DAO, by field name. */
    private static Map<String, String> allQueries;

    @Test
    @DisplayName("no query aliases a snapshot-row column to an item-level or filter-visible name")
    void aliases__neverMapRowColumnsToItemLevelNames() {
        // This is the copy-paste family that produced the whole class of defects in this PR: on
        // dataset_item_versions, created_at/last_updated_at/created_by/last_updated_by exist BOTH as the
        // snapshot row's own columns and, prefixed with item_, as the item's. Aliasing the former to the
        // latter's name is silent: filters bind to the wrong column, and where the response consumes the
        // alias it reports the row's timestamp as the item's. It went unnoticed across five constants.
        var offenders = new ArrayList<String>();
        allQueries.forEach((name, sql) -> {
            Matcher m = ROW_COLUMN_ALIASED_TO_ITEM_NAME.matcher(sql);
            while (m.find()) {
                offenders.add("%s: %s".formatted(name, m.group()));
            }
        });

        assertThat(offenders)
                .as("""
                        each of these aliases a snapshot-row column to a name that is read as item-level. \
                        Source the item_* column instead.""")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS",
            "SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT",
            "SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_STATS"})
    @DisplayName("the experiment-items queries expose the filter names from the item-level columns")
    void experimentItemsQueries__resolveFiltersAgainstItemLevelColumns(String constant) {
        String sql = allQueries.get(constant);

        assertThat(sql).as("%s must be present on the DAO", constant).isNotNull();

        assertThat(sql)
                .as("""
                        %s interpolates the dataset item filters, so every scope they resolve in must expose \
                        the filter-visible names from the item-level columns. Before this was guarded, one \
                        scope aliased the row's columns, another omitted them entirely (raising \
                        UNKNOWN_IDENTIFIER, i.e. a 500), and a third aliased in the opposite direction so the \
                        same filter name meant different things per template branch.""", constant)
                .contains("item_created_at AS created_at")
                .contains("item_last_updated_at AS last_updated_at")
                .contains("item_created_by AS created_by")
                .contains("item_last_updated_by AS last_updated_by");
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
