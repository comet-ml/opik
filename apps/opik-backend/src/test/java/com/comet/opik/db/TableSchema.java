package com.comet.opik.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@code SHOW CREATE}-level snapshot of one ClickHouse table, read from the {@code system} tables so it reflects the
 * schema the server actually holds rather than the DDL someone believes it applied.
 *
 * <p>It carries every aspect a schema change can touch — columns (with their type, DEFAULT/MATERIALIZED kind and
 * expression, and compression codec), data-skipping indices, the sorting / primary / partition keys, projections, and
 * the engine — so a guard comparing two tables can assert on all of them instead of on column names alone. Parsing
 * {@code SHOW CREATE TABLE} text would carry the same information but compare formatting as well as substance; the
 * {@code system} tables give the same facts already decomposed.
 *
 * <p>{@code columns} keeps the server's declaration order ({@code system.columns.position}), which matters because two
 * tables can hold the same column set in a different order — the trace shard and its shadow do exactly that. Callers
 * comparing sets should go through {@link #columnNames()} / {@link #storedColumnNames()} rather than comparing the
 * lists.
 */
record TableSchema(
        String table,
        String engine,
        String partitionKey,
        String sortingKey,
        String primaryKey,
        List<Column> columns,
        List<SkipIndex> skipIndices,
        List<Projection> projections) {

    /**
     * @param defaultKind {@code DEFAULT}, {@code MATERIALIZED}, {@code ALIAS}, or empty when the column simply has no
     *            default. The distinction is load-bearing: only a non-{@code MATERIALIZED}/{@code ALIAS} column can be
     *            named in an {@code INSERT}, so it is what separates a column the cutover backfill must carry from one
     *            the destination recomputes for itself.
     */
    record Column(String name, String type, String defaultKind, String defaultExpression, String codec) {
    }

    record SkipIndex(String name, String typeFull, String expression, long granularity) {
    }

    record Projection(String name, String query) {
    }

    private static final Set<String> COMPUTED_DEFAULT_KINDS = Set.of("MATERIALIZED", "ALIAS");

    static TableSchema read(Connection connection, String database, String table) throws SQLException {
        var tableRow = readTableRow(connection, database, table);
        return new TableSchema(
                table,
                tableRow.get("engine_full"),
                tableRow.get("partition_key"),
                tableRow.get("sorting_key"),
                tableRow.get("primary_key"),
                readColumns(connection, database, table),
                readSkipIndices(connection, database, table),
                readProjections(connection, database, table));
    }

    /** Column names in the server's declaration order. */
    List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }

    /**
     * The columns an {@code INSERT} can name — everything except {@code MATERIALIZED} / {@code ALIAS}, which the server
     * computes and refuses to accept a value for.
     */
    Set<String> storedColumnNames() {
        return columns.stream()
                .filter(column -> !COMPUTED_DEFAULT_KINDS.contains(column.defaultKind()))
                .map(Column::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    Map<String, Column> columnsByName() {
        var byName = new LinkedHashMap<String, Column>();
        columns.forEach(column -> byName.put(column.name(), column));
        return byName;
    }

    Map<String, SkipIndex> skipIndicesByName() {
        var byName = new LinkedHashMap<String, SkipIndex>();
        skipIndices.forEach(index -> byName.put(index.name(), index));
        return byName;
    }

    Set<String> skipIndexNames() {
        return skipIndicesByName().keySet();
    }

    Set<String> projectionNames() {
        var names = new LinkedHashSet<String>();
        projections.forEach(projection -> names.add(projection.name()));
        return names;
    }

    boolean isDistributed() {
        return engine.startsWith("Distributed");
    }

    private static Map<String, String> readTableRow(Connection connection, String database, String table)
            throws SQLException {
        var sql = """
                SELECT engine_full, partition_key, sorting_key, primary_key
                FROM system.tables WHERE database = '%s' AND name = '%s'
                """.formatted(database, table);
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Table '%s.%s' does not exist".formatted(database, table));
            }
            return Map.of(
                    "engine_full", text(resultSet, "engine_full"),
                    "partition_key", text(resultSet, "partition_key"),
                    "sorting_key", text(resultSet, "sorting_key"),
                    "primary_key", text(resultSet, "primary_key"));
        }
    }

    private static List<Column> readColumns(Connection connection, String database, String table) throws SQLException {
        var sql = """
                SELECT name, type, default_kind, default_expression, compression_codec
                FROM system.columns WHERE database = '%s' AND table = '%s' ORDER BY position
                """.formatted(database, table);
        var columns = new ArrayList<Column>();
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                columns.add(new Column(
                        text(resultSet, "name"),
                        text(resultSet, "type"),
                        text(resultSet, "default_kind"),
                        text(resultSet, "default_expression"),
                        text(resultSet, "compression_codec")));
            }
        }
        return List.copyOf(columns);
    }

    private static List<SkipIndex> readSkipIndices(Connection connection, String database, String table)
            throws SQLException {
        var sql = """
                SELECT name, type_full, expr, granularity
                FROM system.data_skipping_indices WHERE database = '%s' AND table = '%s' ORDER BY name
                """.formatted(database, table);
        var indices = new ArrayList<SkipIndex>();
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                indices.add(new SkipIndex(
                        text(resultSet, "name"),
                        text(resultSet, "type_full"),
                        text(resultSet, "expr"),
                        resultSet.getLong("granularity")));
            }
        }
        return List.copyOf(indices);
    }

    private static List<Projection> readProjections(Connection connection, String database, String table)
            throws SQLException {
        var sql = """
                SELECT name, query FROM system.projections
                WHERE database = '%s' AND table = '%s' ORDER BY name
                """.formatted(database, table);
        var projections = new ArrayList<Projection>();
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                projections.add(new Projection(text(resultSet, "name"), text(resultSet, "query")));
            }
        }
        return List.copyOf(projections);
    }

    /** ClickHouse returns an absent String as {@code ""}; normalise the JDBC {@code null} case to match. */
    private static String text(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getString(column);
        return value == null ? "" : value;
    }
}
