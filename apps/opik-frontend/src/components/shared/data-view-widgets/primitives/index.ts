// ============================================================================
// INLINE PRIMITIVE WIDGETS
// ============================================================================
// These are low-level inline widgets matching 1:1 with Figma designs.
// Each widget has a clear API/props and exported config for registry building.

import type { ComponentRenderProps, ComponentRegistry } from "@/lib/data-view";
import { useResolvedProps } from "@/lib/data-view";
import { toDisplayString, isJsonValue } from "../utils";

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

export { StatsRowWidget, statsRowWidgetConfig } from "./StatsRowWidget";
export type { StatsRowWidgetProps, StatItem } from "./StatsRowWidget";

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
import { statsRowWidgetConfig } from "./StatsRowWidget";

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
  StatsRow: statsRowWidgetConfig,
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
import { StatsRowWidget } from "./StatsRowWidget";
import type { StatItem } from "./StatsRowWidget";

function TextRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
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
    variant: props.variant as "body" | "caption" | undefined,
    truncate: Boolean(props.truncate),
    monospace: isJsonValue(value) ? true : Boolean(props.monospace),
  });
}

function NumberRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return NumberWidget({
    value: props.value as number | null,
    label: props.label as string | null | undefined,
    size: props.size as "xs" | "sm" | "md" | "lg" | "xl" | undefined,
    format: props.format as "decimal" | "percent" | "currency" | undefined,
  });
}

function LabelRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  const text = props.text;

  // DEBUG: Log empty labels
  if (!text) {
    console.debug("[Label] Received empty text prop");
  }

  return LabelWidget({
    text: toDisplayString(text),
  });
}

function TagRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
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

function BoolRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return BoolWidget({
    value: props.value as boolean | null,
    style: props.style as "check" | "text" | undefined,
  });
}

function BoolChipRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return BoolChipWidget({
    value: props.value as boolean | null,
    trueLabel: props.trueLabel as string | null | undefined,
    falseLabel: props.falseLabel as string | null | undefined,
  });
}

function LinkButtonRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return LinkButtonWidget({
    type: props.type as "trace" | "span",
    id: String(props.id ?? ""),
    label: props.label as string | null | undefined,
  });
}

function LinkRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return LinkWidget({
    url: String(props.url ?? ""),
    text: props.text as string | null | undefined,
  });
}

function TraceLinkRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return TraceLinkWidget({
    traceId: String(props.traceId ?? ""),
    text: props.text as string | null | undefined,
  });
}

function ThreadLinkRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return ThreadLinkWidget({
    threadId: String(props.threadId ?? ""),
    text: props.text as string | null | undefined,
  });
}

function StatsRowRenderer({ element }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  const items = props.items as StatItem[] | undefined;
  return StatsRowWidget({
    items: items ?? [],
  });
}

export const inlineWidgetRegistry: ComponentRegistry = {
  Text: TextRenderer,
  Number: NumberRenderer,
  Label: LabelRenderer,
  Tag: TagRenderer,
  Bool: BoolRenderer,
  BoolChip: BoolChipRenderer,
  LinkButton: LinkButtonRenderer,
  Link: LinkRenderer,
  TraceLink: TraceLinkRenderer,
  ThreadLink: ThreadLinkRenderer,
  StatsRow: StatsRowRenderer,
};
