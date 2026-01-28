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

export interface ThreadLinkWidgetProps {
  threadId: string;
  text?: string | null;
  label?: string | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const threadLinkWidgetConfig = {
  type: "ThreadLink" as const,
  category: "inline" as const,
  schema: z.object({
    threadId: DynamicString.describe("The thread ID to link to"),
    text: NullableDynamicString.describe(
      "Display text (defaults to 'View Thread')",
    ),
    label: NullableDynamicString.describe("Optional label prefix"),
  }),
  description:
    "Opens a thread in a new window. AI provides only the thread ID.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * ThreadLinkWidget - Opens a thread in a new window
 *
 * Style:
 * - Uses Button with tableLink variant (foreground text color)
 * - icon: square-arrow-out-up-right (14x14)
 *
 * Navigation:
 * - Opens in new window with target="_blank"
 * - Uses generateTracesURL with type "threads" to construct the full URL
 */
export const ThreadLinkWidget: React.FC<ThreadLinkWidgetProps> = ({
  threadId,
  text,
  label,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { projectId } = useParams({ strict: false }) as { projectId?: string };

  if (!threadId || !projectId) return null;

  const href = generateTracesURL(workspaceName, projectId, "threads", threadId);
  const displayText = text || "View Thread";

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

export default ThreadLinkWidget;
