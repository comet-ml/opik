import React, { useEffect, useRef, useCallback } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useHotkeys } from "react-hotkeys-hook";

import useTreeDetailsStore from "@/components/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";

type VirtualizedTreeViewerProps = {
  scrollRef: React.RefObject<HTMLDivElement>;
  rowId: string;
  onRowIdChange: (id: string) => void;
};

const VirtualizedTreeViewer: React.FC<VirtualizedTreeViewerProps> = ({
  scrollRef,
  rowId,
  onRowIdChange,
}) => {
  const { flattenedTree, expandedTreeRows, expandToNode, toggleExpand } =
    useTreeDetailsStore();

  const selectedRowRef = useRef<{ current?: string; previous?: string }>({
    current: undefined,
    previous: undefined,
  });

  // Virtualizer setup
  const rowVirtualizer = useVirtualizer({
    count: flattenedTree.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 50, // TODO lala
    overscan: 5,
  });

  const selectRow = useCallback(
    (id: string) => {
      console.log("selectRow", id); // TODO lala remove
      const selectedIndex = flattenedTree.findIndex((node) => node.id === id);
      if (id !== selectedRowRef.current.current) {
        selectedRowRef.current.previous = selectedRowRef.current.current;
        selectedRowRef.current.current = id;
        onRowIdChange(id);
      }

      return selectedIndex;
    },
    [flattenedTree, onRowIdChange],
  );

  useEffect(() => {
    if (
      rowId !== selectedRowRef.current.current &&
      rowId !== selectedRowRef.current.previous
    ) {
      console.log("useEffect selectRow", rowId, selectedRowRef.current); //TODO lala remove
      expandToNode(rowId);
      const index = selectRow(rowId);
      if (index !== -1) {
        requestAnimationFrame(() =>
          rowVirtualizer.scrollToIndex(index, {
            behavior: "smooth",
          }),
        );
      }
    }
  }, [expandToNode, rowVirtualizer, selectRow, rowId]);

  // TODO lala remove
  useHotkeys(
    "space, enter",
    (e) => {
      e.preventDefault();
      const node = flattenedTree.find((node) => node.id === rowId);
      if (node?.children?.length) {
        toggleExpand(node.id);
      }
    },
    [flattenedTree, rowId],
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
        const node = flattenedTree[virtualRow.index];
        const isFocused = node.id === rowId;
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
                onClick={() => toggleExpand(node.id)}
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
};

export default VirtualizedTreeViewer;
