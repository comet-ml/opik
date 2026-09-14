// The control is not offered because an error exists — it is offered because the
// user chose to open it. Landing a beat after that choice is what makes it read
// as a response rather than as furniture that was there all along.
export const MCP_HINT_REVEAL_DELAY_MS = 1000;

export const MCP_HINT_LABEL = "Fix via MCP";

/** Reported on the funnel events so trace-level and span-level failures stay separable. */
export type McpHintEntityType = "trace" | "span";
