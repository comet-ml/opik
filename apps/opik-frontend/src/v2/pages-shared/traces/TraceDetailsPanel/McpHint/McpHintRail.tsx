import React from "react";

import McpHintButton from "./McpHintButton";
import { MCP_HINT_REVEAL_DELAY_MS } from "./constants";
import { McpHintTarget } from "./types";

type McpHintRailProps = {
  isVisible: boolean;
  target: McpHintTarget;
};

/**
 * Where the pill sits: the right end of the header row, as the design has it.
 *
 * The viewer hands this to a sticky layer that carries no height, so the pill
 * holds that spot and stays reachable once an expanded error scrolls the row
 * away. The layer ignores pointer events; the pill takes them back here.
 */
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
      className="pointer-events-auto flex shrink-0 justify-end motion-safe:duration-300 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-1 motion-safe:[animation-delay:var(--mcp-hint-reveal-delay)] motion-safe:[animation-fill-mode:backwards]"
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
