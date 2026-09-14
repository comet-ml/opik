import React, { useEffect, useRef } from "react";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpHintButton from "./McpHintButton";
import useDelayedReveal from "./useDelayedReveal";
import useMcpInstallMode from "./useMcpInstallMode";
import { McpHintEntityType } from "./constants";

type McpHintRailProps = {
  isErrorExpanded: boolean;
  /** Identifies the failure the hint is about: a new one has to earn its own reveal. */
  subject: string;
  entityType: McpHintEntityType;
  traceId: string;
  projectId: string;
};

/**
 * The rail the hint lives in: an overlay pinned directly below the sticky
 * inspect bar, right-aligned.
 *
 * Overlay rather than a row in the scroll flow, for two reasons — it stays put
 * while a long traceback scrolls under it, and it cannot shift the layout when
 * it appears, which the reveal would otherwise do a second after the user has
 * started reading.
 */
const McpHintRail: React.FunctionComponent<McpHintRailProps> = ({
  isErrorExpanded,
  subject,
  entityType,
  traceId,
  projectId,
}) => {
  const isRevealed = useDelayedReveal({ active: isErrorExpanded, subject });
  const installMode = useMcpInstallMode();

  // One impression per reveal. The flag is cleared when the reveal is retracted
  // (a new subject), so the next one counts again.
  const hasTrackedImpressionRef = useRef(false);
  useEffect(() => {
    if (!isRevealed) {
      hasTrackedImpressionRef.current = false;
      return;
    }
    if (hasTrackedImpressionRef.current) return;

    hasTrackedImpressionRef.current = true;
    trackEvent(OpikEvent.MCP_BUTTON_SHOWN, {
      entity_type: entityType,
      install_mode: installMode,
    });
  }, [isRevealed, entityType, installMode]);

  if (!isRevealed) return null;

  return (
    <div className="pointer-events-none absolute right-4 top-4 z-10 flex justify-end motion-safe:animate-in motion-safe:fade-in">
      <McpHintButton traceId={traceId} projectId={projectId} />
    </div>
  );
};

export default McpHintRail;
