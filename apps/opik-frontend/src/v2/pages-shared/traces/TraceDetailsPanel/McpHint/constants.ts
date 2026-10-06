// Grace for crossing the gap between the pill and the card.
export const MCP_HINT_CLOSE_DELAY_MS = 260;

// A beat between opening the error and the pill arriving, so the two read as
// cause and effect rather than as one layout.
export const MCP_HINT_REVEAL_DELAY_MS = 200;

// How long the copied feedback stays on a control before it goes back to
// offering the copy. Two seconds, as CodeBlockCopy and the rest of the product.
export const MCP_COPIED_FEEDBACK_MS = 2000;

// How long a confirmation stands before the card resolves it: closing, or —
// if the pointer is on the card, so somebody is reading — going back to the
// routes. Until then nothing dismisses it, because replacing the routes makes
// the card shorter, which can slide it out from under a pointer that never
// moved, and hand-off to another app takes the focus with it.
export const MCP_CONFIRMATION_DISMISS_MS = 3000;

export const MCP_HINT_LABEL = "Fix via MCP";

export const MCP_HINT_DOCS_PATH = "/mcp-server";

export const MCP_HINT_TITLE = "Opik MCP";

export const MCP_HINT_DESCRIPTION =
  "Instead of writing a script for each question, your agent reads Opik directly — and can fix the code in the same step.";

export const MCP_DEEPLINK_FALLBACK_NOTE =
  "Nothing opened? Use the prompt instead:";

export const MCP_TILES_LABEL = "Set it up for:";

export const MCP_PROMPT_PITCH = "Using different agent?";

export const MCP_PROMPT_ACTION = "Copy prompt";

export const MCP_COPIED = "Copied";
