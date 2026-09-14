import React from "react";

import McpHintButton from "./McpHintButton";
import { McpHintTarget } from "./types";

type McpHintRailProps = {
  isErrorExpanded: boolean;
  target: McpHintTarget;
};

/**
 * The rail the hint lives in: an overlay pinned directly below the sticky
 * inspect bar, right-aligned.
 *
 * Overlay rather than a row in the scroll flow, for two reasons — it stays put
 * while a long traceback scrolls under it, and it cannot shift the layout when
 * it appears or goes away.
 *
 * Shown for exactly as long as the error is open. Which error counts as open is
 * the panel's business, and it holds that per span, so the hint cannot turn up
 * on a failure nobody opened.
 */
const McpHintRail: React.FunctionComponent<McpHintRailProps> = ({
  isErrorExpanded,
  target,
}) => {
  if (!isErrorExpanded) return null;

  return (
    <div className="pointer-events-none absolute right-4 top-4 z-10 flex justify-end motion-safe:duration-300 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-1">
      <McpHintButton target={target} />
    </div>
  );
};

export default McpHintRail;
