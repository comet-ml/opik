import React from "react";
import { z } from "zod";
import { useParams } from "@tanstack/react-router";
import { SquareArrowOutUpRight } from "lucide-react";
import { DynamicString, NullableDynamicString } from "@/lib/data-view";
import { generateTracesURL } from "@/lib/annotation-queues";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

// ============================================================================
// TYPES
// ============================================================================

export interface TraceLinkWidgetProps {
  traceId: string;
  text?: string | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const traceLinkWidgetConfig = {
  type: "TraceLink" as const,
  category: "inline" as const,
  schema: z.object({
    traceId: DynamicString.describe("The trace ID to link to"),
    text: NullableDynamicString.describe(
      "Display text (defaults to 'View Trace')",
    ),
  }),
  description:
    "Opens a trace in a new browser tab. Use in trace context when contextType is 'trace'. Bind traceId to /id.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * TraceLinkWidget - Opens a trace in a new window
 *
 * Style:
 * - Uses Button with tableLink variant (foreground text color)
 * - icon: square-arrow-out-up-right (14x14)
 *
 * Navigation:
 * - Opens in new window with target="_blank"
 * - Uses generateTracesURL to construct the full URL
 */
export const TraceLinkWidget: React.FC<TraceLinkWidgetProps> = ({
  traceId,
  text,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { projectId } = useParams({ strict: false }) as { projectId?: string };

  if (!traceId || !projectId) return null;

  const href = generateTracesURL(workspaceName, projectId, "traces", traceId);
  const displayText = text || "View Trace";

  return (
    <Button
      variant="tableLink"
      size="2xs"
      asChild
      className="w-fit no-underline"
    >
      <a href={href} target="_blank" rel="noopener noreferrer">
        {displayText}
        <SquareArrowOutUpRight className="ml-1 size-3.5 shrink-0" />
      </a>
    </Button>
  );
};

export default TraceLinkWidget;
