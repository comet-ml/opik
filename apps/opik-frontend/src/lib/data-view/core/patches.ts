import type { ViewTree, ViewNode, ViewPatch } from "./types";
import { getByPath, setByPath, unsetByPath } from "./path";

// ============================================================================
// JSON POINTER HELPERS
// ============================================================================

/**
 * Parse a JSON Pointer into path segments.
 * /nodes/section-1/props/title → ["nodes", "section-1", "props", "title"]
 */
export function parseJsonPointer(pointer: string): string[] {
  if (!pointer.startsWith("/"))
    throw new Error(`Invalid JSON Pointer: ${pointer}`);
  if (pointer === "/") return [];
  return pointer
    .slice(1)
    .split("/")
    .map((s) => s.replace(/~1/g, "/").replace(/~0/g, "~"));
}

/**
 * Deep clone an object (simple implementation for tree structures).
 */
function deepClone<T>(obj: T): T {
  if (obj === null || typeof obj !== "object") return obj;
  if (Array.isArray(obj)) return obj.map(deepClone) as T;
  const cloned: Record<string, unknown> = {};
  for (const key of Object.keys(obj)) {
    cloned[key] = deepClone((obj as Record<string, unknown>)[key]);
  }
  return cloned as T;
}

// ============================================================================
// PATCH APPLICATION
// ============================================================================

/**
 * Apply a single patch to a view tree.
 * Returns new tree (immutable).
 */
export function applyPatch(tree: ViewTree, patch: ViewPatch): ViewTree {
  const newTree = deepClone(tree);
  const path = patch.path;

  switch (patch.op) {
    case "add":
    case "replace":
      if (path && path !== "/") {
        setByPath(
          newTree as unknown as Record<string, unknown>,
          path,
          patch.value,
        );
      }
      break;
    case "remove":
      if (path && path !== "/") {
        unsetByPath(newTree as unknown as Record<string, unknown>, path);
      }
      break;
    case "move": {
      if (!patch.from) throw new Error("Move requires 'from' path");
      const value = getByPath(newTree, patch.from);
      unsetByPath(newTree as unknown as Record<string, unknown>, patch.from);
      if (path && path !== "/") {
        setByPath(newTree as unknown as Record<string, unknown>, path, value);
      }
      break;
    }
  }

  newTree.meta = { ...newTree.meta, updatedAt: new Date().toISOString() };
  return newTree;
}

/**
 * Apply multiple patches in sequence.
 */
export function applyPatches(tree: ViewTree, patches: ViewPatch[]): ViewTree {
  return patches.reduce((t, p) => applyPatch(t, p), tree);
}

// ============================================================================
// PATCH CREATION HELPERS
// ============================================================================

/**
 * Create a patch to update a node's prop.
 */
export function createPropPatch(
  nodeId: string,
  propName: string,
  value: unknown,
): ViewPatch {
  return {
    op: "replace",
    path: `/nodes/${nodeId}/props/${propName}`,
    value,
  };
}

/**
 * Create a patch to add a new node.
 * Automatically sets parentKey if parentId is provided.
 */
export function createAddNodePatch(
  node: ViewNode,
  parentId?: string | null,
): ViewPatch {
  const nodeWithParent: ViewNode = {
    ...node,
    parentKey: parentId ?? null,
  };

  return {
    op: "add",
    path: `/nodes/${node.id}`,
    value: nodeWithParent,
  };
}

/**
 * Create a patch to remove a node.
 */
export function createRemoveNodePatch(nodeId: string): ViewPatch {
  return {
    op: "remove",
    path: `/nodes/${nodeId}`,
  };
}

/**
 * Create a patch to add a child to a container node.
 */
export function createAddChildPatch(
  parentId: string,
  childId: string,
  index?: number,
): ViewPatch {
  const path =
    index !== undefined
      ? `/nodes/${parentId}/children/${index}`
      : `/nodes/${parentId}/children/-`; // Append

  return {
    op: "add",
    path,
    value: childId,
  };
}

/**
 * Create a patch to update a node's parentKey.
 */
export function createSetParentPatch(
  nodeId: string,
  parentId: string | null,
): ViewPatch {
  return {
    op: "replace",
    path: `/nodes/${nodeId}/parentKey`,
    value: parentId,
  };
}

// ============================================================================
// TREE MERGING
// ============================================================================

/**
 * Merge AI-generated patches into existing tree.
 * Preserves user edits to editable props.
 */
export interface MergeOptions {
  /** Props that should never be overwritten by AI */
  preserveProps?: string[];
}

export function mergeViewTrees(
  existing: ViewTree,
  patches: ViewPatch[],
  options: MergeOptions = {},
): ViewTree {
  const { preserveProps = [] } = options;

  // Filter out patches that would overwrite preserved props
  const filteredPatches = patches.filter((patch) => {
    if (patch.op !== "replace") return true;

    // Check if patch targets a preserved prop
    const match = patch.path.match(/\/nodes\/[^/]+\/props\/(\w+)$/);
    if (match && preserveProps.includes(match[1])) {
      return false;
    }

    return true;
  });

  return applyPatches(existing, filteredPatches);
}

// ============================================================================
// PARENT-CHILD RELATIONSHIP UTILITIES
// ============================================================================

/**
 * Update parent-child relationships after adding a node.
 * Returns patches to add the node and update parent's children array.
 */
export function createAddNodeWithParentPatches(
  node: ViewNode,
  parentId: string,
  index?: number,
): ViewPatch[] {
  return [
    createAddNodePatch(node, parentId),
    createAddChildPatch(parentId, node.id, index),
  ];
}

/**
 * Update parent-child relationships after removing a node.
 * Returns patches to remove the node and update parent's children array.
 */
export function createRemoveNodeWithParentPatches(
  tree: ViewTree,
  nodeId: string,
): ViewPatch[] {
  const node = tree.nodes[nodeId];
  if (!node) return [];

  const patches: ViewPatch[] = [createRemoveNodePatch(nodeId)];

  // Find parent and remove from children array
  if (node.parentKey) {
    const parent = tree.nodes[node.parentKey];
    if (parent?.children) {
      const childIndex = parent.children.indexOf(nodeId);
      if (childIndex !== -1) {
        patches.push({
          op: "remove",
          path: `/nodes/${node.parentKey}/children/${childIndex}`,
        });
      }
    }
  }

  return patches;
}

// ============================================================================
// EMPTY TREE FACTORY
// ============================================================================

export function createEmptyTree(): ViewTree {
  return {
    version: 1,
    root: "",
    nodes: {},
  };
}
