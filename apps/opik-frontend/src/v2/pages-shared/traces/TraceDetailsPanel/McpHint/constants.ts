// The control is not offered because an error exists — it is offered because the
// user chose to open it. Landing a beat after that choice is what makes it read
// as a response rather than as furniture that was there all along.
export const MCP_HINT_REVEAL_DELAY_MS = 1000;

// Long enough to cross the gap between the button and the popover, short enough
// that a popover the user has walked away from does not linger.
export const MCP_HINT_HOVER_GRACE_MS = 260;

export const MCP_HINT_LABEL = "Fix via MCP";

export const MCP_HINT_DOCS_PATH = "/mcp-server";

export const MCP_HINT_TITLE = "Opik MCP";

export const MCP_HINT_DESCRIPTION =
  "Instead of writing a script for each question, your agent reads Opik directly — and can fix the code in the same step.";
