import { create } from "zustand";
import isFunction from "lodash/isFunction";
import { OnChangeFn } from "@/types/shared";
import { BASE_TRACE_DATA_TYPE, Span } from "@/types/traces";

export type SpanWithMetadata = Omit<Span, "type"> & {
  type: BASE_TRACE_DATA_TYPE;
  duration: number;
  tokens?: number;
  spanColor?: string;
  startTimestamp: number;
  maxStartTime: number;
  maxEndTime: number;
  maxDuration: number;
  hasError?: boolean;
  isInSearch?: boolean;
  span_feedback_scores?: import("@/types/traces").TraceFeedbackScore[];
};

export interface TreeNode {
  id: string;
  name: string;
  data: SpanWithMetadata;
  children?: TreeNode[];
}

export enum TREE_DATABLOCK_TYPE {
  GUARDRAILS = "guardrails",
  DURATION = "duration",
  NUMBERS_OF_TOKENS = "tokens",
  TOKENS_BREAKDOWN = "tokensBreakdown",
  ESTIMATED_COST = "estimatedCost",
  NUMBER_OF_SCORES = "numberOfScores",
  NUMBER_OF_COMMENTS = "numberOfComments",
  NUMBER_OF_TAGS = "numberOfTags",
  MODEL = "model",
  DURATION_TIMELINE = "durationTimeline",
}

export type TreeNodeConfig = Record<TREE_DATABLOCK_TYPE, boolean>;

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

interface FlattenedNode extends TreeNode {
  depth: number;
}

type TreeDetailsStore = {
  tree: TreeNode[];
  setTree: OnChangeFn<TreeNode[]>;
  flattenedTree: FlattenedNode[];
  fullExpandedSet: Set<string>;
  expandedTreeRows: Set<string>;
  setExpandedTreeRows: OnChangeFn<Set<string>>;
  toggleExpandAll: () => void;
  toggleExpand: (id: string) => void;
};

const useTreeDetailsStore = create<TreeDetailsStore>((set, get) => ({
  tree: [],
  setTree: (update) => {
    set((state) => {
      const tree = isFunction(update) ? update(state.tree) : update;
      const fullExpandedSet = new Set<string>(generateFullExpandedMap(tree));
      const flattenedTree: FlattenedNode[] = traverse(tree, 0, fullExpandedSet);

      return {
        ...state,
        tree,
        flattenedTree,
        fullExpandedSet,
        expandedTreeRows: fullExpandedSet,
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
}));

export default useTreeDetailsStore;
