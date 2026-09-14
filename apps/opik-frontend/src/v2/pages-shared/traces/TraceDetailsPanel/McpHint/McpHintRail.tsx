import React, { useState } from "react";

import McpHintButton from "./McpHintButton";
import { McpHintTarget } from "./types";

type McpHintRailProps = {
  isErrorOpen: boolean;
  target: McpHintTarget;
};

/**
 * The rail the hint lives in: an overlay pinned directly below the sticky
 * inspect bar, right-aligned.
 *
 * Overlay rather than a row in the scroll flow, for two reasons — it stays put
 * while a long traceback scrolls under it, and it cannot shift the layout when
 * it appears.
 *
 * Opening the error is the ask, and it stands: closing the traceback again is
 * usually the moment the user turns to acting on it, so the hint stays. It is
 * the *node* that scopes it — the panel keys this component by the trace or
 * span being inspected, so moving to another one resets the latch, and the hint
 * has to be asked for there too.
 */
const McpHintRail: React.FunctionComponent<McpHintRailProps> = ({
  isErrorOpen,
  target,
}) => {
  const [hasBeenOpened, setHasBeenOpened] = useState(isErrorOpen);

  // React's own way to derive state from a prop without an effect: the set is
  // guarded, so it re-renders once and never loops.
  if (isErrorOpen && !hasBeenOpened) setHasBeenOpened(true);

  if (!hasBeenOpened) return null;

  return (
    <div className="pointer-events-none absolute right-4 top-4 z-10 flex justify-end motion-safe:duration-300 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-1">
      <McpHintButton target={target} />
    </div>
  );
};

export default McpHintRail;
