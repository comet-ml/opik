import React from "react";
import { z } from "zod";
import { Link, useParams } from "@tanstack/react-router";
import { ExternalLink } from "lucide-react";
import { DynamicString, NullableDynamicString } from "@/lib/data-view";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

// ============================================================================
// TYPES
// ============================================================================

export type LinkButtonType = "trace" | "span";

export interface LinkButtonWidgetProps {
  type: LinkButtonType;
  id: string;
  label?: string | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const linkButtonWidgetConfig = {
  type: "LinkButton" as const,
  category: "inline" as const,
  schema: z.object({
    type: z.enum(["trace", "span"]).describe("Link target type"),
    id: DynamicString.describe("Trace or span ID"),
    label: NullableDynamicString.describe("Optional label (defaults to type)"),
  }),
  description:
    "First-class navigation link to a trace or span. Only valid when a trace/span ID exists. Should not be used inside Input/Output blocks.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * LinkButtonWidget - First-class trace/span navigation link
 *
 * Figma reference: Node 239-15707
 * Style:
 * - 14px Regular Inter, #373D4D (foreground)
 * - height: 24px
 * - gap: 4px between text and icon
 * - icon: external-link (14x14)
 *
 * Rules:
 * - Only valid when a trace/span ID exists
 * - Should not be used inside Input/Output blocks
 *
 * Navigation:
 * - Uses React Router's Link for SPA navigation (no full page reload)
 * - Navigates to the traces page with trace/span ID as search params
 */
export const LinkButtonWidget: React.FC<LinkButtonWidgetProps> = ({
  type,
  id,
  label,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { projectId } = useParams({ strict: false }) as { projectId?: string };

  if (!id) return null;

  const displayLabel = label ?? (type === "trace" ? "View Trace" : "View Span");

  // Build search params for trace/span selection
  // The traces page uses ?trace=<id> and ?span=<id> query params
  const searchParams: Record<string, string> =
    type === "trace" ? { trace: id } : { span: id };

  // If we don't have project context, fall back to a disabled button
  if (!projectId) {
    return (
      <Button
        variant="tableLink"
        size="2xs"
        disabled
        className="w-fit no-underline"
      >
        {displayLabel}
        <ExternalLink className="ml-1 size-3.5 shrink-0" />
      </Button>
    );
  }

  return (
    <Button
      variant="tableLink"
      size="2xs"
      asChild
      className="w-fit no-underline"
    >
      <Link
        to="/$workspaceName/projects/$projectId/traces"
        params={{
          workspaceName,
          projectId,
        }}
        search={searchParams}
      >
        {displayLabel}
        <ExternalLink className="ml-1 size-3.5 shrink-0" />
      </Link>
    </Button>
  );
};

export default LinkButtonWidget;
