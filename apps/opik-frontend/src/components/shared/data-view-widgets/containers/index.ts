// ============================================================================
// CONTAINER WIDGETS
// ============================================================================
// Container widgets for layout and grouping.
// Each widget has a clear API/props and exported config for registry building.
// Containers have hasChildren: true and receive/render children.

import type { ComponentRenderProps, ComponentRegistry } from "@/lib/data-view";
import { useResolvedProps } from "@/lib/data-view";

// Components
export { ContainerWidget, containerWidgetConfig } from "./Container";
export type {
  ContainerWidgetProps,
  ContainerLayout,
  ContainerGap,
  ContainerPadding,
} from "./Container";

export {
  Level1ContainerWidget,
  level1ContainerWidgetConfig,
} from "./Level1Container";
export type { Level1ContainerWidgetProps } from "./Level1Container";

export {
  Level2ContainerWidget,
  level2ContainerWidgetConfig,
} from "./Level2Container";
export type {
  Level2ContainerWidgetProps,
  Level2IconType,
  Level2StatusType,
} from "./Level2Container";

export { InlineRowWidget, inlineRowWidgetConfig } from "./InlineRow";
export type { InlineRowWidgetProps } from "./InlineRow";

// ============================================================================
// AGGREGATED CONFIGS (for catalog building)
// ============================================================================

import { containerWidgetConfig } from "./Container";
import { level1ContainerWidgetConfig } from "./Level1Container";
import { level2ContainerWidgetConfig } from "./Level2Container";
import { inlineRowWidgetConfig } from "./InlineRow";

export const containerWidgetConfigs = {
  Container: containerWidgetConfig,
  Level1Container: level1ContainerWidgetConfig,
  Level2Container: level2ContainerWidgetConfig,
  InlineRow: inlineRowWidgetConfig,
} as const;

// ============================================================================
// AGGREGATED REGISTRY (for rendering)
// ============================================================================

import { ContainerWidget } from "./Container";
import type {
  ContainerLayout,
  ContainerGap,
  ContainerPadding,
} from "./Container";
import { Level1ContainerWidget } from "./Level1Container";
import { Level2ContainerWidget } from "./Level2Container";
import { InlineRowWidget } from "./InlineRow";

function ContainerRenderer({ element, children }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return ContainerWidget({
    layout: props.layout as ContainerLayout | undefined,
    gap: props.gap as ContainerGap | undefined,
    padding: props.padding as ContainerPadding | undefined,
    children,
  });
}

function Level1ContainerRenderer({ element, children }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return Level1ContainerWidget({
    title: props.title as string | null | undefined,
    defaultOpen: props.defaultOpen !== false,
    children,
  });
}

function Level2ContainerRenderer({ element, children }: ComponentRenderProps) {
  const props = useResolvedProps(element);
  return Level2ContainerWidget({
    summary: String(props.summary ?? ""),
    defaultOpen: Boolean(props.defaultOpen),
    icon: (props.icon as "tool" | "retrieval" | "generation" | null) ?? null,
    status: props.status as "success" | "error" | "pending" | null | undefined,
    duration: props.duration as string | null | undefined,
    children,
  });
}

function InlineRowRenderer({ children }: ComponentRenderProps) {
  return InlineRowWidget({
    children,
  });
}

export const containerWidgetRegistry: ComponentRegistry = {
  Container: ContainerRenderer,
  Level1Container: Level1ContainerRenderer,
  Level2Container: Level2ContainerRenderer,
  InlineRow: InlineRowRenderer,
};
