"use client";

import type React from "react";
import type { ViewTree, ComponentRegistry } from "../core/types";
import { useDataView } from "./DataViewProvider";

// ============================================================================
// RE-EXPORTS
// ============================================================================

export type { ComponentRenderProps, ComponentRegistry } from "../core/types";

// ============================================================================
// RENDERER PROPS
// ============================================================================

interface RendererProps {
  /** Component registry (render functions) */
  registry: ComponentRegistry;
  /** Whether tree is currently loading/streaming */
  loading?: boolean;
}

// ============================================================================
// RENDERER
// ============================================================================

/**
 * Renders a view tree using the provided component registry.
 *
 * @example
 * import { createCatalog, Renderer, DataViewProvider, useResolvedProps } from "@/lib/data-view";
 *
 * // Define catalog (schemas for AI)
 * const catalog = createCatalog({ components: {...} });
 *
 * // Define registry (React components use element + useResolvedProps hook)
 * const registry = {
 *   Section: ({ element, children }) => {
 *     const props = useResolvedProps(element);
 *     return <div>{props.title}{children}</div>;
 *   },
 *   KeyValue: ({ element }) => {
 *     const props = useResolvedProps(element);
 *     return <span>{props.label}: {props.value}</span>;
 *   },
 * };
 *
 * // Render
 * <DataViewProvider tree={tree} source={data}>
 *   <Renderer registry={registry} />
 * </DataViewProvider>
 */
export function Renderer({
  registry,
  loading,
}: RendererProps): JSX.Element | null {
  const { tree } = useDataView();

  if (!tree.root || !tree.nodes[tree.root]) {
    return null;
  }

  return (
    <NodeRenderer
      nodeId={tree.root}
      tree={tree}
      registry={registry}
      loading={loading}
    />
  );
}

// ============================================================================
// NODE RENDERER (recursive)
// ============================================================================

interface NodeRendererProps {
  nodeId: string;
  tree: ViewTree;
  registry: ComponentRegistry;
  loading?: boolean;
}

function NodeRenderer({
  nodeId,
  tree,
  registry,
  loading,
}: NodeRendererProps): JSX.Element | null {
  const node = tree.nodes[nodeId];
  if (!node) return null;

  // Get component renderer
  const Component = registry[node.type];
  if (!Component) {
    // Inline error UI for unknown component types
    return (
      <div className="rounded-md border border-destructive/50 bg-destructive/10 p-4">
        <div className="text-sm font-medium text-destructive">
          Unknown component: {node.type}
        </div>
        <div className="mt-1 text-xs text-destructive">
          No renderer registered for this component type
        </div>
      </div>
    );
  }

  // Render children recursively
  const childProps = {
    tree,
    registry,
    loading,
  };
  const children = node.children?.map((childId) => (
    <NodeRenderer key={childId} nodeId={childId} {...childProps} />
  ));

  // Components use useResolvedProps hook internally to resolve dynamic values
  return (
    <AnimatedMount nodeId={nodeId}>
      <Component element={node} loading={loading}>
        {children}
      </Component>
    </AnimatedMount>
  );
}

// ============================================================================
// ANIMATED MOUNT WRAPPER
// ============================================================================

/**
 * Wraps components with a subtle animation when they are patched/updated.
 * Only animates nodes that have been explicitly marked for animation via trackPatch.
 *
 * Uses border instead of ring to avoid clipping issues with overflow-hidden parents.
 * Includes a pulsing glow overlay for a "magic building" effect.
 */
function AnimatedMount({
  nodeId,
  children,
}: {
  nodeId: string;
  children: React.ReactNode;
}) {
  const { isNodeAnimated } = useDataView();
  const shouldAnimate = isNodeAnimated(nodeId);

  if (!shouldAnimate) {
    return <>{children}</>;
  }

  return (
    <div className="relative overflow-hidden rounded-sm duration-300 ease-out animate-in fade-in-0 zoom-in-[0.98] slide-in-from-bottom-2">
      {/* Pulsing glow overlay - creates magic building feel */}
      <div className="pointer-events-none absolute inset-0 animate-pulse rounded-sm bg-gradient-to-r from-primary/0 via-primary/10 to-primary/0" />
      {/* Content with border highlight (replaces ring to avoid clipping) */}
      <div className="rounded-sm border border-primary/30 bg-primary/5 transition-colors duration-500">
        {children}
      </div>
    </div>
  );
}
