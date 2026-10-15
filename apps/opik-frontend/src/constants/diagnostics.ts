// Mirrors the backend sweep's threshold: at this many traces in the window a project gets its
// automatic first diagnostic.
export const AUTO_FIRST_RUN_MIN_TRACES = 100;

// Mirrors the backend sweep's window: the automatic first run reads the traces of the 7 days before it is
// claimed, and the threshold above is counted over the same span.
export const AUTO_FIRST_RUN_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

// The longest an automatic run is expected to take. Not a backend limit, just a generous bound on runs
// that usually finish in minutes: past it, a run with no result is treated as lost, and a result that
// lands later is not attributed to it.
export const AUTO_RUN_MAX_DURATION_MS = 40 * 60 * 1000;
