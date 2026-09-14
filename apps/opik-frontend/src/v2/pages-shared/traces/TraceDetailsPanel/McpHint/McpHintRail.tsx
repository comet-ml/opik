import React, { useEffect, useRef } from "react";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpHintButton from "./McpHintButton";
import useRevealOnExpand from "./useRevealOnExpand";
import useMcpInstallMode from "./useMcpInstallMode";
import { McpHintTarget } from "./types";

type McpHintRailProps = {
  isErrorExpanded: boolean;
  /** Identifies the failure the hint is about: a new one has to earn its own reveal. */
  subject: string;
  target: McpHintTarget;
};

/**
 * The rail the hint lives in: an overlay pinned directly below the sticky
 * inspect bar, right-aligned.
 *
 * Overlay rather than a row in the scroll flow, for two reasons — it stays put
 * while a long traceback scrolls under it, and it cannot shift the layout when
 * it appears or goes away.
 */
const McpHintRail: React.FunctionComponent<McpHintRailProps> = ({
  isErrorExpanded,
  subject,
  target,
}) => {
  const isRevealed = useRevealOnExpand({ active: isErrorExpanded, subject });
  const installMode = useMcpInstallMode();

  // One impression per reveal. The flag is cleared when the hint goes away, so
  // reopening the error counts again.
  const hasTrackedImpressionRef = useRef(false);
  useEffect(() => {
    if (!isRevealed) {
      hasTrackedImpressionRef.current = false;
      return;
    }
    if (hasTrackedImpressionRef.current) return;

    hasTrackedImpressionRef.current = true;
    trackEvent(OpikEvent.MCP_BUTTON_SHOWN, {
      entity_type: target.entityType,
      install_mode: installMode,
    });
  }, [isRevealed, target.entityType, installMode]);

  if (!isRevealed) return null;

  return (
    <div className="pointer-events-none absolute right-4 top-4 z-10 flex justify-end motion-safe:duration-300 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-1">
      <McpHintButton target={target} />
    </div>
  );
};

export default McpHintRail;
