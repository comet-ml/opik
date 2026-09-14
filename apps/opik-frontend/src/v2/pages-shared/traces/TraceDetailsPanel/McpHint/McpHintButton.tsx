import React from "react";

import { cn } from "@/lib/utils";
import { MCP_HINT_LABEL } from "./constants";

// The Ollie pill, same palette as the Explain affordance and the MCP
// announcement banner: amber into orange, with the Ollie shadow.
const PILL_CLASS = cn(
  "pointer-events-auto flex h-5 shrink-0 items-center rounded-full border px-1.5",
  "border-[var(--color-ollie)] text-white shadow-[var(--shadow-ollie)]",
  "bg-[linear-gradient(-45deg,var(--color-ollie-amber)_0%,var(--color-ollie)_100%)]",
  "transition-opacity hover:opacity-90",
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-ollie)] focus-visible:ring-offset-1",
);

const McpHintButton: React.FunctionComponent = () => (
  <button type="button" className={PILL_CLASS} data-testid="mcp-hint-button">
    {/* Wrapped rather than bare so a page translator cannot re-parent the text
        node out from under React (see the frontend browser-translation note). */}
    <span className="whitespace-nowrap font-mono text-[10px] leading-3">
      {MCP_HINT_LABEL}
    </span>
  </button>
);

export default McpHintButton;
