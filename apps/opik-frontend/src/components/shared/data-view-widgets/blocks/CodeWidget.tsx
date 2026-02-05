import React from "react";
import { z } from "zod";
import {
  DynamicString,
  NullableDynamicString,
  NullableDynamicBoolean,
} from "@/lib/data-view";
import BaseCodeMirrorBlock from "@/components/shared/CodeMirror/BaseCodeMirrorBlock";

// ============================================================================
// TYPES
// ============================================================================

export interface CodeWidgetProps {
  content: string;
  language?: string | null;
  label?: string | null;
  wrap?: boolean;
  showCopy?: boolean;
}

// ============================================================================
// CONFIG (for registry building)
// ============================================================================

export const codeWidgetConfig = {
  type: "Code" as const,
  category: "block" as const,
  schema: z.object({
    content: DynamicString.describe("Code content to display"),
    language: NullableDynamicString.describe(
      "Language hint (e.g., json, python, javascript)",
    ),
    label: NullableDynamicString.describe(
      "Label for the code block (e.g., Function Input)",
    ),
    wrap: NullableDynamicBoolean.describe(
      "Enable line wrapping vs horizontal scroll",
    ),
    showCopy: NullableDynamicBoolean.describe("Show copy button in header"),
  }),
  description:
    "Syntax highlighted code block with copy functionality. Line numbers are always shown.",
};

// ============================================================================
// COMPONENT
// ============================================================================

/**
 * CodeWidget - Syntax highlighted code display
 *
 * Figma reference: Node 239-15130
 * Uses the shared BaseCodeMirrorBlock component for consistent code rendering.
 *
 * Styles:
 * - Border: #E2E8F0 (border-border), rounded-md
 * - Background: #F8FAFC (bg-primary-foreground)
 * - Header: border-b, 12px Inter Regular #64748B (comet-body-xs text-muted-slate)
 * - Line numbers: 15px Ubuntu Mono, #94A3B8 (text-light-slate)
 * - Code: 15px Ubuntu Mono, #374151
 *
 * Props:
 * - content: Code string to display
 * - language: Optional language hint (for syntax highlighting)
 * - label: Header label (e.g., "Function Input")
 * - wrap: Enable line wrapping (default: false, horizontal scroll)
 * - showCopy: Show copy button (default: true)
 *
 * Note: Line numbers are always shown.
 */
export const CodeWidget: React.FC<CodeWidgetProps> = ({
  content,
  language,
  label,
  wrap = false,
  showCopy = true,
}) => {
  if (!content) return null;

  return (
    <BaseCodeMirrorBlock
      code={content}
      language={language}
      label={label}
      showLineNumbers={true}
      showCopy={showCopy}
      wrap={wrap}
      maxHeight="400px"
    />
  );
};

export default CodeWidget;
