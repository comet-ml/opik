import React from "react";

import McpHintButton from "./McpHintButton";
import { MCP_HINT_REVEAL_DELAY_MS } from "./constants";
import { McpHintTarget } from "./types";

type McpHintRailProps = {
  isVisible: boolean;
  target: McpHintTarget;
};

// An overlay rather than a row in the scroll flow: it stays put while a long
// traceback scrolls under it, and cannot shift the layout when it appears.
const McpHintRail: React.FunctionComponent<McpHintRailProps> = ({
  isVisible,
  target,
}) => {
  if (!isVisible) return null;

  return (
    <div
      // A beat after the error opens, so the pill reads as a response to it
      // rather than as part of the same layout. The delay is the animation's
      // own, held at its first frame by fill-mode backwards, which costs no
      // state and no render: a timer here would only re-render to say "now".
      // Both sit inside motion-safe, so nobody who turned animation off waits.
      className="pointer-events-none absolute right-4 top-4 z-10 flex justify-end motion-safe:duration-300 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-1 motion-safe:[animation-delay:var(--mcp-hint-reveal-delay)] motion-safe:[animation-fill-mode:backwards]"
      style={
        {
          "--mcp-hint-reveal-delay": `${MCP_HINT_REVEAL_DELAY_MS}ms`,
        } as React.CSSProperties
      }
    >
      <McpHintButton target={target} />
    </div>
  );
};

export default McpHintRail;
