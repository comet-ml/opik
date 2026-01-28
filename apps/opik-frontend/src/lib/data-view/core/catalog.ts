import { z } from "zod";
import type {
  ComponentDefinition,
  ViewNode,
  ViewTree,
  SourceData,
} from "./types";
import { analyzeSourceData, type PathInfo } from "./path";

// ============================================================================
// CATALOG CONFIGURATION
// ============================================================================

/**
 * Catalog configuration for createCatalog.
 */
export interface CatalogConfig<
  TComponents extends Record<string, ComponentDefinition> = Record<
    string,
    ComponentDefinition
  >,
> {
  /** Catalog name (for prompt generation) */
  name?: string;
  /** Component definitions */
  components: TComponents;
}

// ============================================================================
// CATALOG INTERFACE
// ============================================================================

/**
 * Catalog instance with component definitions and schemas.
 */
export interface Catalog<
  TComponents extends Record<string, ComponentDefinition> = Record<
    string,
    ComponentDefinition
  >,
> {
  /** Catalog name */
  readonly name: string;
  /** Component type names */
  readonly componentNames: (keyof TComponents & string)[];
  /** Component definitions */
  readonly components: TComponents;
  /** Zod schema for a single node */
  readonly nodeSchema: z.ZodType<ViewNode>;
  /** Zod schema for the full tree */
  readonly treeSchema: z.ZodType<ViewTree>;
  /** Check if component exists */
  hasComponent(type: string): boolean;
  /** Get component definition */
  getComponent(type: string): ComponentDefinition | undefined;
  /** Validate a node */
  validateNode(node: unknown): {
    success: boolean;
    data?: ViewNode;
    error?: z.ZodError;
  };
  /** Validate a tree */
  validateTree(tree: unknown): {
    success: boolean;
    data?: ViewTree;
    error?: z.ZodError;
  };
}

// ============================================================================
// CREATE CATALOG
// ============================================================================

/**
 * Create a catalog of components for AI generation and validation.
 *
 * @example
 * const catalog = createCatalog({
 *   name: "dashboard",
 *   components: {
 *     Section: {
 *       props: z.object({ title: DynamicString }),
 *       hasChildren: true,
 *       description: "Groups related content",
 *     },
 *     KeyValue: {
 *       props: z.object({ label: z.string(), value: DynamicString }),
 *       hasChildren: false,
 *       description: "Displays a key-value pair",
 *     },
 *   },
 * });
 */
export function createCatalog<
  TComponents extends Record<string, ComponentDefinition>,
>(config: CatalogConfig<TComponents>): Catalog<TComponents> {
  const { name = "unnamed", components } = config;
  const componentNames = Object.keys(components) as (keyof TComponents &
    string)[];

  // Build node schemas for each component type
  const componentNodeSchemas: z.ZodTypeAny[] = [];
  for (const typeName of componentNames) {
    const def = components[typeName];
    if (!def) continue;

    componentNodeSchemas.push(
      z.object({
        id: z.string(),
        type: z.literal(typeName),
        props: def.props,
        children: z.array(z.string()).nullable(),
        parentKey: z.string().nullable(),
      }),
    );
  }

  // Create union schema for all component types
  let nodeSchema: z.ZodType<ViewNode>;

  if (componentNodeSchemas.length === 0) {
    // Empty catalog fallback
    nodeSchema = z.object({
      id: z.string(),
      type: z.string(),
      props: z.record(z.string(), z.unknown()),
      children: z.array(z.string()).nullable(),
      parentKey: z.string().nullable(),
    }) as z.ZodType<ViewNode>;
  } else if (componentNodeSchemas.length === 1) {
    nodeSchema = componentNodeSchemas[0] as z.ZodType<ViewNode>;
  } else {
    // Use discriminatedUnion for efficient parsing
    // Cast through unknown as json-render does - Zod's complex union types don't directly cast
    nodeSchema = z.discriminatedUnion("type", [
      componentNodeSchemas[0] as z.ZodDiscriminatedUnionOption<"type">,
      componentNodeSchemas[1] as z.ZodDiscriminatedUnionOption<"type">,
      ...(componentNodeSchemas.slice(
        2,
      ) as z.ZodDiscriminatedUnionOption<"type">[]),
    ]) as unknown as z.ZodType<ViewNode>;
  }

  // Tree schema
  const treeSchema = z.object({
    version: z.literal(1),
    root: z.string(),
    nodes: z.record(z.string(), nodeSchema),
    meta: z
      .object({
        name: z.string().nullable(),
        description: z.string().nullable(),
        createdAt: z.string().nullable(),
        updatedAt: z.string().nullable(),
      })
      .nullable(),
  }) as z.ZodType<ViewTree>;

  return {
    name,
    componentNames,
    components,
    nodeSchema,
    treeSchema,

    hasComponent(type: string) {
      return type in components;
    },

    getComponent(type: string) {
      return components[type as keyof TComponents];
    },

    validateNode(node: unknown) {
      const result = nodeSchema.safeParse(node);
      return result.success
        ? { success: true, data: result.data }
        : { success: false, error: result.error };
    },

    validateTree(tree: unknown) {
      const result = treeSchema.safeParse(tree);
      return result.success
        ? { success: true, data: result.data }
        : { success: false, error: result.error };
    },
  };
}

// ============================================================================
// PROMPT GENERATION
// ============================================================================

/**
 * Options for prompt generation.
 */
export interface PromptOptions {
  /** Include available data paths from source data */
  includeDataPaths?: boolean;
  /** Source data for path extraction */
  sourceData?: SourceData;
  /** Max depth for data path extraction */
  maxDataDepth?: number;
  /** Existing tree (for update mode) */
  existingTree?: ViewTree;
  /** Additional instructions */
  additionalInstructions?: string;
}

/**
 * Generate a system prompt for AI that describes the catalog.
 *
 * @example
 * const prompt = generatePrompt(catalog, {
 *   sourceData: traceData,
 *   includeDataPaths: true,
 * });
 */
export function generatePrompt<
  TComponents extends Record<string, ComponentDefinition>,
>(catalog: Catalog<TComponents>, options: PromptOptions = {}): string {
  const {
    includeDataPaths = true,
    sourceData,
    maxDataDepth = 4,
    existingTree,
    additionalInstructions,
  } = options;

  const sections: string[] = [];

  // Header
  sections.push(`# ${catalog.name} UI Builder

Generate a ViewTree JSON that renders data using the available components.
Output must conform to the schema exactly.`);

  // Mode
  if (existingTree) {
    sections.push(`## Mode: UPDATE EXISTING

Modify the existing tree. Preserve user-customized values unless asked to change.

Current tree:
\`\`\`json
${JSON.stringify(existingTree, null, 2)}
\`\`\``);
  } else {
    sections.push(`## Mode: CREATE NEW

Generate a complete ViewTree from scratch.`);
  }

  // Components
  sections.push(generateComponentsSection(catalog));

  // Data paths
  if (includeDataPaths && sourceData) {
    const paths = analyzeSourceData(sourceData, maxDataDepth);
    sections.push(generateDataPathsSection(paths));
  }

  // Rules
  sections.push(`## Rules

1. **Unique IDs**: Every node needs a unique \`id\` that matches its key in \`nodes\`
2. **Data binding**: Use \`{ "path": "/json/pointer/path" }\` for dynamic values from source data
3. **Flat structure**: All nodes go in the top-level \`nodes\` object, use \`children\` array for hierarchy
4. **Parent tracking**: Set \`parentKey\` to the parent node's ID (null for root)
5. **Static titles**: Use literal strings for section/card titles (not bound) so users can customize`);

  // Additional instructions
  if (additionalInstructions) {
    sections.push(`## Additional Instructions

${additionalInstructions}`);
  }

  return sections.join("\n\n");
}

function generateComponentsSection<
  TComponents extends Record<string, ComponentDefinition>,
>(catalog: Catalog<TComponents>): string {
  const lines: string[] = ["## Available Components\n"];

  for (const name of catalog.componentNames) {
    const def = catalog.components[name];
    if (!def) continue;

    lines.push(`### ${name}`);
    lines.push(def.description);
    lines.push(
      `- Container: ${def.hasChildren ? "Yes (can have children)" : "No"}`,
    );
    lines.push("");
  }

  return lines.join("\n");
}

function generateDataPathsSection(paths: PathInfo[]): string {
  const lines: string[] = ["## Available Data Paths\n"];
  lines.push('Use `{ "path": "..." }` to bind props to source data:\n');
  lines.push("| Path | Type | Sample |");
  lines.push("|------|------|--------|");

  for (const path of paths) {
    const sample =
      path.sample !== undefined
        ? JSON.stringify(path.sample).slice(0, 50)
        : path.arrayLength !== undefined
          ? `[${path.arrayLength} items]`
          : "-";

    lines.push(`| \`${path.path}\` | ${path.type} | ${sample} |`);
  }

  return lines.join("\n");
}

// ============================================================================
// TYPE HELPERS
// ============================================================================

/**
 * Infer component props from a catalog.
 */
export type InferCatalogProps<C extends Catalog> = {
  [K in keyof C["components"]]: z.infer<C["components"][K]["props"]>;
};
