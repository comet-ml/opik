import React from "react";
import { z } from "zod";
import { Braces, Check, Database, Bot } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  DynamicString,
  DynamicBoolean,
  NullableDynamicString,
} from "@/lib/data-view";
import { CollapsibleSection } from "@/components/shared/CollapsibleSection";

// ============================================================================
// TYPES
// ============================================================================

export type Level2IconType = "tool" | "retrieval" | "generation" | null;
export type Level2StatusType = "success" | "error" | "pending" | null;

export interface Level2ContainerWidgetProps {
  summary: string;
  defaultOpen?: boolean;
  icon: Level2IconType;
  status?: Level2StatusType;
  duration?: string | null;
  children?: React.ReactNode;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const level2ContainerWidgetConfig = {
  type: "Level2Container" as const,
  category: "container" as const,
  schema: z.object({
    summary: DynamicString.describe(
      "Summary text displayed in collapsed state",
    ),
    defaultOpen: DynamicBoolean.optional().describe(
      "Whether the container is open by default",
    ),
    icon: z
      .enum(["tool", "retrieval", "generation"])
      .nullable()
      .describe(
        "Icon type for visual identification (tool/retrieval/generation). Required - LLM must choose an appropriate icon or explicitly set to null.",
      ),
    status: z
      .enum(["success", "error", "pending"])
      .nullable()
      .optional()
      .describe("Status indicator (success shows checkmark, error shows X)"),
    duration: NullableDynamicString.describe(
      "Duration text displayed on the right (e.g., '42ms')",
    ),
  }),
  description:
    "Collapsible detail disclosure for spans, tools, system internals. " +
    "Text widgets inside must use default styling (no variant prop).",
};

// ============================================================================
// ICON STYLES (using CSS variables for consistency with PrettyLLMMessage)
// ============================================================================

const iconBackgrounds: Record<NonNullable<Level2IconType>, string> = {
  tool: "bg-[var(--tag-turquoise-bg)]",
  retrieval: "bg-[var(--tag-blue-bg)]",
  generation: "bg-[var(--tag-yellow-bg)]",
};

const iconColors: Record<NonNullable<Level2IconType>, string> = {
  tool: "text-[var(--tag-turquoise-text)]",
  retrieval: "text-[var(--tag-blue-text)]",
  generation: "text-[var(--tag-yellow-text)]",
};

const IconComponents: Record<
  NonNullable<Level2IconType>,
  React.FC<{ className?: string }>
> = {
  tool: Braces,
  retrieval: Database,
  generation: Bot,
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * Level2Container - Collapsible detail disclosure
 *
 * Uses CollapsibleSection for consistent styling with PrettyLLMMessage.
 *
 * Styles:
 * - Header: flex row, gap-1, items-center, p-1, px-0
 * - Chevron: ChevronRight, 14px, rotates 90° on open
 * - Icon: 20px container, 12px icon, rounded-sm
 * - Summary text: comet-body-s-accented, text-light-slate
 * - Status: checkmark for success (green), X for error (red)
 * - Duration: right-aligned, comet-body-xs, text-light-slate
 * - Content: ml-[6px] border-l pl-[12px] space-y-3
 */
export const Level2ContainerWidget: React.FC<Level2ContainerWidgetProps> = ({
  summary,
  defaultOpen = false,
  icon,
  status,
  duration,
  children,
}) => {
  const itemValue = "level2-item";
  const IconComponent = icon ? IconComponents[icon] : null;

  return (
    <CollapsibleSection.Container
      type="single"
      collapsible
      defaultValue={defaultOpen ? itemValue : undefined}
      className="w-full"
    >
      <CollapsibleSection.Root value={itemValue}>
        <CollapsibleSection.Header
          leftContent={
            <>
              {/* Icon indicator */}
              {icon && IconComponent && (
                <div
                  className={cn(
                    "flex size-5 shrink-0 items-center justify-center rounded-sm",
                    iconBackgrounds[icon],
                  )}
                >
                  <IconComponent className={cn("size-3", iconColors[icon])} />
                </div>
              )}

              {/* Summary text */}
              <span className="comet-body-s-accented flex-1 text-light-slate">
                {summary}
              </span>
            </>
          }
          rightContent={
            (status || duration) && (
              <div className="flex items-center gap-1">
                {/* Status indicator */}
                {status === "success" && (
                  <Check className="size-3.5 shrink-0 text-green-500" />
                )}
                {status === "error" && (
                  <span className="size-3.5 shrink-0 text-red-500">
                    &#10005;
                  </span>
                )}
                {/* Duration */}
                {duration && (
                  <span className="comet-body-xs shrink-0 text-light-slate">
                    {duration}
                  </span>
                )}
              </div>
            )
          }
        />
        <CollapsibleSection.Content>{children}</CollapsibleSection.Content>
      </CollapsibleSection.Root>
    </CollapsibleSection.Container>
  );
};

export default Level2ContainerWidget;
