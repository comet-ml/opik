import React, { useEffect, useRef, useCallback, useMemo } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useHotkeys } from "react-hotkeys-hook";
import {
  ArrowRightLeft,
  Brain,
  ChevronRight,
  Clock,
  Coins,
  Hash,
  MessageSquareMore,
  PenLine,
  Tag,
} from "lucide-react";
import ErrorTriangle from "@/icons/error-triangle.svg?react";

import useTreeDetailsStore, {
  TREE_DATABLOCK_TYPE,
  TreeNode,
  TreeNodeConfig,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BaseTraceDataTypeIcon from "@/shared/BaseTraceDataTypeIcon/BaseTraceDataTypeIcon";
import { GuardrailResult } from "@/types/guardrails";
import get from "lodash/get";
import { formatDate, formatDuration } from "@/lib/date";
import isNumber from "lodash/isNumber";
import isUndefined from "lodash/isUndefined";
import { formatCost } from "@/lib/money";
import FeedbackScoreHoverCard from "@/shared/FeedbackScoreTag/FeedbackScoreHoverCard";
import UserCommentHoverList from "@/shared/UserComment/UserCommentHoverList";
import TagsHoverCard from "@/shared/TagsHoverCard/TagsHoverCard";
import {
  TRACE_TYPE_FOR_TREE,
  TRACE_TYPE_COLORS_MAP,
  TRACE_COLOR_TYPE,
} from "@/constants/traces";

const EXPAND_HOTKEYS = ["⏎"];
const INDENT_PX = 24;
const ICON_OFFSET = 8;

const VerticalLine: React.FC<{
  left: number;
  top?: number | string;
  bottom?: number | string;
  height?: number | string;
}> = ({ left, top = 0, bottom, height }) => (
  <div
    className="absolute w-px bg-tree-line"
    style={{ left, top, bottom, height }}
  />
);

const TreeConnectors: React.FC<{
  depth: number;
  connectors: boolean[];
  isExpanded: boolean;
  isExpandable: boolean;
}> = ({ depth, connectors, isExpanded, isExpandable }) => (
  <div className="pointer-events-none absolute inset-0">
    {depth > 0 &&
      Array.from({ length: depth }, (_, i) => {
        const d = i + 1;
        const isOwnDepth = d === depth;
        const hasContinuation = connectors[i] ?? false;
        const parentCenterX = ICON_OFFSET + (d - 1) * INDENT_PX + ICON_OFFSET;
        const childLeft = ICON_OFFSET + d * INDENT_PX;
        const branchWidth = childLeft - parentCenterX;

        if (!isOwnDepth && !hasContinuation) return null;

        if (!isOwnDepth) {
          return <VerticalLine key={d} left={parentCenterX} height="100%" />;
        }

        return (
          <React.Fragment key={d}>
            {hasContinuation && (
              <VerticalLine left={parentCenterX} height="100%" />
            )}
            <div
              className="absolute rounded-bl-md border-b border-l border-tree-line"
              style={{
                left: parentCenterX,
                top: hasContinuation ? 6 : 0,
                height: hasContinuation ? 6 : 12,
                width: branchWidth,
              }}
            />
          </React.Fragment>
        );
      })}
    {isExpandable && isExpanded && (
      <VerticalLine
        left={ICON_OFFSET + depth * INDENT_PX + ICON_OFFSET}
        top={20}
        bottom={0}
      />
    )}
  </div>
);

type VirtualizedTreeViewerProps = {
  scrollRef: React.RefObject<HTMLDivElement>;
  config: TreeNodeConfig;
  rowId: string;
  onRowIdChange: (id: string) => void;
};

const VirtualizedTreeViewer: React.FC<VirtualizedTreeViewerProps> = ({
  scrollRef,
  config,
  rowId,
  onRowIdChange,
}) => {
  const { flattenedTree, expandedTreeRows, toggleExpand } =
    useTreeDetailsStore();

  const [scrollToRowId, setScrollToRowId] = React.useState<
    string | undefined
  >();

  const selectedRowRef = useRef<{ current?: string; previous?: string }>({
    current: undefined,
    previous: undefined,
  });

  // For each node, track which ancestor depth levels have a continuing vertical line
  const connectorInfo = useMemo(() => {
    const info: boolean[][] = new Array(flattenedTree.length);
    const hasFollowing: boolean[] = [];

    for (let i = flattenedTree.length - 1; i >= 0; i--) {
      const node = flattenedTree[i];
      const depths: boolean[] = [];

      for (let d = 1; d <= node.depth; d++) {
        depths.push(hasFollowing[d] ?? false);
      }

      info[i] = depths;
      hasFollowing[node.depth] = true;
      for (let d = node.depth + 1; d < hasFollowing.length; d++) {
        hasFollowing[d] = false;
      }
    }

    return info;
  }, [flattenedTree]);

  const hasDurationTimeline = config[TREE_DATABLOCK_TYPE.DURATION_TIMELINE];

  const rowVirtualizer = useVirtualizer({
    count: flattenedTree.length,
    getScrollElement: () => scrollRef.current,
    getItemKey: (index: number) => flattenedTree[index].id ?? index,
    estimateSize: () => 38,
    scrollPaddingEnd: 96,
    overscan: 5,
    measureElement: (el) => el.getBoundingClientRect().height,
  });

  const selectRow = useCallback(
    (id: string) => {
      if (id !== selectedRowRef.current.current) {
        selectedRowRef.current.previous = selectedRowRef.current.current;
        selectedRowRef.current.current = id;
        onRowIdChange(id);
      }
    },
    [onRowIdChange],
  );

  useEffect(() => {
    if (
      rowId !== selectedRowRef.current.current &&
      rowId !== selectedRowRef.current.previous
    ) {
      selectRow(rowId);
      setScrollToRowId(rowId);
    }
  }, [rowVirtualizer, selectRow, rowId]);

  useEffect(() => {
    if (scrollToRowId) {
      const index = flattenedTree.findIndex(
        (node) => node.id === scrollToRowId,
      );
      if (index !== -1) {
        rowVirtualizer.scrollToIndex(index, {
          behavior: "smooth",
        });
        setScrollToRowId(undefined);
      }
    }
  }, [flattenedTree, rowVirtualizer, scrollToRowId]);

  useHotkeys(
    "enter",
    (e) => {
      e.preventDefault();
      const node = flattenedTree.find((node) => node.id === rowId);
      if (node?.children?.length) {
        toggleExpand(node.id);
      }
    },
    [flattenedTree, rowId],
  );

  const nodeHasDetails = useCallback(
    (node: TreeNode) => {
      const guardrailStatus = get(node.data?.output, "guardrail_result", null);
      const {
        tokens,
        comments,
        tags,
        model,
        provider,
        feedback_scores: feedbackScores,
        span_feedback_scores: spanFeedbackScores,
        total_estimated_cost: estimatedCost,
        type,
      } = node.data;
      const isTrace = type === TRACE_TYPE_FOR_TREE;
      const promptTokens = node.data.usage?.prompt_tokens;
      const completionTokens = node.data.usage?.completion_tokens;

      return (
        guardrailStatus !== null ||
        (config[TREE_DATABLOCK_TYPE.DURATION] && node.data.duration) ||
        (config[TREE_DATABLOCK_TYPE.MODEL] && (model || provider)) ||
        (config[TREE_DATABLOCK_TYPE.ESTIMATED_COST] &&
          !isUndefined(estimatedCost)) ||
        (config[TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS] && isNumber(tokens)) ||
        (config[TREE_DATABLOCK_TYPE.TOKENS_BREAKDOWN] &&
          isNumber(promptTokens) &&
          isNumber(completionTokens)) ||
        (config[TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES] &&
          (Boolean(feedbackScores?.length) ||
            (isTrace && Boolean(spanFeedbackScores?.length)))) ||
        (config[TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS] &&
          Boolean(comments?.length)) ||
        (config[TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS] && Boolean(tags?.length))
      );
    },
    [config],
  );

  const renderDetailsContainer = (node: TreeNode) => {
    const guardrailStatus = get(node.data?.output, "guardrail_result", null);

    const duration = formatDuration(node.data.duration);
    const start_time = node.data.start_time
      ? formatDate(node.data.start_time, { includeSeconds: true })
      : "";
    const end_time = node.data.end_time
      ? formatDate(node.data.end_time, { includeSeconds: true })
      : "";

    const durationTooltip = (
      <div>
        Duration in seconds: {duration}
        <p>
          {start_time} {end_time ? ` - ${end_time}` : ""}
        </p>
      </div>
    );

    const {
      tokens,
      comments,
      tags,
      model,
      provider,
      feedback_scores: feedbackScores,
      span_feedback_scores: spanFeedbackScores,
      total_estimated_cost: estimatedCost,
      type,
    } = node.data;
    const isTrace = type === TRACE_TYPE_FOR_TREE;

    const promptTokens = node.data.usage?.prompt_tokens;
    const completionTokens = node.data.usage?.completion_tokens;

    const tokensBreakdownTooltip = node.data.usage ? (
      <div className="space-y-2">
        <div className="space-y-0.5">
          <div className="text-sm font-medium">Token Usage</div>
          <div className="text-xs">
            <span className="font-medium">Input:</span> {promptTokens} |{" "}
            <span className="font-medium">Output:</span> {completionTokens} |{" "}
            <span className="font-medium">Total:</span> {tokens}
          </div>
        </div>
        <div className="space-y-1">
          <div className="text-xs font-medium opacity-75">Breakdown:</div>
          <pre className="whitespace-pre font-mono text-xs opacity-75">
            {Object.entries(node.data.usage)
              .map(([key, value]) => `"${key}": ${value}`)
              .join(",\n")}
          </pre>
        </div>
      </div>
    ) : (
      ""
    );

    return (
      <div className="flex flex-wrap items-center gap-x-3 overflow-x-hidden">
        {Boolean(guardrailStatus !== null) && (
          <TooltipWrapper
            content={
              guardrailStatus === GuardrailResult.PASSED
                ? "Guardrails passed"
                : "Guardrails failed"
            }
          >
            <div
              className={cn("size-2 rounded-full shrink-0 mt-0.5", {
                "bg-emerald-500": guardrailStatus === GuardrailResult.PASSED,
                "bg-rose-500": guardrailStatus === GuardrailResult.FAILED,
              })}
            ></div>
          </TooltipWrapper>
        )}
        {config[TREE_DATABLOCK_TYPE.DURATION] && (
          <TooltipWrapper content={durationTooltip}>
            <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
              <Clock className="size-3 shrink-0" /> {duration}
            </div>
          </TooltipWrapper>
        )}
        {config[TREE_DATABLOCK_TYPE.MODEL] && (model || provider) && (
          <TooltipWrapper
            content={`Model: ${model || "NA"}, Provider: ${provider || "NA"}`}
          >
            <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
              <Brain className="size-3 shrink-0" />{" "}
              <div className="truncate">
                {provider} {model}
              </div>
            </div>
          </TooltipWrapper>
        )}
        {config[TREE_DATABLOCK_TYPE.ESTIMATED_COST] &&
          !isUndefined(estimatedCost) && (
            <TooltipWrapper
              content={`Estimated cost ${formatCost(estimatedCost, {
                modifier: "full",
              })}`}
            >
              <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
                <Coins className="size-3 shrink-0" />{" "}
                {formatCost(estimatedCost)}
              </div>
            </TooltipWrapper>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS] && isNumber(tokens) && (
          <TooltipWrapper content={`Total amount of tokens: ${tokens}`}>
            <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
              <Hash className="size-3 shrink-0" /> {tokens}
            </div>
          </TooltipWrapper>
        )}
        {config[TREE_DATABLOCK_TYPE.TOKENS_BREAKDOWN] &&
          isNumber(promptTokens) &&
          isNumber(completionTokens) && (
            <TooltipWrapper content={tokensBreakdownTooltip}>
              <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
                <ArrowRightLeft className="size-3 shrink-0" /> {promptTokens}/
                {completionTokens}
              </div>
            </TooltipWrapper>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES] &&
          Boolean(feedbackScores?.length) && (
            <FeedbackScoreHoverCard scores={feedbackScores!}>
              <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
                <PenLine className="size-3 shrink-0" /> {feedbackScores!.length}
              </div>
            </FeedbackScoreHoverCard>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES] &&
          isTrace &&
          Boolean(spanFeedbackScores?.length) && (
            <FeedbackScoreHoverCard scores={spanFeedbackScores!}>
              <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
                <PenLine className="size-3 shrink-0" />{" "}
                {spanFeedbackScores!.length} span
              </div>
            </FeedbackScoreHoverCard>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS] &&
          Boolean(comments?.length) && (
            <UserCommentHoverList commentsList={comments}>
              <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
                <MessageSquareMore className="size-3 shrink-0" />{" "}
                {comments.length}
              </div>
            </UserCommentHoverList>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS] &&
          Boolean(tags?.length) && (
            <TagsHoverCard tags={tags} tagVariant="gray">
              <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
                <Tag className="size-3 shrink-0" /> {tags.length}
              </div>
            </TagsHoverCard>
          )}
      </div>
    );
  };

  return (
    <div className="w-full">
      <div
        className="relative w-full"
        style={{
          height: rowVirtualizer.getTotalSize(),
        }}
      >
        {rowVirtualizer.getVirtualItems().map((virtualRow) => {
          const node = flattenedTree[virtualRow.index];
          const isFocused = node.id === rowId;
          const isExpandable = Boolean(node.children?.length);
          const isOutOfSearch = node.data.isInSearch === false;
          const name = node.name || "NA";

          const typeColors =
            TRACE_TYPE_COLORS_MAP[node.data.type as TRACE_COLOR_TYPE];

          return (
            <div
              key={node.id}
              ref={rowVirtualizer.measureElement}
              data-index={virtualRow.index}
              className={cn(
                "absolute left-0 flex w-full flex-col py-1 pl-2 pr-4 cursor-pointer border-l-8 border-transparent",
                "hover:bg-[var(--row-bg)]",
                {
                  "bg-[var(--row-bg)] border-[var(--row-color)]": isFocused,
                  "opacity-50": isOutOfSearch,
                },
              )}
              style={
                {
                  "--row-bg": typeColors?.bg,
                  "--row-color": typeColors?.color,
                  top: virtualRow.start,
                } as React.CSSProperties
              }
              onClick={() => selectRow(node.id)}
            >
              <TreeConnectors
                depth={node.depth}
                connectors={connectorInfo[virtualRow.index] ?? []}
                isExpandable={isExpandable}
                isExpanded={expandedTreeRows.has(node.id)}
              />
              <div
                className="flex items-center"
                style={{
                  paddingLeft: node.depth * INDENT_PX,
                }}
              >
                <BaseTraceDataTypeIcon type={node.data.type} />
                <TooltipWrapper content={name}>
                  <span
                    className={cn(
                      "ml-2 truncate text-foreground-secondary",
                      isFocused ? "comet-body-xs-accented" : "comet-body-xs",
                    )}
                  >
                    {name}
                  </span>
                </TooltipWrapper>
                <div className="flex-auto" />
                {node.data.hasError && (
                  <TooltipWrapper
                    content={node.data.error_info?.message ?? "Has error"}
                  >
                    <span className="flex size-4 shrink-0 items-center justify-center text-destructive">
                      <ErrorTriangle width={12} height={12} />
                    </span>
                  </TooltipWrapper>
                )}
                {isExpandable ? (
                  <TooltipWrapper
                    content="Expand/Collapse"
                    hotkeys={EXPAND_HOTKEYS}
                  >
                    <Button
                      variant="ghost"
                      size="icon-3xs"
                      className="ml-1"
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
                  </TooltipWrapper>
                ) : (
                  <div className="ml-1 size-4 shrink-0" />
                )}
              </div>
              {nodeHasDetails(node) && (
                <div
                  style={{
                    paddingLeft: node.depth * INDENT_PX + INDENT_PX,
                  }}
                >
                  {renderDetailsContainer(node)}
                </div>
              )}
              {hasDurationTimeline && node.data.maxDuration > 0 && (
                <div
                  className="px-2 py-1"
                  style={{ paddingLeft: node.depth * INDENT_PX + INDENT_PX }}
                >
                  <div className="relative h-1 w-full">
                    <div className="absolute inset-x-0 top-[1.5px] h-px bg-border" />
                    <div
                      className="absolute top-0 h-1 rounded-full"
                      style={{
                        background: node.data.spanColor,
                        width:
                          Math.min(
                            (node.data.duration / node.data.maxDuration) * 100,
                            100,
                          ) + "%",
                        left:
                          Math.max(
                            ((node.data.startTimestamp -
                              node.data.maxStartTime) /
                              node.data.maxDuration) *
                              100,
                            0,
                          ) + "%",
                      }}
                    />
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default VirtualizedTreeViewer;
