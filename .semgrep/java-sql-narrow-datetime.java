// Fixtures for java-sql-narrow-datetime.yaml. Run with:
//   semgrep test --config .semgrep/java-sql-narrow-datetime.yaml .semgrep/java-sql-narrow-datetime.java
//
// Semgrep's own annotations drive the assertions: a rule-id comment above a line marks one the
// rule MUST flag, and the negative form marks one it must NOT.
//
// The accepted cases matter as much as the rejected ones. Two of them are the reason the rule
// anchors on `\(`: `toDate32(` and `toDateTime64(` are the correct forms and share a prefix with
// the narrow ones, so a rule written without that anchor rejects the fix it is meant to enforce.
class JavaSqlNarrowDatetimeFixtures {

    // ---------------------------------------------------------------------------
    // REJECTED — a narrow conversion of an id-derived value.
    // ---------------------------------------------------------------------------

    // ruleid: sql-narrow-datetime-on-id
    static final String WEEK_BOUND_BOTH_OPERANDS = """
            SELECT id
            FROM spans
            WHERE workspace_id = :workspace_id
            AND id >= :uuid_from_time
            AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'))
            """;

    // The half-converted form: the column side is already Date32 and only the bound wraps. This is
    // the one a "convert the column" fix leaves behind, and on an equality it is worse than
    // leaving both — the two sides can then never agree for a far-future id.
    // ruleid: sql-narrow-datetime-on-id
    static final String WEEK_BOUND_ONLY_THE_BOUND_WRAPS = """
            SELECT id
            FROM traces
            WHERE id = :id
            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                = toMonday(UUIDv7ToDateTime(toUUID(:id), 'UTC'))
            """;

    // ruleid: sql-narrow-datetime-on-id
    static final String DATE_PROJECTION = """
            SELECT toDate(UUIDv7ToDateTime(toUUID(id))) AS day
            FROM traces
            WHERE workspace_id = :workspace_id
            GROUP BY day
            """;

    // ruleid: sql-narrow-datetime-on-id
    static final String DATETIME_BUCKETING = """
            SELECT countIf(error_info != '' AND toDateTime(UUIDv7ToDateTime(toUUID(t.id))) > now()) AS recent
            FROM traces t
            """;

    // A bare expression string passed as a StringTemplate attribute, not a whole statement. The
    // rule carries no SELECT/WITH guard precisely so this shape is still caught.
    // ruleid: sql-narrow-datetime-on-id
    static final String TEMPLATE_ATTRIBUTE = "toDateTime(UUIDv7ToDateTime(toUUID(:uuid_from_time)))";

    // ruleid: sql-narrow-datetime-on-id
    static final String NARROWED_PARTITION_COLUMN = """
            SELECT toDateTime(id_at) AS at
            FROM spans
            """;

    // A table-qualified column. These DAOs alias their tables (`t.id`, `s.span_time`), so the qualifier
    // has to be tolerated or the rule misses the most ordinary way of writing the same mistake.
    // ruleid: sql-narrow-datetime-on-id
    static final String QUALIFIED_COLUMN = """
            SELECT toMonday(t.id_at) AS week
            FROM traces t
            """;

    // Wrapped across lines, as the long nested bounds in these DAOs are.
    // ruleid: sql-narrow-datetime-on-id
    static final String SPLIT_ACROSS_LINES = """
            SELECT toDate(
                       UUIDv7ToDateTime(toUUID(id))) AS day
            FROM traces
            """;

    // ---------------------------------------------------------------------------
    // ACCEPTED — the wide forms, and narrow conversions of columns that are not id-derived.
    // ---------------------------------------------------------------------------

    // ok: sql-narrow-datetime-on-id
    static final String DATE32_WEEK_EXPRESSION = """
            SELECT id
            FROM spans
            WHERE workspace_id = :workspace_id
            AND id >= :uuid_from_time
            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'))
                    - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1)))
            """;

    // ok: sql-narrow-datetime-on-id
    static final String ADD_WEEKS_OVER_DATE32 = """
            DELETE FROM traces
            WHERE id \\< :cutoff_id
            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                \\< addWeeks(toDate32(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC'))
                    - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC'), 1)), 1)
            """;

    // ok: sql-narrow-datetime-on-id
    static final String DATE32_PROJECTION = """
            SELECT toDate32(UUIDv7ToDateTime(toUUID(id))) AS day
            FROM traces
            GROUP BY day
            """;

    // ok: sql-narrow-datetime-on-id
    static final String PINNED_DATETIME64_BUCKET = "toDateTime64(UUIDv7ToDateTime(toUUID(:uuid_from_time)), 0, 'UTC')";

    // ok: sql-narrow-datetime-on-id
    static final String PINNED_WITH_FILL_BOUND = """
            SELECT bucket, value
            FROM series
            ORDER BY bucket
            WITH FILL FROM :from
                TO toDateTime64(UUIDv7ToDateTime(toUUID(:uuid_to_time)), 0, 'UTC')
                STEP toIntervalDay(1)
            """;

    // Server-stamped columns are honest to 2299 and every value in them is ordinary, so narrowing
    // them is not this rule's concern.
    // ok: sql-narrow-datetime-on-id
    static final String NARROW_ON_SERVER_STAMPED_COLUMNS = """
            SELECT toDate(created_at), toDateTime(start_time), toMonday(last_updated_at)
            FROM traces
            """;

    // ok: sql-narrow-datetime-on-id
    static final String ID_RANGE_WITHOUT_ANY_CONVERSION = """
            SELECT id
            FROM spans
            WHERE id >= :uuid_from_time
            AND id \\<= :uuid_to_time
            """;
}
