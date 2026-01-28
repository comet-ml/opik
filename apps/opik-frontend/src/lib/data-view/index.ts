// ============================================================================
// CATALOG (for AI generation)
// ============================================================================

export { createCatalog, generatePrompt } from "./core/catalog";
export type {
  Catalog,
  CatalogConfig,
  PromptOptions,
  InferCatalogProps,
} from "./core/catalog";

// ============================================================================
// RENDERER (for React rendering)
// ============================================================================

export { Renderer } from "./react/Renderer";
export type { ComponentRenderProps, ComponentRegistry } from "./react/Renderer";

// ============================================================================
// TYPES
// ============================================================================

export type {
  // Dynamic values (data binding)
  PathRef,
  DynamicValue,
  // Components
  ComponentDefinition,
  ComponentRenderer,
  // Nodes and tree
  ViewNode,
  ViewTree,
  // Patches
  PatchOp,
  ViewPatch,
  // Data
  SourceData,
} from "./core/types";

// ============================================================================
// DYNAMIC VALUE SCHEMAS (for defining component props)
// ============================================================================

export {
  PathRefSchema,
  DynamicString,
  DynamicNumber,
  DynamicBoolean,
  DynamicArray,
  // Nullable versions (flattened for OpenAI strict mode)
  NullableDynamicString,
  NullableDynamicNumber,
  NullableDynamicBoolean,
  createDynamicSchema,
  createNullableDynamicSchema,
  ViewPatchSchema,
} from "./core/types";

// ============================================================================
// PATH UTILITIES
// ============================================================================

export type { PathInfo } from "./core/path";

export {
  isPathRef,
  getByPath,
  setByPath,
  hasPath,
  resolveDynamicValue,
  resolveProps,
  extractAllPaths,
  analyzeSourceData,
} from "./core/path";

// ============================================================================
// PATCHES
// ============================================================================

export type { MergeOptions } from "./core/patches";

export {
  parseJsonPointer,
  applyPatch,
  applyPatches,
  createPropPatch,
  createAddNodePatch,
  createRemoveNodePatch,
  createAddChildPatch,
  createSetParentPatch,
  createAddNodeWithParentPatches,
  createRemoveNodeWithParentPatches,
  mergeViewTrees,
  createEmptyTree,
} from "./core/patches";

// ============================================================================
// STORAGE
// ============================================================================

export type { StorageOptions } from "./core/storage";

export {
  saveView,
  loadView,
  deleteView,
  listViews,
  viewExists,
  StorageError,
} from "./core/storage";

// ============================================================================
// STREAMING
// ============================================================================

export type { StreamProcessorOptions } from "./core/streaming";

export {
  parseViewStream,
  parseViewStreamGraceful,
  processViewStream,
  treeToPatches,
} from "./core/streaming";

// ============================================================================
// REACT INTEGRATION
// ============================================================================

export {
  DataViewProvider,
  useDataView,
  useSourceData,
  useViewTree,
} from "./react/DataViewProvider";

export type {
  UseViewStreamOptions,
  UseViewStreamResult,
} from "./react/useViewStream";

export { useViewStream } from "./react/useViewStream";

export type { UseViewStorageResult } from "./react/useViewStorage";

export { useViewStorage, useAutoSave } from "./react/useViewStorage";
