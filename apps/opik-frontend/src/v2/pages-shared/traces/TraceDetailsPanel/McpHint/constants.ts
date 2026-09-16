// Grace for crossing the gap between the pill and the card.
export const MCP_HINT_CLOSE_DELAY_MS = 260;

// A beat between opening the error and the pill arriving, so the two read as
// cause and effect rather than as one layout.
export const MCP_HINT_REVEAL_DELAY_MS = 200;

// How long a copy says so before going back to offering itself. Two seconds,
// the same as CodeBlockCopy and every other copy in the product.
export const MCP_COPIED_FEEDBACK_MS = 2000;

// How long a deeplink's confirmation holds the card. Replacing the routes with
// it makes the card shorter, which can slide it out from under a pointer that
// has not moved, and the pointer-leave that follows would dismiss what the user
// just asked for. After this, hover governs the card again.
export const MCP_CONFIRMATION_HOLD_MS = 2000;

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
