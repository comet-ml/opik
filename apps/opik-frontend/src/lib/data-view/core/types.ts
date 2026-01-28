import { z } from "zod";
import type { ReactNode } from "react";

// ============================================================================
// DATA BINDING (Dynamic Values)
// ============================================================================

/**
 * Reference to a path in source data.
 * Uses JSON Pointer-like syntax: "/tools/0/name", "/metadata/model", etc.
 */
export interface PathRef {
  path: string;
}

/**
 * A value that can be either a literal or a path reference.
 */
export type DynamicValue<T = unknown> = T | PathRef;

export const PathRefSchema = z.object({
  path: z.string().describe("Path to source data (e.g., '/tools/0/name')"),
});

export function createDynamicSchema<T extends z.ZodType>(
  literalSchema: T,
): z.ZodUnion<[T, typeof PathRefSchema]> {
  return z.union([literalSchema, PathRefSchema]);
}

/**
 * Creates a nullable dynamic schema with flattened anyOf.
 * This avoids nested anyOf which OpenAI strict mode doesn't handle well.
 */
export function createNullableDynamicSchema<T extends z.ZodType>(
  literalSchema: T,
): z.ZodUnion<[T, typeof PathRefSchema, z.ZodNull]> {
  return z.union([literalSchema, PathRefSchema, z.null()]);
}

// Pre-built dynamic value schemas
export const DynamicString = createDynamicSchema(z.string());
export const DynamicNumber = createDynamicSchema(z.number());
export const DynamicBoolean = createDynamicSchema(z.boolean());
export const DynamicArray = createDynamicSchema(z.array(z.unknown()));

// Pre-built nullable dynamic value schemas (flattened for OpenAI strict mode)
export const NullableDynamicString = createNullableDynamicSchema(z.string());
export const NullableDynamicNumber = createNullableDynamicSchema(z.number());
export const NullableDynamicBoolean = createNullableDynamicSchema(z.boolean());

// ============================================================================
// VIEW NODE (UI Element)
// ============================================================================

/**
 * A single node in the view tree.
 */
export interface ViewNode<
  TType extends string = string,
  TProps extends Record<string, unknown> = Record<string, unknown>,
> {
  /** Unique identifier */
  id: string;
  /** Component type from catalog */
  type: TType;
  /** Component props (may contain DynamicValues) */
  props: TProps;
  /** Child node IDs (for container components) */
  children?: string[];
  /** Parent node ID (null for root) */
  parentKey?: string | null;
}

// ============================================================================
// VIEW TREE
// ============================================================================

/**
 * Complete view configuration.
 * Flat structure for easy patching and storage.
 */
export interface ViewTree {
  /** Schema version for migrations */
  version: number;
  /** Root node ID */
  root: string;
  /** Flat map of all nodes */
  nodes: Record<string, ViewNode>;
  /** User-defined metadata */
  meta?: {
    name?: string;
    description?: string;
    createdAt?: string;
    updatedAt?: string;
  };
}

// ============================================================================
// PATCHES
// ============================================================================

export type PatchOp = "add" | "replace" | "remove" | "move";

export interface ViewPatch {
  op: PatchOp;
  path: string;
  value?: unknown;
  from?: string;
}

export const ViewPatchSchema = z.object({
  op: z.enum(["add", "replace", "remove", "move"]),
  path: z.string().regex(/^\//, "Path must start with /"),
  value: z.unknown().optional(),
  from: z.string().optional(),
});

// ============================================================================
// SOURCE DATA
// ============================================================================

/**
 * Generic source data structure.
 */
export type SourceData = Record<string, unknown>;

// ============================================================================
// COMPONENT RENDER PROPS
// ============================================================================

/**
 * Props passed to component renderers.
 * Aligned with json-render pattern: element + children.
 * Components use useResolvedProps() hook to resolve dynamic values.
 */
export interface ComponentRenderProps {
  /** Full element definition (type, props, children keys, etc.) */
  element: ViewNode;
  /** Pre-rendered children */
  children: ReactNode;
  /** Optional loading state */
  loading?: boolean;
}

// ============================================================================
// COMPONENT DEFINITION (for Catalog)
// ============================================================================

/**
 * Component definition with schema and metadata.
 * Used in catalog for AI generation.
 */
export interface ComponentDefinition<
  TProps extends z.ZodObject<z.ZodRawShape> = z.ZodObject<z.ZodRawShape>,
> {
  /** Zod schema for props validation and AI generation */
  props: TProps;
  /** Whether this component can have children */
  hasChildren: boolean;
  /** Description for AI prompt generation */
  description: string;
}

// ============================================================================
// COMPONENT REGISTRY (for Rendering)
// ============================================================================

/**
 * Component renderer function type.
 */
export type ComponentRenderer = (
  renderProps: ComponentRenderProps,
) => ReactNode;

/**
 * Registry of component renderers.
 * Maps component type names to their render functions.
 */
export type ComponentRegistry = Record<string, ComponentRenderer>;
