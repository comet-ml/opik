import React, { useCallback, useEffect, useRef } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import {
  ChevronRight,
  Clock,
  FoldVertical,
  Hash,
  TriangleAlert,
  UnfoldVertical,
} from "lucide-react";

import useTreeDetailsStore, {
  TreeNode,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BaseTraceDataTypeIcon from "@/shared/BaseTraceDataTypeIcon/BaseTraceDataTypeIcon";
import Loader from "@/shared/Loader/Loader";
import { formatDuration } from "@/lib/date";
import isNumber from "lodash/isNumber";

const ESTIMATED_ROW_HEIGHT = 36;

type AgentTraceTreeProps = {
  scrollRef: React.RefObject<HTMLDivElement>;
  spanCount: number;
  rowId: string;
  onSelectRow: (id: string) => void;
  isJobRunning: boolean;
};

const AgentTraceTree: React.FC<AgentTraceTreeProps> = ({
  scrollRef,
  spanCount,
  rowId,
  onSelectRow,
  isJobRunning,
}) => {
  const {
    flattenedTree,
    expandedTreeRows,
    fullExpandedSet,
    toggleExpand,
    toggleExpandAll,
  } = useTreeDetailsStore();

  const isAllExpanded = expandedTreeRows.size === fullExpandedSet.size;

  const selectedRowRef = useRef<{ current?: string; previous?: string }>({
    current: undefined,
    previous: undefined,
  });

  const rowVirtualizer = useVirtualizer({
    count: flattenedTree.length,
    getScrollElement: () => scrollRef.current,
    getItemKey: (index: number) => flattenedTree[index].id ?? index,
    estimateSize: () => ESTIMATED_ROW_HEIGHT,
    overscan: 5,
  });

  const selectRow = useCallback(
    (id: string) => {
      if (id !== selectedRowRef.current.current) {
        selectedRowRef.current.previous = selectedRowRef.current.current;
        selectedRowRef.current.current = id;
        onSelectRow(id);
      }
    },
    [onSelectRow],
  );

  useEffect(() => {
    if (
      rowId !== selectedRowRef.current.current &&
      rowId !== selectedRowRef.current.previous
    ) {
      selectRow(rowId);
    }
  }, [rowId, selectRow]);

  const renderRow = (
    node: TreeNode & { depth: number },
    isFocused: boolean,
  ) => {
    const isExpandable = Boolean(node.children?.length);
    const name = node.name || "NA";
    const duration = formatDuration(node.data.duration);
    const tokens = node.data.tokens;

    return (
      <div
        className={cn(
          "flex items-center gap-1 px-1.5 py-2 cursor-pointer rounded-md hover:bg-primary-foreground",
          { "bg-primary-foreground": isFocused },
        )}
        style={{ paddingLeft: node.depth * 12 + 6 }}
        onClick={() => selectRow(node.id)}
      >
        <div className="mr-0.5 flex h-5 w-4 shrink-0 items-center justify-center">
          {isExpandable && (
            <Button
              variant="ghost"
              size="icon-3xs"
              onClick={(e) => {
                e.stopPropagation();
                toggleExpand(node.id);
              }}
            >
              <ChevronRight
                className={cn({
                  "transform rotate-90": expandedTreeRows.has(node.id),
                })}
              />
            </Button>
          )}
        </div>
        <BaseTraceDataTypeIcon type={node.data.type} />
        <TooltipWrapper content={name}>
          <span
            className={cn(
              "truncate text-foreground-secondary min-w-0",
              isFocused ? "comet-body-s-accented" : "comet-body-s",
            )}
          >
            {name}
          </span>
        </TooltipWrapper>
        <div className="ml-auto flex shrink-0 items-center gap-2">
          {node.data.hasError && (
            <TooltipWrapper
              content={node.data.error_info?.message ?? "Has error"}
            >
              <div className="flex size-5 items-center justify-center rounded-sm bg-[var(--error-indicator-background)]">
                <TriangleAlert className="size-3 text-[var(--error-indicator-text)]" />
              </div>
            </TooltipWrapper>
          )}
          {Boolean(duration) && (
            <span className="comet-body-xs flex items-center gap-0.5 text-muted-slate">
              <Clock className="size-3" />
              {duration}
            </span>
          )}
          {isNumber(tokens) && (
            <span className="comet-body-xs flex items-center gap-0.5 text-muted-slate">
              <Hash className="size-3" />
              {tokens}
            </span>
          )}
        </div>
      </div>
    );
  };

  return (
    <div className="flex flex-col">
      <div className="sticky top-0 z-10 flex items-center justify-between bg-background px-4 pb-2 pt-4">
        <div className="flex items-center gap-1.5">
          <span className="comet-body-s-accented">Trajectory</span>
          <span className="comet-body-xs text-muted-slate">
            {spanCount} {spanCount === 1 ? "span" : "spans"}
          </span>
        </div>
        <TooltipWrapper content={isAllExpanded ? "Collapse all" : "Expand all"}>
          <Button onClick={toggleExpandAll} variant="outline" size="icon-2xs">
            {isAllExpanded ? <FoldVertical /> : <UnfoldVertical />}
          </Button>
        </TooltipWrapper>
      </div>

      <div className="w-full px-2">
        <div
          className="relative w-full"
          style={{ height: rowVirtualizer.getTotalSize() }}
        >
          {rowVirtualizer.getVirtualItems().map((virtualRow) => {
            const node = flattenedTree[virtualRow.index];
            const isFocused = node.id === rowId;

            return (
              <div
                key={node.id}
                className="absolute left-0 w-full"
                style={{
                  top: virtualRow.start,
                  height: virtualRow.size,
                }}
              >
                {renderRow(node as TreeNode & { depth: number }, isFocused)}
              </div>
            );
          })}
        </div>
      </div>

      {isJobRunning && (
        <div className="flex items-center justify-center gap-2 py-4 text-muted-slate">
          <Loader className="size-3.5" />
          <span className="comet-body-xs">Running agent...</span>
        </div>
      )}
    </div>
  );
};

export default AgentTraceTree;
