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
  TriangleAlert,
} from "lucide-react";

import useTreeDetailsStore, {
  TREE_DATABLOCK_TYPE,
  TreeNode,
  TreeNodeConfig,
} from "@/components/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";
import { GuardrailResult } from "@/types/guardrails";
import get from "lodash/get";
import { formatDate, formatDuration } from "@/lib/date";
import isNumber from "lodash/isNumber";
import isUndefined from "lodash/isUndefined";
import { formatCost } from "@/lib/money";
import FeedbackScoreHoverCard from "@/components/shared/FeedbackScoreTag/FeedbackScoreHoverCard";
import UserCommentHoverList from "@/components/pages-shared/traces/UserComment/UserCommentHoverList";
import TagsHoverCard from "@/components/shared/TagsHoverCard/TagsHoverCard";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";

const EXPAND_HOTKEYS = ["⏎"];
const DETAILS_SECTION_COMPONENTS = [
  TREE_DATABLOCK_TYPE.DURATION,
  TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS,
  TREE_DATABLOCK_TYPE.TOKENS_BREAKDOWN,
  TREE_DATABLOCK_TYPE.ESTIMATED_COST,
  TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES,
  TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS,
  TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS,
  TREE_DATABLOCK_TYPE.MODEL,
];

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

  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const [scrollToRowId, setScrollToRowId] = React.useState<
    string | undefined
  >();

  const selectedRowRef = useRef<{ current?: string; previous?: string }>({
    current: undefined,
    previous: undefined,
  });

  const hasDurationTimeline = config[TREE_DATABLOCK_TYPE.DURATION_TIMELINE];
  const hasOtherConfig = useMemo(
    () =>
      (isGuardrailsEnabled
        ? [TREE_DATABLOCK_TYPE.GUARDRAILS, ...DETAILS_SECTION_COMPONENTS]
        : DETAILS_SECTION_COMPONENTS
      ).reduce((acc, block) => acc || config[block], false),
    [config, isGuardrailsEnabled],
  );

  const estimatedHeight =
    36 + (hasDurationTimeline ? 18 : 0) + (hasOtherConfig ? 30 : 0);

  const rowVirtualizer = useVirtualizer({
    count: flattenedTree.length,
    getScrollElement: () => scrollRef.current,
    getItemKey: (index: number) => flattenedTree[index].id ?? index,
    estimateSize: () => estimatedHeight,
    scrollPaddingEnd: 96,
    overscan: 5,
  });

  useEffect(() => {
    rowVirtualizer?.measure();
  }, [estimatedHeight, rowVirtualizer]);

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

  const renderDurationTimeline = (node: TreeNode) => {
    const widthPercentage = Math.min(
      (node.data.duration / node.data.maxDuration) * 100,
      100,
    );

    const offset = node.data.startTimestamp - node.data.maxStartTime;
    const offsetPercentage = Math.max(
      (offset / node.data.maxDuration) * 100,
      0,
    );

    return (
      <div className="w-full pb-1 pl-4 pt-1.5">
        <div className="relative w-full">
          <div className="absolute inset-x-0 top-[0.5px] h-px bg-border" />
          <div
            className="absolute top-0 h-0.5 rounded-full transition-[width,left] duration-500 ease-in-out"
            style={{
              background: node.data.spanColor,
              width: widthPercentage + "%",
              left: offsetPercentage + "%",
            }}
          />
        </div>
      </div>
    );
  };

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
      <div className="flex h-5 items-center gap-3 overflow-x-hidden">
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
            <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
              <Clock className="size-3 shrink-0" /> {duration}
            </div>
          </TooltipWrapper>
        )}
        {config[TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS] && isNumber(tokens) && (
          <TooltipWrapper content={`Total amount of tokens: ${tokens}`}>
            <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
              <Hash className="size-3 shrink-0" /> {tokens}
            </div>
          </TooltipWrapper>
        )}
        {config[TREE_DATABLOCK_TYPE.TOKENS_BREAKDOWN] &&
          isNumber(promptTokens) &&
          isNumber(completionTokens) && (
            <TooltipWrapper content={tokensBreakdownTooltip}>
              <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
                <ArrowRightLeft className="size-3 shrink-0" /> {promptTokens}/
                {completionTokens}
              </div>
            </TooltipWrapper>
          )}
        {config[TREE_DATABLOCK_TYPE.ESTIMATED_COST] &&
          !isUndefined(estimatedCost) && (
            <TooltipWrapper
              content={`Estimated cost ${formatCost(estimatedCost)}`}
            >
              <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
                <Coins className="size-3 shrink-0" />{" "}
                {formatCost(estimatedCost, { modifier: "short" })}
              </div>
            </TooltipWrapper>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES] &&
          Boolean(feedbackScores?.length) && (
            <FeedbackScoreHoverCard scores={feedbackScores!}>
              <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
                <PenLine className="size-3 shrink-0" /> {feedbackScores!.length}
              </div>
            </FeedbackScoreHoverCard>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES] &&
          isTrace &&
          Boolean(spanFeedbackScores?.length) && (
            <FeedbackScoreHoverCard scores={spanFeedbackScores!}>
              <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
                <PenLine className="size-3 shrink-0" />{" "}
                {spanFeedbackScores!.length} span
              </div>
            </FeedbackScoreHoverCard>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS] &&
          Boolean(comments?.length) && (
            <UserCommentHoverList commentsList={comments}>
              <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
                <MessageSquareMore className="size-3 shrink-0" />{" "}
                {comments.length}
              </div>
            </UserCommentHoverList>
          )}
        {config[TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS] &&
          Boolean(tags?.length) && (
            <TagsHoverCard tags={tags}>
              <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
                <Tag className="size-3 shrink-0" /> {tags.length}
              </div>
            </TagsHoverCard>
          )}
        {config[TREE_DATABLOCK_TYPE.MODEL] && (model || provider) && (
          <TooltipWrapper
            content={`Model: ${model || "NA"}, Provider: ${provider || "NA"}`}
          >
            <div className="comet-body-xs-accented flex items-center gap-1 text-muted-slate">
              <Brain className="size-3 shrink-0" />{" "}
              <div className="truncate">
                {provider} {model}
              </div>
            </div>
          </TooltipWrapper>
        )}
      </div>
    );
  };

  return (
    <div className="w-full px-4">
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

          return (
            <div
              key={node.id}
              className={cn(
                "absolute left-0 flex w-full flex-col gap-1.5 px-1.5 py-2 cursor-pointer rounded-md hover:bg-primary-foreground",
                {
                  "bg-primary-foreground": isFocused,
                  "opacity-50": isOutOfSearch,
                },
              )}
              style={{
                top: virtualRow.start,
                height: virtualRow.size,
              }}
              onClick={() => selectRow(node.id)}
            >
              <div
                className="flex"
                style={{
                  paddingLeft: node.depth * 12,
                }}
              >
                <div className="mr-1 flex h-5 w-4 shrink-0 items-center justify-center">
                  {isExpandable && (
                    <TooltipWrapper
                      content="Expand/Collapse"
                      hotkeys={EXPAND_HOTKEYS}
                    >
                      <Button
                        variant="ghost"
                        size="icon-3xs"
                        onClick={() => toggleExpand(node.id)}
                      >
                        <ChevronRight
                          className={cn({
                            "transform rotate-90": expandedTreeRows.has(
                              node.id,
                            ),
                          })}
                        />
                      </Button>
                    </TooltipWrapper>
                  )}
                </div>
                <div className="flex min-w-1 flex-auto flex-col justify-stretch gap-2">
                  <div className="flex items-center gap-2">
                    <BaseTraceDataTypeIcon type={node.data.type} />
                    <TooltipWrapper content={name}>
                      <span
                        className={cn(
                          "truncate text-foreground-secondary",
                          isFocused ? "comet-body-s-accented" : "comet-body-s",
                        )}
                      >
                        {name}
                      </span>
                    </TooltipWrapper>
                    {node.data.hasError && (
                      <>
                        <div className="flex-auto" />
                        <TooltipWrapper
                          content={node.data.error_info?.message ?? "Has error"}
                        >
                          <div className="flex size-5 items-center justify-center rounded-sm bg-[var(--error-indicator-background)]">
                            <TriangleAlert className="size-3 text-[var(--error-indicator-text)]" />
                          </div>
                        </TooltipWrapper>
                      </>
                    )}
                  </div>
                  {hasOtherConfig && renderDetailsContainer(node)}
                </div>
              </div>
              {hasDurationTimeline && renderDurationTimeline(node)}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default VirtualizedTreeViewer;
