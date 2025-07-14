import React, { useCallback, useEffect, useMemo, useRef } from "react";
import useLocalStorageState from "use-local-storage-state";
import { FoldVertical, UnfoldVertical } from "lucide-react";

import {
  addAllParentIds,
  constructDataMapAndSearchIds,
  filterFunction,
} from "./helpers";
import { OnChangeFn } from "@/types/shared";
import { Span, Trace } from "@/types/traces";
import { Filters } from "@/types/filters";
import { SPANS_COLORS_MAP, TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { Button } from "@/components/ui/button";
import NoData from "@/components/shared/NoData/NoData";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import VirtualizedTreeViewer from "@/components/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/VirtualizedTreeViewer";
import useTreeDetailsStore, {
  TREE_DATABLOCK_TYPE,
  TreeNode,
  TreeNodeConfig,
} from "@/components/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import SpanDetailsButton from "@/components/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/SpanDetailsButton";

const SELECTED_TREE_DATABLOCKS_KEY = "tree-datablocks-config";
const SELECTED_TREE_DATABLOCKS_DEFAULT_VALUE: TreeNodeConfig = {
  [TREE_DATABLOCK_TYPE.GUARDRAILS]: true,
  [TREE_DATABLOCK_TYPE.DURATION]: true,
  [TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS]: true,
  [TREE_DATABLOCK_TYPE.ESTIMATED_COST]: true,
  [TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES]: true,
  [TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS]: true,
  [TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS]: true,
  [TREE_DATABLOCK_TYPE.MODEL]: true,
  [TREE_DATABLOCK_TYPE.DURATION_TIMELINE]: true,
};

type TraceTreeViewerProps = {
  trace: Trace;
  spans?: Span[];
  rowId: string;
  onSelectRow: (id: string) => void;
  search?: string;
  setSearch: OnChangeFn<string | undefined>;
  filters: Filters;
  setFilters: OnChangeFn<Filters>;
};

const TraceTreeViewer: React.FunctionComponent<TraceTreeViewerProps> = ({
  trace,
  spans,
  rowId,
  onSelectRow,
  search,
  setSearch,
  filters,
  setFilters,
}) => {
  const traceSpans = useMemo(() => spans ?? [], [spans]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [config, setConfig] = useLocalStorageState(
    SELECTED_TREE_DATABLOCKS_KEY,
    {
      defaultValue: SELECTED_TREE_DATABLOCKS_DEFAULT_VALUE,
    },
  );

  const hasSearch = Boolean(search && search.length);
  const hasFilter = Boolean(filters.length);
  const hasSearchOrFilter = hasSearch || hasFilter;
  const title = !hasSearchOrFilter
    ? "Trace"
    : hasFilter && hasSearch
      ? "Results"
      : hasSearch
        ? "Search results"
        : "Filtered results";

  const predicate = useCallback(
    (data: Span | Trace) =>
      !hasSearch && !hasFilter ? true : filterFunction(data, filters, search),
    [hasSearch, hasFilter, search, filters],
  );

  const { filteredTraceSpans, searchIds } = useMemo(() => {
    const retVal: {
      searchIds: Set<string>;
      filteredTraceSpans: Span[] | null;
    } = {
      searchIds: new Set(),
      filteredTraceSpans: traceSpans,
    };

    if (!hasSearchOrFilter) return retVal;

    const [dataMap, searchIds] = constructDataMapAndSearchIds(
      trace,
      traceSpans,
      predicate,
    );
    const parentIds = addAllParentIds(searchIds, dataMap);

    retVal.searchIds = searchIds;
    retVal.filteredTraceSpans =
      searchIds.size === 0
        ? null
        : traceSpans.filter(
            (traceSpan) =>
              searchIds.has(traceSpan.id) || parentIds.has(traceSpan.id),
          );

    return retVal;
  }, [traceSpans, hasSearchOrFilter, trace, predicate]);

  const { tree, toggleExpandAll, setTree, expandedTreeRows, fullExpandedSet } =
    useTreeDetailsStore();
  const isAllExpanded = expandedTreeRows.size === fullExpandedSet.size;

  useEffect(() => {
    if (!filteredTraceSpans) {
      setTree([]);
      return;
    }

    const sharedData = {
      maxStartTime: new Date(trace.start_time).getTime(),
      maxEndTime: new Date(trace.end_time).getTime(),
      maxDuration: trace.duration,
    };

    const lookup: Record<string, TreeNode> = {
      [trace.id]: {
        id: trace.id,
        name: trace.name,
        data: {
          ...trace,
          ...sharedData,
          spanColor: SPANS_COLORS_MAP[TRACE_TYPE_FOR_TREE],
          parent_span_id: "",
          trace_id: trace.id,
          type: TRACE_TYPE_FOR_TREE,
          tokens: trace.usage?.total_tokens,
          duration: trace.duration,
          startTimestamp: new Date(trace.start_time).getTime(),
          name: trace.name,
          hasError: Boolean(trace.error_info),
          isInSearch:
            searchIds.size === 0 ? undefined : searchIds.has(trace.id),
        },
        children: [],
      },
    };

    const retVal = [lookup[trace.id]];
    const spans = filteredTraceSpans
      .filter((span) => span.trace_id === trace.id)
      .sort((s1, s2) => s1.start_time.localeCompare(s2.start_time));

    spans.forEach((span) => {
      lookup[span.id] = {
        id: span.id,
        name: span.name,
        data: {
          ...span,
          ...sharedData,
          spanColor: SPANS_COLORS_MAP[span.type],
          tokens: span.usage?.total_tokens,
          duration: span.duration,
          startTimestamp: new Date(span.start_time).getTime(),
          hasError: Boolean(span.error_info),
          isInSearch: searchIds.size === 0 ? undefined : searchIds.has(span.id),
        },
        children: [],
      };
    });

    spans.forEach((span: Span) => {
      const directParentKey = span.parent_span_id;

      if (!directParentKey) {
        lookup[trace.id].children?.push(lookup[span.id]);
      } else if (lookup[directParentKey]) {
        lookup[directParentKey].children?.push(lookup[span.id]);
      }
    });

    setTree(retVal);
  }, [filteredTraceSpans, trace, searchIds, setTree]);

  return (
    <div
      className="relative size-full max-w-full overflow-auto pb-4"
      ref={scrollRef}
    >
      <div className="min-w-[400px] max-w-full">
        <div className="sticky top-0 z-10 flex flex-row items-center justify-between gap-2 bg-white pb-2 pl-6 pr-4 pt-4">
          <div className="flex h-8 items-center gap-1">
            <div className="comet-title-xs">{title}</div>
            <div className="comet-body-s text-muted-slate">
              {!hasSearchOrFilter ? traceSpans.length : searchIds.size} items
            </div>
            <ExplainerIcon
              {...EXPLAINERS_MAP[
                EXPLAINER_ID.what_are_these_elements_in_the_tree
              ]}
            />
          </div>
          <div className="flex items-center gap-x-1.5">
            {!hasSearchOrFilter ? (
              <>
                <SpanDetailsButton config={config} onConfigChange={setConfig} />
                <TooltipWrapper
                  content={isAllExpanded ? "Collapse all" : "Expand all"}
                >
                  <Button
                    onClick={toggleExpandAll}
                    variant="outline"
                    size="icon-2xs"
                  >
                    {isAllExpanded ? <FoldVertical /> : <UnfoldVertical />}
                  </Button>
                </TooltipWrapper>
              </>
            ) : (
              <Button
                variant="ghost"
                size="2xs"
                onClick={() => {
                  setSearch(undefined);
                  setFilters([]);
                }}
              >
                Clear
              </Button>
            )}
          </div>
        </div>
        {tree.length ? (
          <VirtualizedTreeViewer
            scrollRef={scrollRef}
            config={config}
            rowId={rowId}
            onRowIdChange={onSelectRow}
          />
        ) : (
          <NoData message="No results" icon={null} />
        )}
      </div>
    </div>
  );
};

export default TraceTreeViewer;
