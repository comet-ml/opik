// Fixtures for java-sql-string-formatting.yaml. Run with:
//   semgrep test --config .semgrep/java-sql-string-formatting.yaml .semgrep/java-sql-string-formatting.java
//
// Semgrep's own annotations drive the assertions: a rule-id comment above a line marks it
// as one the rule MUST flag, and the negative form marks one it must NOT.
//
// The must-not-flag cases below are the ones that actually bit. Every one of them matched
// at some point during development, and the quoted-value and projection cases were caught
// in PR review rather than by measurement. Keep them — they are the regression contract
// for any future edit to the regex.
class JavaSqlStringFormattingFixtures {

    // ---------------------------------------------------------------------------
    // MUST FLAG — a `%s` standing in for a clause, the injection-shaped pattern.
    // ---------------------------------------------------------------------------

    // ruleid: sql-query-with-format-slot
    static final String CLAUSE_SPLICE_AND = """
            SELECT id
            FROM spans
            WHERE workspace_id = :workspace_id
            AND %s
            """;

    // ruleid: sql-query-with-format-slot
    static final String CLAUSE_SPLICE_WHERE = """
            SELECT name
            FROM system.columns
            WHERE %s
            """;

    // ruleid: sql-query-with-format-slot
    static final String CLAUSE_SPLICE_LOWERCASE = """
            select id
            from spans
            where workspace_id = :w
            and %s
            """;

    // ruleid: sql-query-with-format-slot
    static final String CLAUSE_SPLICE_NESTED_WITH = """
            WITH x AS (
            SELECT id
            FROM spans
            WHERE a = 1
              AND %s
            )
            """;

    // ---------------------------------------------------------------------------
    // MUST NOT FLAG — value and projection positions, and non-SQL strings.
    // ---------------------------------------------------------------------------

    // A slot inside a quoted SQL literal is a value position, not a clause. The ClickHouse
    // health checks fill these from compile-time constants (OPIK_7727 tracks the cleanup).
    // ok: sql-query-with-format-slot
    static final String VALUE_SLOT_TIGHT_QUOTES = """
            SELECT count() AS cnt
            FROM system.disks
            WHERE name = '%s'
            """;

    // Same, with whitespace inside the quotes — the `(?<!')%s(?!')` form missed this.
    // ok: sql-query-with-format-slot
    static final String VALUE_SLOT_SPACED_QUOTES = """
            SELECT count() AS cnt
            FROM system.clusters
            WHERE cluster = ' %s '
            """;

    // A projection: the slot names a column, it does not splice a predicate. Real instances
    // live in SpansLocalV2TableTest / NaNAwareAggregateIntegrationTest / TracesLocalV2CutoverTest.
    // ok: sql-query-with-format-slot
    static final String PROJECTION_SLOT = """
            SELECT %s AS value
            FROM spans_local_v2
            WHERE workspace_id = :workspace_id
            """;

    // ok: sql-query-with-format-slot
    static final String PROJECTION_SLOT_IN_CTE = """
            WITH new_trace AS (
              SELECT toFixedString('%s', 36) AS id,
              %s AS ttft
            )
            SELECT ttft
            FROM new_trace
            """;

    // A whole-CTE prefix placeholder, filled at class init from a constant (OPIK_7727).
    // ok: sql-query-with-format-slot
    static final String PREFIX_SPLICE = """
            %s
            SELECT bucket
            FROM spans_filtered
            """;

    // Correctly parameterized: nothing to flag.
    // ok: sql-query-with-format-slot
    static final String FULLY_BOUND = """
            SELECT id
            FROM spans
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            """;

    // Not SQL: a log message that happens to contain the word "update".
    // ok: sql-query-with-format-slot
    static final String LOG_MESSAGE = "Completed bulk update for '%s' dataset items";

    // Not SQL: an LLM prompt containing the jq expression `..|select(.error_info?)`.
    // ok: sql-query-with-format-slot
    static final String PROMPT_WITH_JQ = """
            Use `%s` to inspect the trace.
            Filter with `..|select(.error_info?)` to find errors.
            Results come from the span tree, not from the top-level input.
            """;

    // Real SQL date format, not an integer slot — why the rule matches %s only, never %d.
    // ok: sql-query-with-format-slot
    static final String DATE_FORMAT_NOT_A_SLOT = """
            SELECT value
            FROM metadata
            WHERE STR_TO_DATE(value, '%Y-%m-%d') < CURDATE()
            """;
}
