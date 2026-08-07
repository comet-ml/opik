// Fixtures for java-sql-string-formatting.yaml. Run with:
//   semgrep test --config .semgrep/java-sql-string-formatting.yaml .semgrep/java-sql-string-formatting.java
//
// Semgrep's own annotations drive the assertions: a rule-id comment above a line marks it
// as one that rule MUST flag, and the negative form marks one it must NOT.
//
// Two rules are asserted independently:
//   sql-query-clause-splice — ERROR, blocks CI. Injection-shaped clause splices only.
//   sql-query-format-slot   — WARNING, reports only. Any `%s` in a SQL literal.
//
// A clause splice is flagged by BOTH (it is also a format slot); everything else that
// carries a slot is flagged by the reporting rule alone. Non-SQL strings are flagged by
// neither — those cases are the ones that actually bit during development, so keep them.
class JavaSqlStringFormattingFixtures {

    // ---------------------------------------------------------------------------
    // BLOCKING — a `%s` standing in for a whole clause. Caller data reaches the query.
    // ---------------------------------------------------------------------------

    // ruleid: sql-query-clause-splice,sql-query-format-slot
    static final String CLAUSE_SPLICE_AND = """
            SELECT id
            FROM spans
            WHERE workspace_id = :workspace_id
            AND %s
            """;

    // ruleid: sql-query-clause-splice,sql-query-format-slot
    static final String CLAUSE_SPLICE_WHERE = """
            SELECT name
            FROM system.columns
            WHERE %s
            """;

    // ruleid: sql-query-clause-splice,sql-query-format-slot
    static final String CLAUSE_SPLICE_LOWERCASE = """
            select id
            from spans
            where workspace_id = :w
            and %s
            """;

    // Non-SELECT statements are in scope too: an UPDATE or DELETE predicate is exactly as
    // injectable as a SELECT one, and a splice there mutates rather than reads.
    // ruleid: sql-query-clause-splice,sql-query-format-slot
    static final String CLAUSE_SPLICE_UPDATE = """
            UPDATE spans
            SET name = :name
            WHERE workspace_id = :workspace_id
            AND %s
            """;

    // ruleid: sql-query-clause-splice,sql-query-format-slot
    static final String CLAUSE_SPLICE_DELETE = """
            DELETE FROM spans
            WHERE workspace_id = :workspace_id
            AND %s
            """;

    // ---------------------------------------------------------------------------
    // REPORTED, NOT BLOCKING — the query text is still assembled by formatting, but the
    // slot is not a clause. Prefer a bound parameter or a StringTemplate attribute anyway.
    // ---------------------------------------------------------------------------

    // Value position. Filled from a compile-time constant in the ClickHouse health checks,
    // so not injectable today — but the query text should still be a constant.
    // ok: sql-query-clause-splice
    // ruleid: sql-query-format-slot
    static final String VALUE_SLOT_TIGHT_QUOTES = """
            SELECT count() AS cnt
            FROM system.disks
            WHERE name = '%s'
            """;

    // Same, with whitespace inside the quotes.
    // ok: sql-query-clause-splice
    // ruleid: sql-query-format-slot
    static final String VALUE_SLOT_SPACED_QUOTES = """
            SELECT count() AS cnt
            FROM system.clusters
            WHERE cluster = ' %s '
            """;

    // Projection: the slot names a column rather than splicing a predicate.
    // ok: sql-query-clause-splice
    // ruleid: sql-query-format-slot
    static final String PROJECTION_SLOT = """
            SELECT %s AS value
            FROM spans_local_v2
            WHERE workspace_id = :workspace_id
            """;

    // A whole-CTE prefix placeholder, filled at class init from a constant. A StringTemplate
    // attribute expresses this without formatting the query text.
    // ok: sql-query-clause-splice
    // ruleid: sql-query-format-slot
    static final String PREFIX_SPLICE = """
            %s
            SELECT bucket
            FROM spans_filtered
            """;

    // ---------------------------------------------------------------------------
    // FLAGGED BY NEITHER — correctly parameterized SQL, and strings that only look like it.
    // ---------------------------------------------------------------------------

    // ok: sql-query-clause-splice,sql-query-format-slot
    static final String FULLY_BOUND = """
            SELECT id
            FROM spans
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            """;

    // Not SQL: a log message that happens to contain the word "update".
    // ok: sql-query-clause-splice,sql-query-format-slot
    static final String LOG_MESSAGE = "Completed bulk update for '%s' dataset items";

    // Not SQL: an LLM prompt containing the jq expression `..|select(.error_info?)`.
    // Prompt templates carry their own injection risk, tracked separately in OPIK 7837 —
    // it is not a SQL concern and this rule is not the right gate for it.
    // ok: sql-query-clause-splice,sql-query-format-slot
    static final String PROMPT_WITH_JQ = """
            Use `%s` to inspect the trace.
            Filter with `..|select(.error_info?)` to find errors.
            Results come from the span tree, not from the top-level input.
            """;

    // Real SQL date format, not an integer slot — why both rules match %s only, never %d.
    // ok: sql-query-clause-splice,sql-query-format-slot
    static final String DATE_FORMAT_NOT_A_SLOT = """
            SELECT value
            FROM metadata
            WHERE STR_TO_DATE(value, '%Y-%m-%d') < CURDATE()
            """;
}
