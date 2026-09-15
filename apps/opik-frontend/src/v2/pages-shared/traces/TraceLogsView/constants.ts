/**
 * URL and storage keys for the trace-logs view, kept apart from the view itself so consumers that
 * only wire up navigation — the sidebar trigger and its controls hook — don't pull the table's
 * dependency graph into their route chunk.
 */
export const TLS_QUERY_PREFIX = "tls_";
export const TLS_STORAGE_PREFIX = "tls-traces-";
