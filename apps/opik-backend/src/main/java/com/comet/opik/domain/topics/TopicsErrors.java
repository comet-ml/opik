package com.comet.opik.domain.topics;

import java.sql.SQLException;

/** How topics code logs an error whose message can hold trace text. */
final class TopicsErrors {

    private TopicsErrors() {
    }

    /**
     * The type, and a SQL error's state and code, never the message: a Jackson, Jdbi or ClickHouse error can quote the
     * answer, the bound arguments or the query, which can hold trace text.
     */
    static String formatError(Throwable throwable) {
        return throwable instanceof SQLException sql
                ? "%s(state %s, code %s)".formatted(sql.getClass().getName(), sql.getSQLState(), sql.getErrorCode())
                : throwable.getClass().getName();
    }
}
