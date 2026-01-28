// ============================================================================
// INLINE PRIMITIVE WIDGETS
// ============================================================================
// These are low-level inline widgets matching 1:1 with Figma designs.
// Each widget has a clear API/props and exported config for registry building.

import type { ComponentRenderProps, ComponentRegistry } from "@/lib/data-view";

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Safely converts a value to a displayable string.
 * - Objects/arrays are JSON stringified (compact for inline display)
 * - Strings are returned as-is
 * - null/undefined return empty string
 * - Other primitives are converted via String()
 */
function toDisplayString(value: unknown, compact = true): string {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "object") {
    try {
      return compact ? JSON.stringify(value) : JSON.stringify(value, null, 2);
    } catch {
      return "[Object]";
    }
  }
  return String(value);
}

/**
 * Checks if a value is JSON (object or array, not a string)
 */
function isJsonValue(value: unknown): boolean {
  return (
    value !== null &&
    typeof value === "object" &&
    !(value instanceof Date) &&
    !(value instanceof RegExp)
  );
}

// Components
export { TextWidget, textWidgetConfig } from "./TextWidget";
export type { TextWidgetProps } from "./TextWidget";

export { NumberWidget, numberWidgetConfig } from "./NumberWidget";
export type {
  NumberWidgetProps,
  NumberSize,
  NumberFormat,
} from "./NumberWidget";

export { LabelWidget, labelWidgetConfig } from "./LabelWidget";
export type { LabelWidgetProps } from "./LabelWidget";

export { TagWidget, tagWidgetConfig } from "./TagWidget";
export type { TagWidgetProps, TagVariant } from "./TagWidget";

export { BoolWidget, boolWidgetConfig } from "./BoolWidget";
export type { BoolWidgetProps, BoolStyle } from "./BoolWidget";

export { BoolChipWidget, boolChipWidgetConfig } from "./BoolChipWidget";
export type { BoolChipWidgetProps } from "./BoolChipWidget";

export { LinkButtonWidget, linkButtonWidgetConfig } from "./LinkButtonWidget";
export type { LinkButtonWidgetProps, LinkButtonType } from "./LinkButtonWidget";

export { LinkWidget, linkWidgetConfig } from "./LinkWidget";
export type { LinkWidgetProps } from "./LinkWidget";

export { TraceLinkWidget, traceLinkWidgetConfig } from "./TraceLinkWidget";
export type { TraceLinkWidgetProps } from "./TraceLinkWidget";

export { ThreadLinkWidget, threadLinkWidgetConfig } from "./ThreadLinkWidget";
export type { ThreadLinkWidgetProps } from "./ThreadLinkWidget";

// ============================================================================
// AGGREGATED CONFIGS (for catalog building)
// ============================================================================

import { textWidgetConfig } from "./TextWidget";
import { numberWidgetConfig } from "./NumberWidget";
import { labelWidgetConfig } from "./LabelWidget";
import { tagWidgetConfig } from "./TagWidget";
import { boolWidgetConfig } from "./BoolWidget";
import { boolChipWidgetConfig } from "./BoolChipWidget";
import { linkButtonWidgetConfig } from "./LinkButtonWidget";
import { linkWidgetConfig } from "./LinkWidget";
import { traceLinkWidgetConfig } from "./TraceLinkWidget";
import { threadLinkWidgetConfig } from "./ThreadLinkWidget";

export const inlineWidgetConfigs = {
  Text: textWidgetConfig,
  Number: numberWidgetConfig,
  Label: labelWidgetConfig,
  Tag: tagWidgetConfig,
  Bool: boolWidgetConfig,
  BoolChip: boolChipWidgetConfig,
  LinkButton: linkButtonWidgetConfig,
  Link: linkWidgetConfig,
  TraceLink: traceLinkWidgetConfig,
  ThreadLink: threadLinkWidgetConfig,
} as const;

// ============================================================================
// AGGREGATED REGISTRY (for rendering)
// ============================================================================

import { TextWidget } from "./TextWidget";
import { NumberWidget } from "./NumberWidget";
import { LabelWidget } from "./LabelWidget";
import { TagWidget } from "./TagWidget";
import { BoolWidget } from "./BoolWidget";
import { BoolChipWidget } from "./BoolChipWidget";
import { LinkButtonWidget } from "./LinkButtonWidget";
import { LinkWidget } from "./LinkWidget";
import { TraceLinkWidget } from "./TraceLinkWidget";
import { ThreadLinkWidget } from "./ThreadLinkWidget";

function createTextRenderer({ props }: ComponentRenderProps) {
  const value = props.value;

  // DEBUG: Log when Text widget receives non-string content
  if (isJsonValue(value)) {
    console.debug(
      "[Text] Received JSON value - consider using Code widget instead:",
      { value },
    );
  }

  return TextWidget({
    value: toDisplayString(value),
    variant: props.variant as "body" | "bold" | "caption" | undefined,
    truncate: Boolean(props.truncate),
    monospace: isJsonValue(value) ? true : Boolean(props.monospace),
  });
}

function createNumberRenderer({ props }: ComponentRenderProps) {
  return NumberWidget({
    value: props.value as number | null,
    label: props.label as string | null | undefined,
    size: props.size as "xs" | "sm" | "md" | "lg" | "xl" | undefined,
    format: props.format as "decimal" | "percent" | "currency" | undefined,
  });
}

function createLabelRenderer({ props }: ComponentRenderProps) {
  const text = props.text;

  // DEBUG: Log empty labels
  if (!text) {
    console.debug("[Label] Received empty text prop");
  }

  return LabelWidget({
    text: toDisplayString(text),
  });
}

function createTagRenderer({ props }: ComponentRenderProps) {
  return TagWidget({
    label: String(props.label ?? ""),
    variant: props.variant as
      | "default"
      | "error"
      | "warning"
      | "success"
      | "info"
      | undefined,
  });
}

function createBoolRenderer({ props }: ComponentRenderProps) {
  return BoolWidget({
    value: props.value as boolean | null,
    style: props.style as "check" | "text" | undefined,
  });
}

function createBoolChipRenderer({ props }: ComponentRenderProps) {
  return BoolChipWidget({
    value: props.value as boolean | null,
    trueLabel: props.trueLabel as string | null | undefined,
    falseLabel: props.falseLabel as string | null | undefined,
  });
}

function createLinkButtonRenderer({ props }: ComponentRenderProps) {
  return LinkButtonWidget({
    type: props.type as "trace" | "span",
    id: String(props.id ?? ""),
    label: props.label as string | null | undefined,
  });
}

function createLinkRenderer({ props }: ComponentRenderProps) {
  return LinkWidget({
    url: String(props.url ?? ""),
    text: props.text as string | null | undefined,
    label: props.label as string | null | undefined,
  });
}

function createTraceLinkRenderer({ props }: ComponentRenderProps) {
  return TraceLinkWidget({
    traceId: String(props.traceId ?? ""),
    text: props.text as string | null | undefined,
    label: props.label as string | null | undefined,
  });
}

function createThreadLinkRenderer({ props }: ComponentRenderProps) {
  return ThreadLinkWidget({
    threadId: String(props.threadId ?? ""),
    text: props.text as string | null | undefined,
    label: props.label as string | null | undefined,
  });
}

export const inlineWidgetRegistry: ComponentRegistry = {
  Text: createTextRenderer,
  Number: createNumberRenderer,
  Label: createLabelRenderer,
  Tag: createTagRenderer,
  Bool: createBoolRenderer,
  BoolChip: createBoolChipRenderer,
  LinkButton: createLinkButtonRenderer,
  Link: createLinkRenderer,
  TraceLink: createTraceLinkRenderer,
  ThreadLink: createThreadLinkRenderer,
};
