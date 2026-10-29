package com.comet.opik.domain;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * One {@code system.query_log} entry of a free-form query, initial or shard-side: the {@code <db>.<table>} names it
 * read, and those its applied row policies cover, resolved through {@code system.row_policies}. The lists are copied,
 * so the evidence the post-run check reads cannot change underneath it.
 */
@Builder(toBuilder = true)
public record FreeFormSqlQueryLogEntry(boolean initial, @NonNull String user, @NonNull List<String> tables,
        @NonNull List<String> policyCoveredTables) {

    public FreeFormSqlQueryLogEntry {
        tables = List.copyOf(tables);
        policyCoveredTables = List.copyOf(policyCoveredTables);
    }
}
