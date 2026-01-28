import React, { useState } from "react";
import { z } from "zod";
import {
  DynamicString,
  NullableDynamicString,
  NullableDynamicBoolean,
} from "@/lib/data-view";
import { MarkdownPreview } from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { Button } from "@/components/ui/button";

// ============================================================================
// TYPES
// ============================================================================

export interface TextBlockWidgetProps {
  content: string;
  label?: string | null;
  maxLines?: number | null;
  expandable?: boolean | null;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const textBlockWidgetConfig = {
  type: "TextBlock" as const,
  category: "block" as const,
  schema: z.object({
    content: DynamicString.describe("Text content to display"),
    label: NullableDynamicString.describe(
      "Optional label above the content block (e.g., 'Input', 'Output')",
    ),
    maxLines: z
      .number()
      .nullable()
      .optional()
      .describe("Maximum lines to show before truncation"),
    expandable: NullableDynamicBoolean.describe(
      "Whether to show expand/collapse control",
    ),
  }),
  description:
    "Primary text display for input/output content. Renders markdown. Use label prop for 'Input'/'Output' labels. Always use for trace view input/output.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * TextBlockWidget - Primary I/O content display
 *
 * Figma reference: Node 245-14061
 * Styles:
 * - Label: comet-body-s, text-foreground (e.g., "Input", "Output")
 * - Content area: bg-slate-50, rounded-md, px-4 py-3
 * - Content: Rendered as markdown
 *
 * Constraints:
 * - Always use for input and output for trace view
 * - Use label prop for "Input"/"Output" labels
 */
export const TextBlockWidget: React.FC<TextBlockWidgetProps> = ({
  content,
  label,
  maxLines,
  expandable = false,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);

  if (!content) return null;

  // Determine if truncation should be applied
  const shouldTruncate = maxLines && maxLines > 0 && !isExpanded;

  // Line clamp style for truncation
  const lineClampStyle: React.CSSProperties = shouldTruncate
    ? {
        display: "-webkit-box",
        WebkitLineClamp: maxLines,
        WebkitBoxOrient: "vertical",
        overflow: "hidden",
      }
    : {};

  // Content area styling
  const contentClasses = "rounded-md bg-slate-50 px-4 py-3";

  return (
    <div className="flex flex-col">
      {label && (
        <div className="py-2">
          <span className="comet-body-s text-foreground">{label}</span>
        </div>
      )}
      <div className={contentClasses}>
        <div style={lineClampStyle}>
          <MarkdownPreview className="comet-body-s text-foreground">
            {content}
          </MarkdownPreview>
        </div>
        {expandable && maxLines && maxLines > 0 && (
          <Button
            variant="ghost"
            size="3xs"
            onClick={() => setIsExpanded(!isExpanded)}
            className="mt-2 h-auto p-0 underline"
          >
            {isExpanded ? "Show less" : "Show more"}
          </Button>
        )}
      </div>
    </div>
  );
};

export default TextBlockWidget;
