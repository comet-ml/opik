package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.DatasetItemField;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the v2-client entry points on {@link FilterQueryBuilder}:
 * {@link FilterQueryBuilder#toAnalyticsDbFiltersV2Client} and
 * {@link FilterQueryBuilder#populateV2ClientParams}.
 *
 * <p>Together these enable {@code com.clickhouse.client.api.Client#query(sql, params, settings)}
 * to consume the same filter expressions built for the r2dbc driver, with v2 {@code {name:Type}}
 * placeholders instead of r2dbc {@code :name}.
 *
 * <p>The inner {@link FilterQueryBuilder#rewritePlaceholdersForV2Client} helper (package-private)
 * is covered directly in {@link RewritePlaceholders} since the public entry point delegates to it.
 */
@DisplayName("FilterQueryBuilder v2 client entry points")
class FilterQueryBuilderV2ClientTest {

    @Nested
    @DisplayName("toAnalyticsDbFiltersV2Client")
    class ToAnalyticsDbFiltersV2Client {

        @Test
        @DisplayName("Emits v2 {name:Type} placeholders end-to-end (no separate rewrite call)")
        void v2EntryPoint__rendersTypedPlaceholders() {
            // SOURCE is a STRING field; the DAO's excludeFilters path drives EQUAL/CONTAINS
            // here in production. (IN/NOT_IN are ENUM-only in the operator/type matrix.)
            var f0 = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.EQUAL, null, "manual");
            var f1 = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.EQUAL, null, "sdk");

            String sql = FilterQueryBuilder
                    .toAnalyticsDbFiltersV2Client(List.of(f0, f1), FilterStrategy.DATASET_ITEM)
                    .orElseThrow();

            // The output must carry typed v2 placeholders and must not retain any r2dbc-style
            // :name placeholders.
            assertThat(sql).contains("{filter0:String}");
            assertThat(sql).contains("{filter1:String}");
            assertThat(sql).doesNotContain(":filter0");
            assertThat(sql).doesNotContain(":filter1");
        }

        @Test
        @DisplayName("Empty filter list returns Optional.empty()")
        void v2EntryPoint__emptyFilters__returnsEmpty() {
            assertThat(FilterQueryBuilder.toAnalyticsDbFiltersV2Client(List.of(), FilterStrategy.DATASET_ITEM))
                    .isEmpty();
        }

        @Test
        @DisplayName("Threads traceColumnsNonNullable: trace end_time uses the sentinel-aware column under the flag")
        void v2EntryPoint__threadsTraceColumnsNonNullableFlag() {
            var filter = TraceFilter.builder()
                    .field(TraceField.END_TIME)
                    .operator(Operator.LESS_THAN)
                    .value(Instant.now().toString())
                    .build();
            var withFlag = FilterQueryBuilder
                    .toAnalyticsDbFiltersV2Client(List.of(filter), FilterStrategy.TRACE, true)
                    .orElseThrow();
            var withoutFlag = FilterQueryBuilder
                    .toAnalyticsDbFiltersV2Client(List.of(filter), FilterStrategy.TRACE, false)
                    .orElseThrow();

            // Flag on -> sentinel-aware end_time; flag off -> raw column. Both emit v2 {name:Type} placeholders.
            assertThat(withFlag).contains("nullIf(end_time, toDateTime64('1970-01-01 00:00:00.000', 9))");
            assertThat(withoutFlag).doesNotContain("nullIf(end_time");
            assertThat(withFlag).doesNotContain(":filter0");
        }
    }

    @Nested
    @DisplayName("rewritePlaceholdersForV2Client (internal helper)")
    class RewritePlaceholders {

        @Test
        @DisplayName("Single-value operator produces {filterN:String} placeholder")
        void rewrite__singleValueOperator__producesStringPlaceholder() {
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.EQUAL, null, "manual");
            String input = "AND source = :filter0";

            String out = FilterQueryBuilder.rewritePlaceholdersForV2Client(input, List.of(filter));

            assertThat(out).isEqualTo("AND source = {filter0:String}");
        }

        @Test
        @DisplayName("Multi-value operator produces {filterN:Array(String)} placeholder")
        void rewrite__multiValueOperator__producesArrayPlaceholder() {
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.IN, null, "manual,sdk");
            String input = "AND source IN :filter0";

            String out = FilterQueryBuilder.rewritePlaceholdersForV2Client(input, List.of(filter));

            assertThat(out).isEqualTo("AND source IN {filter0:Array(String)}");
        }

        @Test
        @DisplayName("Mixed filters get distinct types per index")
        void rewrite__mixedFilters__typedPerIndex() {
            var f0 = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.IN, null, "manual,sdk");
            var f1 = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.EQUAL, null, "manual");
            String input = "AND (source IN :filter0) AND (source = :filter1)";

            String out = FilterQueryBuilder.rewritePlaceholdersForV2Client(input, List.of(f0, f1));

            assertThat(out).isEqualTo(
                    "AND (source IN {filter0:Array(String)}) AND (source = {filter1:String})");
        }

        @Test
        @DisplayName("Placeholders with overlapping prefixes (filter1 vs filter12) don't collide")
        void rewrite__overlappingPrefixes__resolvesLongestFirst() {
            // 13 single-value filters so :filter12 appears in the input.
            List<DatasetItemFilter> filters = new java.util.ArrayList<>();
            for (int i = 0; i < 13; i++) {
                filters.add(new DatasetItemFilter(DatasetItemField.SOURCE, Operator.EQUAL, null, "v" + i));
            }
            String input = "x = :filter1 OR y = :filter12";

            String out = FilterQueryBuilder.rewritePlaceholdersForV2Client(input, filters);

            // The longer name must win: :filter12 stays a single replacement, not a partial
            // match of :filter1 followed by literal "2".
            assertThat(out).isEqualTo("x = {filter1:String} OR y = {filter12:String}");
        }

        @Test
        @DisplayName("Dynamic field and JSON-path placeholders convert to {…:String}")
        void rewrite__dynamicPlaceholders__convertToString() {
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.EQUAL, null, "x");
            String input = "json_path(:dynamicJsonPath0) = :filter0 AND key(:dynamicField0) = :filterKey0";

            String out = FilterQueryBuilder.rewritePlaceholdersForV2Client(input, List.of(filter));

            assertThat(out).isEqualTo(
                    "json_path({dynamicJsonPath0:String}) = {filter0:String} "
                            + "AND key({dynamicField0:String}) = {filterKey0:String}");
        }

        @Test
        @DisplayName("Empty fragment is returned unchanged")
        void rewrite__emptyFragment__unchanged() {
            assertThat(FilterQueryBuilder.rewritePlaceholdersForV2Client("", List.of()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("populateV2ClientParams")
    class PopulateV2ClientParams {

        @Test
        @DisplayName("Single-value filter binds the raw value")
        void bind__singleValue__rawValue() {
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.EQUAL, null, "manual");
            Map<String, Object> params = new HashMap<>();

            FilterQueryBuilder.populateV2ClientParams(params, List.of(filter), FilterStrategy.DATASET_ITEM);

            assertThat(params).containsEntry("filter0", "manual");
        }

        @Test
        @DisplayName("Multi-value filter binds a ClickHouse array literal string, not a Java array")
        void bind__multiValue__formattedArrayLiteral() {
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.IN, null, "manual,sdk,unknown");
            Map<String, Object> params = new HashMap<>();

            FilterQueryBuilder.populateV2ClientParams(params, List.of(filter), FilterStrategy.DATASET_ITEM);

            // The v2 client serialises map values via String.valueOf; we must emit a CH literal
            // ourselves so we don't end up with the unquoted Java array form [manual, sdk, unknown].
            assertThat(params).containsEntry("filter0", "['manual','sdk','unknown']");
        }

        @Test
        @DisplayName("Multi-value: trims whitespace and drops empty tokens")
        void bind__multiValue__trimsAndDropsEmpty() {
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.IN, null, " manual , , sdk,");
            Map<String, Object> params = new HashMap<>();

            FilterQueryBuilder.populateV2ClientParams(params, List.of(filter), FilterStrategy.DATASET_ITEM);

            assertThat(params).containsEntry("filter0", "['manual','sdk']");
        }

        @Test
        @DisplayName("Injection: single quote inside array element is escaped")
        void bind__multiValue__singleQuoteEscaped() {
            // Attempt: close the string then inject SQL.
            String malicious = "x';DROP TABLE users;--";
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.IN, null, malicious);
            Map<String, Object> params = new HashMap<>();

            FilterQueryBuilder.populateV2ClientParams(params, List.of(filter), FilterStrategy.DATASET_ITEM);

            // ClickHouseUtil escapes ' as \' (C-style). The resulting literal stays inside a
            // single-element array — no SQL escape.
            assertThat(params).containsEntry("filter0", "['x\\';DROP TABLE users;--']");
        }

        @Test
        @DisplayName("Injection: backslash + single quote does not break out of the literal")
        void bind__multiValue__backslashQuoteEscaped() {
            // Attempt: use ClickHouse's C-style \' escape to terminate the string.
            // If we only escaped quotes (not backslashes) the output would be '\''…' which
            // ClickHouse parses as ' (escaped quote) + literal '…' — string escape.
            String malicious = "x\\';DROP;--";
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.IN, null, malicious);
            Map<String, Object> params = new HashMap<>();

            FilterQueryBuilder.populateV2ClientParams(params, List.of(filter), FilterStrategy.DATASET_ITEM);

            // Backslash → \\, quote → \' — the whole payload stays a single element.
            assertThat(params).containsEntry("filter0", "['x\\\\\\';DROP;--']");
        }

        @Test
        @DisplayName("formatStringArrayLiteral: Collection<String> overload renders empty array as []")
        void formatStringArrayLiteral__empty__rendersEmptyArray() {
            assertThat(FilterQueryBuilder.formatStringArrayLiteral(List.of())).isEqualTo("[]");
        }

        @Test
        @DisplayName("Operators with no value (e.g. IS_EMPTY) do not bind a filter param")
        void bind__noValueOperator__noFilterParam() {
            // IS_EMPTY/IS_NOT_EMPTY don't consume the value, but the DTO requires a non-null
            // value field so pass an empty string.
            var filter = new DatasetItemFilter(DatasetItemField.SOURCE, Operator.IS_EMPTY, null, "");
            Map<String, Object> params = new HashMap<>();

            FilterQueryBuilder.populateV2ClientParams(params, List.of(filter), FilterStrategy.DATASET_ITEM);

            assertThat(params).doesNotContainKey("filter0");
        }
    }
}
