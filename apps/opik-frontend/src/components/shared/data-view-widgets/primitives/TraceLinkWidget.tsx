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
  label?: string | null;
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
    label: NullableDynamicString.describe("Optional label prefix"),
  }),
  description: "Opens a trace in a new window. AI provides only the trace ID.",
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
  label,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { projectId } = useParams({ strict: false }) as { projectId?: string };

  if (!traceId || !projectId) return null;

  const href = generateTracesURL(workspaceName, projectId, "traces", traceId);
  const displayText = text || "View Trace";

  return (
    <span className="inline-flex items-center gap-2">
      {label && <span className="comet-body-s text-muted-slate">{label}</span>}
      <Button variant="tableLink" size="sm" asChild className="px-0">
        <a href={href} target="_blank" rel="noopener noreferrer">
          {displayText}
          <SquareArrowOutUpRight className="ml-1.5 size-3.5 shrink-0" />
        </a>
      </Button>
    </span>
  );
};

export default TraceLinkWidget;
