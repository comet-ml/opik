import React, {
  createContext,
  useContext,
  useState,
  useMemo,
  useCallback,
  useEffect,
  useRef,
  type ReactNode,
} from "react";
import type { SourceData, ViewTree, ViewPatch } from "../core/types";
import { applyPatch, applyPatches, createEmptyTree } from "../core/patches";
import { saveView, loadView } from "../core/storage";

// ============================================================================
// CONTEXT
// ============================================================================

interface DataViewContextValue {
  /** Current source data */
  source: SourceData;

  /** Current view tree */
  tree: ViewTree;

  /** Update source data */
  setSource: (source: SourceData) => void;

  /** Update view tree */
  setTree: (tree: ViewTree) => void;

  /** Apply a single patch */
  patch: (p: ViewPatch) => void;

  /** Apply multiple patches */
  patchAll: (patches: ViewPatch[]) => void;

  /**
   * Set a static (non-bound) prop value on a node.
   * Use this for UI-driven prop mutations.
   */
  setStaticProp: (nodeId: string, propKey: string, value: unknown) => void;

  /** Save current view to localStorage */
  save: (key: string) => void;

  /** Load view from localStorage */
  load: (key: string) => boolean;

  /** Reset to empty tree */
  reset: () => void;

  /** Check if a node should be animated */
  isNodeAnimated: (nodeId: string) => boolean;

  /** Track a patch for animation (extracts node ID automatically) */
  trackPatch: (patch: ViewPatch) => void;
}

const DataViewContext = createContext<DataViewContextValue | null>(null);

// ============================================================================
// PROVIDER
// ============================================================================

interface DataViewProviderProps {
  /** Initial source data */
  initialSource: SourceData;

  /** Initial view tree (optional) */
  initialTree?: ViewTree;

  /** Storage key for auto-save (optional) */
  storageKey?: string;

  children: ReactNode;
}

export function DataViewProvider({
  initialSource,
  initialTree,
  storageKey,
  children,
}: DataViewProviderProps): JSX.Element {
  const [source, setSource] = useState<SourceData>(initialSource);
  const [tree, setTree] = useState<ViewTree>(
    () =>
      (storageKey && loadView(storageKey)) || initialTree || createEmptyTree(),
  );

  // Animation tracking state
  const [animatedNodes, setAnimatedNodes] = useState<Set<string>>(new Set());
  const timeoutsRef = useRef<Map<string, NodeJS.Timeout>>(new Map());

  // Extract node ID from patch path (e.g., "/nodes/abc123/..." -> "abc123")
  const extractNodeIdFromPatch = useCallback(
    (patch: ViewPatch): string | null => {
      const match = patch.path.match(/^\/nodes\/([^/]+)/);
      return match ? match[1] : null;
    },
    [],
  );

  // Mark node for animation
  const markNodeForAnimation = useCallback((nodeId: string) => {
    setAnimatedNodes((prev) => new Set(prev).add(nodeId));

    // Clear existing timeout
    const existing = timeoutsRef.current.get(nodeId);
    if (existing) clearTimeout(existing);

    // Auto-expire after 600ms
    const timeout = setTimeout(() => {
      setAnimatedNodes((prev) => {
        const next = new Set(prev);
        next.delete(nodeId);
        return next;
      });
      timeoutsRef.current.delete(nodeId);
    }, 600);

    timeoutsRef.current.set(nodeId, timeout);
  }, []);

  // Track patch for animation (public API)
  const trackPatch = useCallback(
    (patch: ViewPatch) => {
      const nodeId = extractNodeIdFromPatch(patch);
      if (nodeId) {
        markNodeForAnimation(nodeId);
      }
    },
    [extractNodeIdFromPatch, markNodeForAnimation],
  );

  // Check if node should animate
  const isNodeAnimated = useCallback(
    (nodeId: string) => animatedNodes.has(nodeId),
    [animatedNodes],
  );

  // Cleanup on unmount
  useEffect(() => {
    const timeouts = timeoutsRef.current;
    return () => {
      timeouts.forEach((timeout) => clearTimeout(timeout));
      timeouts.clear();
    };
  }, []);

  const setStaticProp = useCallback(
    (nodeId: string, propKey: string, value: unknown) => {
      setTree((currentTree) => {
        const node = currentTree.nodes[nodeId];
        if (!node) return currentTree;

        return {
          ...currentTree,
          nodes: {
            ...currentTree.nodes,
            [nodeId]: {
              ...node,
              props: {
                ...node.props,
                [propKey]: value,
              },
            },
          },
          meta: {
            ...currentTree.meta,
            updatedAt: new Date().toISOString(),
          },
        };
      });
    },
    [],
  );

  const value = useMemo<DataViewContextValue>(
    () => ({
      source,
      tree,
      setSource,
      setTree,
      patch: (p: ViewPatch) => setTree((t) => applyPatch(t, p)),
      patchAll: (patches: ViewPatch[]) =>
        setTree((t) => applyPatches(t, patches)),
      setStaticProp,
      save: (key: string) => saveView(key, tree),
      load: (key: string) => {
        const loaded = loadView(key);
        if (loaded) {
          setTree(loaded);
          return true;
        }
        return false;
      },
      reset: () => setTree(createEmptyTree()),
      isNodeAnimated,
      trackPatch,
    }),
    [source, tree, setStaticProp, isNodeAnimated, trackPatch],
  );

  return (
    <DataViewContext.Provider value={value}>
      {children}
    </DataViewContext.Provider>
  );
}

// ============================================================================
// HOOKS
// ============================================================================

export function useDataView(): DataViewContextValue {
  const context = useContext(DataViewContext);
  if (!context) {
    throw new Error("useDataView must be used within DataViewProvider");
  }
  return context;
}

export function useSourceData(): SourceData {
  return useDataView().source;
}

export function useViewTree(): ViewTree {
  return useDataView().tree;
}
