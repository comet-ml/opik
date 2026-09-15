import React, { useEffect, useState } from "react";

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
  // A beat after the error opens, so the pill reads as a response to it rather
  // than as part of the same layout. Presentational, which is why the delay
  // lives here rather than in the state the panel owns.
  const [hasWaited, setHasWaited] = useState(false);
  useEffect(() => {
    if (!isVisible) {
      setHasWaited(false);
      return;
    }
    const timer = setTimeout(
      () => setHasWaited(true),
      MCP_HINT_REVEAL_DELAY_MS,
    );
    return () => clearTimeout(timer);
  }, [isVisible]);

  if (!isVisible || !hasWaited) return null;

  return (
    <div className="pointer-events-none absolute right-4 top-4 z-10 flex justify-end motion-safe:duration-300 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-1">
      <McpHintButton target={target} />
    </div>
  );
};

export default McpHintRail;
