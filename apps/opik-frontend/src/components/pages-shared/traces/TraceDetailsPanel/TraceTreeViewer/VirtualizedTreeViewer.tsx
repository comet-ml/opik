import React, {
  useMemo,
  useEffect,
  useRef,
  useCallback,
  forwardRef,
  useImperativeHandle,
} from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useHotkeys } from "react-hotkeys-hook";

import useTreeDetailsStore from "@/components/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { BASE_TRACE_DATA_TYPE, Span } from "@/types/traces";

// TODO lala optimize
const getNodePath = (
  nodes: TreeNode[],
  targetId: string,
  path: string[] = [],
): string[] | null => {
  for (const node of nodes) {
    if (node.id === targetId) {
      return [...path, node.id];
    }
    if (node.children) {
      const foundPath = getNodePath(node.children, targetId, [
        ...path,
        node.id,
      ]);
      if (foundPath) return foundPath;
    }
  }
  return null;
};

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

interface FlattenedNode extends TreeNode {
  depth: number;
}

export type VirtualizedTreeViewerRef = {
  isCollapsedAll: () => boolean;
  toggleExpandAll: () => void;
};

type VirtualizedTreeViewerProps = {
  tree: TreeNode[];
  scrollRef: React.RefObject<HTMLDivElement>;
  rowId: string;
  onRowIdChange: (id: string) => void;
};

const VirtualizedTreeViewer = forwardRef<
  VirtualizedTreeViewerRef,
  VirtualizedTreeViewerProps
>(({ tree, scrollRef, rowId, onRowIdChange }, ref) => {
  const {
    expandedTreeRows,
    setExpandedTreeRows,
    focusedRowIndex,
    setFocusedRowIndex,
  } = useTreeDetailsStore();

  const initiallyExpanded = useRef<{
    id: string;
    lengthMap: Record<string, number>;
  }>({
    id: "",
    lengthMap: {},
  });
  const selectedRowRef = useRef<string | undefined>();

  useImperativeHandle(ref, () => ({
    isCollapsedAll: () => expandedTreeRows.size === 0,
    toggleExpandAll: () =>
      expandedTreeRows.size === 0 ? expandAll() : collapseAll(),
  }));

  const expandAll = () => {
    const allIds = new Set<string>();
    const collectIds = (nodes: TreeNode[]) => {
      nodes.forEach((node) => {
        if (node.children) {
          allIds.add(node.id);
          collectIds(node.children);
        }
      });
    };
    collectIds(tree);
    setExpandedTreeRows(allIds);
  };

  const collapseAll = () => {
    setExpandedTreeRows(new Set());
  };

  // Flatten visible nodes based on expandedTreeRows state
  const flattened = useMemo<FlattenedNode[]>(() => {
    const list: FlattenedNode[] = [];
    const traverse = (nodes: TreeNode[], depth: number) => {
      nodes.forEach((node) => {
        list.push({ ...node, depth });
        if (node.children && expandedTreeRows.has(node.id)) {
          traverse(node.children, depth + 1);
        }
      });
    };
    traverse(tree, 0);
    return list;
  }, [tree, expandedTreeRows]);

  // Virtualizer setup
  const rowVirtualizer = useVirtualizer({
    count: flattened.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 50, // TODO lala
    overscan: 5,
  });

  const expandToNode = useCallback(
    (id: string) => {
      const path = getNodePath(tree, id);
      if (path) {
        setExpandedTreeRows((prev) => {
          const next = new Set(prev);
          path.forEach((nodeId) => next.add(nodeId));
          return next;
        });
      }
    },
    [setExpandedTreeRows, tree],
  );

  const selectRow = useCallback(
    (id: string) => {
      const selectedIndex = flattened.findIndex((node) => node.id === id);
      if (selectedIndex !== -1) {
        setFocusedRowIndex(selectedIndex);
      }
      if (id !== selectedRowRef.current) {
        selectedRowRef.current = id;
        onRowIdChange(id);
      }
    },
    [flattened, onRowIdChange, setFocusedRowIndex],
  );

  useEffect(() => {
    const root = tree[0];
    if (
      (root && initiallyExpanded.current.id !== root.id) ||
      (initiallyExpanded.current.id === root.id &&
        initiallyExpanded.current.lengthMap[root.id] !== root.children?.length)
    ) {
      initiallyExpanded.current.id = root.id;
      initiallyExpanded.current.lengthMap[root.id] = root.children?.length || 0;
      console.log("on root change"); //TODO lala remove

      const level = 3;
      const levelExpandedSet = new Set<string>();
      const collectIds = (nodes: TreeNode[], currentDepth: number) => {
        nodes.forEach((node) => {
          if (node.children && currentDepth < level) {
            levelExpandedSet.add(node.id);
            collectIds(node.children, currentDepth + 1);
          }
        });
      };
      collectIds(tree, 0);
      setExpandedTreeRows(levelExpandedSet);
    }
  }, [setExpandedTreeRows, tree]);

  useEffect(() => {
    if (rowId !== selectedRowRef.current) {
      expandToNode(rowId);
      selectRow(rowId);
      setFocusedRowIndex((index) => {
        setTimeout(() => {
          console.log(rowId);
          rowVirtualizer.scrollToIndex(index, {
            align: "start",
            behavior: "smooth",
          });
        }, 0);

        return index;
      });
    }
  }, [expandToNode, rowVirtualizer, selectRow, rowId, setFocusedRowIndex]);

  // Toggle expand/collapse
  const toggleExpand = (id: string) => {
    setExpandedTreeRows((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  // Keyboard navigation
  useHotkeys(
    "up",
    (e) => {
      e.preventDefault();
      setFocusedRowIndex((i) => Math.max(0, i - 1));
    },
    [flattened],
  );
  useHotkeys(
    "down",
    (e) => {
      e.preventDefault();
      setFocusedRowIndex((i) => Math.min(flattened.length - 1, i + 1));
    },
    [flattened],
  );
  useHotkeys(
    "space, enter",
    (e) => {
      e.preventDefault();
      const node = flattened[focusedRowIndex];
      if (node.children) {
        toggleExpand(node.id);
      }
    },
    [flattened, focusedRowIndex],
  );

  return (
    <div
      style={{
        height: rowVirtualizer.getTotalSize(),
        width: "100%",
        position: "relative",
      }}
    >
      {rowVirtualizer.getVirtualItems().map((virtualRow) => {
        const node = flattened[virtualRow.index];
        const isFocused = virtualRow.index === focusedRowIndex;
        const isExpandable = Boolean(node.children?.length);

        return (
          <div
            key={node.id}
            style={{
              position: "absolute",
              top: virtualRow.start,
              left: node.depth * 20,
              height: 50,
              width: `calc(100% - ${node.depth * 20}px)`, // indent
              lineHeight: `50px`,
              padding: "0 8px",
              boxSizing: "border-box",
              background: isFocused ? "#e6f7ff" : undefined,
              cursor: isExpandable ? "pointer" : "default",
              display: "flex",
              alignItems: "center",
              userSelect: "none",
            }}
            onClick={() => selectRow(node.id)}
          >
            {isExpandable && (
              <div
                className="mr-2 flex size-4 cursor-pointer items-center justify-center rounded border"
                onClick={(event) => {
                  toggleExpand(node.id);
                  event.stopPropagation();
                }}
              >
                {expandedTreeRows.has(node.id) ? "▾" : "▸"}
              </div>
            )}
            <span>{node.name}</span>
          </div>
        );
      })}
    </div>
  );
});

VirtualizedTreeViewer.displayName = "VirtualizedTreeViewer";

export default VirtualizedTreeViewer;
