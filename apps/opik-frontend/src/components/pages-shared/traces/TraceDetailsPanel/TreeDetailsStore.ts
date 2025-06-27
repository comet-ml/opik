import { create } from "zustand";
import isFunction from "lodash/isFunction";
import { OnChangeFn } from "@/types/shared";
import { BASE_TRACE_DATA_TYPE, Span } from "@/types/traces";

const LEVEL_TO_EXPAND = 3;

export type SpanWithMetadata = Omit<Span, "type"> & {
  type: BASE_TRACE_DATA_TYPE;
  duration: number;
  tokens?: number;
  spanColor?: string;
  startTimestamp?: number;
  maxStartTime?: number;
  maxEndTime?: number;
  maxDuration?: number;
  hasError?: boolean;
  isInSearch?: boolean;
};

export interface TreeNode {
  id: string;
  name: string;
  data: SpanWithMetadata;
  children?: TreeNode[];
}

const traverse = (
  nodes: TreeNode[],
  depth: number,
  expandedTreeRows: Set<string>,
  list: FlattenedNode[] = [],
) => {
  nodes.forEach((node) => {
    list.push({ ...node, depth });
    if (node.children && expandedTreeRows.has(node.id)) {
      traverse(node.children, depth + 1, expandedTreeRows, list);
    }
  });

  return list;
};

const generateFullExpandedMap = (nodes: TreeNode[], ids: string[] = []) => {
  nodes.forEach((node) => {
    if (node.children?.length) {
      ids.push(node.id);
      generateFullExpandedMap(node.children, ids);
    }
  });

  return ids;
};

const getNodePath = (
  nodes: TreeNode[],
  targetId: string,
  path: string[] = [],
): string[] | null => {
  for (const node of nodes) {
    const newPath = [...path, node.id];
    if (node.id === targetId) {
      return newPath;
    }
    if (node.children) {
      const foundPath = getNodePath(node.children, targetId, newPath);
      if (foundPath) return foundPath;
    }
  }
  return null;
};

// TODO lala need to clean this cache every time we have closed sidebar
const treeDetectorCache: {
  id: string;
  lengthMap: Record<string, number>;
} = { id: "", lengthMap: {} };

const detectIsNewTree = (tree: TreeNode[]) => {
  const root = tree[0];
  if (
    (root && treeDetectorCache.id !== root.id) ||
    (treeDetectorCache.id === root.id &&
      treeDetectorCache.lengthMap[root.id] !== root.children?.length)
  ) {
    treeDetectorCache.id = root.id;
    treeDetectorCache.lengthMap[root.id] = root.children?.length || 0;
    return true;
  }
  return false;
};

const expandToLevel = (tree: TreeNode[]) => {
  const expanded = new Set<string>();
  const traverseToLevel = (nodes: TreeNode[], currentDepth: number) => {
    nodes.forEach((node) => {
      if (currentDepth < LEVEL_TO_EXPAND) {
        expanded.add(node.id);
        if (node.children) {
          traverseToLevel(node.children, currentDepth + 1);
        }
      }
    });
  };
  traverseToLevel(tree, 0);
  return expanded;
};

interface FlattenedNode extends TreeNode {
  depth: number;
}

type TreeDetailsStore = {
  rowId?: string;
  tree: TreeNode[];
  setTree: OnChangeFn<TreeNode[]>;
  flattenedTree: FlattenedNode[];
  fullExpandedSet: Set<string>;
  expandedTreeRows: Set<string>;
  setExpandedTreeRows: OnChangeFn<Set<string>>;
  toggleExpandAll: () => void;
  expandToNode: (id: string) => void;
  toggleExpand: (id: string) => void;
  getNextRowId: (id: string) => string | undefined;
  getPreviousRosId: (id: string) => string | undefined;
};

const useTreeDetailsStore = create<TreeDetailsStore>((set, get) => ({
  tree: [],
  setTree: (update) => {
    set((state) => {
      const tree = isFunction(update) ? update(state.tree) : update;
      const flattenedTree: FlattenedNode[] = traverse(
        tree,
        0,
        state.expandedTreeRows,
      );
      const fullExpandedSet = new Set<string>(generateFullExpandedMap(tree));
      let expandedTreeRows = state.expandedTreeRows;
      if (detectIsNewTree(tree)) {
        expandedTreeRows = expandToLevel(tree);
      }

      return {
        ...state,
        tree,
        flattenedTree,
        fullExpandedSet,
        expandedTreeRows,
      };
    });
  },
  flattenedTree: [],
  fullExpandedSet: new Set(),
  expandedTreeRows: new Set(),
  setExpandedTreeRows: (update) => {
    set((state) => {
      const expandedTreeRows = isFunction(update)
        ? update(state.expandedTreeRows)
        : update;
      const flattenedTree: FlattenedNode[] = traverse(
        state.tree,
        0,
        expandedTreeRows,
      );

      return {
        ...state,
        expandedTreeRows,
        flattenedTree,
      };
    });
  },
  toggleExpandAll: () => {
    const state = get();

    state.setExpandedTreeRows(
      new Set<string>(
        state.expandedTreeRows.size === state.fullExpandedSet.size
          ? []
          : state.fullExpandedSet,
      ),
    );
  },
  expandToNode: (id: string) => {
    const state = get();
    const path = getNodePath(state.tree, id);
    if (path) {
      const expanded = new Set(state.expandedTreeRows);
      path.forEach((nodeId) => expanded.add(nodeId));
      state.setExpandedTreeRows(expanded);
    }
  },
  toggleExpand: (id: string) => {
    const state = get();
    const expanded = new Set(state.expandedTreeRows);
    if (expanded.has(id)) {
      expanded.delete(id);
    } else {
      expanded.add(id);
    }
    state.setExpandedTreeRows(expanded);
  },
  getNextRowId: (id: string) => {
    const state = get();
    const index = state.flattenedTree.findIndex((node) => node.id === id);
    return index !== -1 ? state.flattenedTree[index + 1]?.id : undefined;
  },
  getPreviousRosId: (id: string) => {
    const state = get();
    const index = state.flattenedTree.findIndex((node) => node.id === id);
    return index > 0 ? state.flattenedTree[index - 1]?.id : undefined;
  },
}));

export default useTreeDetailsStore;
