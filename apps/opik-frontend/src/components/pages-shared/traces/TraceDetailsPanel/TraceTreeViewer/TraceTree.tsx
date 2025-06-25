import React, { useState, useMemo, useRef, useEffect } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useHotkeys } from "react-hotkeys-hook";

interface TreeNode {
  id: string;
  name: string;
  children?: TreeNode[];
  height: number;
}

interface FlattenedNode extends TreeNode {
  depth: number;
}

// Utility to generate a random tree up to a max count
const generateTree = (maxCount: number): TreeNode[] => {
  let count = 0;
  const makeNode = (depth = 0): TreeNode | null => {
    if (count >= maxCount) return null;
    const id = `${depth}-${count}`;
    const height = 50; // 50-200px
    count++;
    // randomly decide number of children if depth < 3
    const children: TreeNode[] = [];
    if (depth < 3 && Math.random() < 0.7) {
      const numChildren = Math.floor(Math.random() * 3) + 1;
      for (let i = 0; i < numChildren; i++) {
        const child = makeNode(depth + 1);
        if (child) children.push(child);
      }
    }
    return {
      id,
      name: `Node ${id}`,
      children: children.length ? children : undefined,
      height,
    };
  };

  const roots: TreeNode[] = [];
  while (count < maxCount) {
    const node = makeNode(0);
    if (node) roots.push(node);
  }
  return roots;
};

export const VirtualizedTreeList: React.FC = () => {
  const tree = useMemo(() => generateTree(10000), []);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [focusedIndex, setFocusedIndex] = useState(0);
  const parentRef = useRef<HTMLDivElement>(null);

  // Flatten visible nodes based on expanded state
  const flattened = useMemo<FlattenedNode[]>(() => {
    const list: FlattenedNode[] = [];
    const traverse = (nodes: TreeNode[], depth: number) => {
      nodes.forEach((node) => {
        list.push({ ...node, depth });
        if (node.children && expanded.has(node.id)) {
          traverse(node.children, depth + 1);
        }
      });
    };
    traverse(tree, 0);
    return list;
  }, [tree, expanded]);

  // Virtualizer setup
  const rowVirtualizer = useVirtualizer({
    count: flattened.length,
    getScrollElement: () => parentRef.current,
    estimateSize: (index) => flattened[index].height,
    overscan: 5,
  });

  // Toggle expand/collapse
  const toggleExpand = (id: string) => {
    setExpanded((prev) => {
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
      setFocusedIndex((i) => Math.max(0, i - 1));
    },
    { enableOnTags: ["INPUT", "TEXTAREA"] },
    [flattened],
  );
  useHotkeys(
    "down",
    (e) => {
      e.preventDefault();
      setFocusedIndex((i) => Math.min(flattened.length - 1, i + 1));
    },
    { enableOnTags: ["INPUT", "TEXTAREA"] },
    [flattened],
  );

  // Scroll focused item into view
  useEffect(() => {
    rowVirtualizer.scrollToIndex(focusedIndex, {
      align: "auto",
    });
  }, [focusedIndex, rowVirtualizer]);

  return (
    <div ref={parentRef} style={{ height: "100vh", overflow: "auto" }}>
      <div
        style={{
          height: rowVirtualizer.getTotalSize(),
          width: "100%",
          position: "relative",
        }}
      >
        {rowVirtualizer.getVirtualItems().map((virtualRow) => {
          const node = flattened[virtualRow.index];
          const isFocused = virtualRow.index === focusedIndex;
          return (
            <div
              key={node.id}
              style={{
                position: "absolute",
                top: virtualRow.start,
                left: node.depth * 20,
                height: node.height,
                width: `calc(100% - ${node.depth * 20}px)`, // indent
                lineHeight: `${node.height}px`,
                padding: "0 8px",
                boxSizing: "border-box",
                background: isFocused ? "#e6f7ff" : undefined,
                cursor: node.children ? "pointer" : "default",
                display: "flex",
                alignItems: "center",
                userSelect: "none",
              }}
              onClick={() => node.children && toggleExpand(node.id)}
            >
              {node.children && (
                <span style={{ marginRight: 4 }}>
                  {expanded.has(node.id) ? "▾" : "▸"}
                </span>
              )}
              <span>{node.name}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
};
