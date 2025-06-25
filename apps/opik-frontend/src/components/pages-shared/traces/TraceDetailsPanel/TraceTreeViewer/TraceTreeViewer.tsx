import React, { useCallback, useMemo, useRef, useState } from "react";

import {
  addAllParentIds,
  constructDataMapAndSearchIds,
  searchFunction,
} from "./helpers";
import { Span, Trace } from "@/types/traces";
import { SPANS_COLORS_MAP, TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { Button } from "@/components/ui/button";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import NoData from "@/components/shared/NoData/NoData";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import VirtualizedTreeViewer, {
  TreeNode,
  VirtualizedTreeViewerRef,
} from "@/components/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/VirtualizedTreeViewer";

type TraceTreeViewerProps = {
  trace: Trace;
  spans?: Span[];
  rowId: string;
  onSelectRow: (id: string) => void;
  isSpansLazyLoading: boolean;
};

const TraceTreeViewer: React.FunctionComponent<TraceTreeViewerProps> = ({
  trace,
  spans,
  rowId,
  onSelectRow,
  isSpansLazyLoading,
}) => {
  const [search, setSearch] = useState("");
  const traceSpans = useMemo(() => spans ?? [], [spans]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const virtualTreeViewerRef = useRef<VirtualizedTreeViewerRef>();

  const noSearch = !search;

  const predicate = useCallback(
    (data: Span | Trace) => {
      if (!search) return true;
      const searchValue = search.toLowerCase();
      return searchValue ? searchFunction(searchValue, data) : true;
    },
    [search],
  );

  const { filteredTraceSpans, searchIds } = useMemo(() => {
    const retVal: {
      searchIds: Set<string>;
      filteredTraceSpans: Span[] | null;
    } = {
      searchIds: new Set(),
      filteredTraceSpans: traceSpans,
    };

    if (noSearch) return retVal;

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
  }, [trace, traceSpans, predicate, noSearch]);

  const virtualTreeData = useMemo(() => {
    if (!filteredTraceSpans) return null;

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
          spanColor: SPANS_COLORS_MAP[TRACE_TYPE_FOR_TREE],
          parent_span_id: "",
          trace_id: trace.id,
          type: TRACE_TYPE_FOR_TREE,
          tokens: trace.usage?.total_tokens,
          duration: trace.duration,
          name: trace.name,
          hasError: Boolean(trace.error_info),
          isInSearch: searchIds.has(trace.id),
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
          isInSearch: searchIds.has(span.id),
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

    return retVal;
  }, [filteredTraceSpans, trace, searchIds]);

  return (
    <div
      className="relative size-full max-w-full overflow-auto pb-4"
      ref={scrollRef}
    >
      <div className="mt-4 min-w-[400px] max-w-full">
        <div className="mt-2 flex flex-row items-end gap-2 px-6">
          <div className="comet-title-s">Trace spans</div>
          <ExplainerIcon
            className="-ml-1 self-center"
            {...EXPLAINERS_MAP[
              EXPLAINER_ID.what_are_these_elements_in_the_tree
            ]}
          />
          <div className="comet-body-s pb-[3px] text-muted-slate">
            <div>{traceSpans.length} spans</div>
          </div>
        </div>
        <div className="sticky top-0 z-10 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 bg-white px-6 pb-4 pt-3">
          <div className="flex items-center gap-2">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search by all fields"
              dimension="sm"
              disabled={isSpansLazyLoading}
            ></SearchInput>
          </div>
          <div className="flex items-center gap-2">
            {noSearch && (
              <Button
                onClick={virtualTreeViewerRef.current?.toggleExpandAll}
                variant="ghost"
                size="sm"
                className="-mr-3"
              >
                {virtualTreeViewerRef.current?.isCollapsedAll
                  ? "Collapse all"
                  : "Expand all"}
              </Button>
            )}
          </div>
        </div>

        {virtualTreeData ? (
          <VirtualizedTreeViewer
            ref={virtualTreeViewerRef}
            tree={virtualTreeData}
            scrollRef={scrollRef}
            rowId={rowId}
            onRowIdChange={onSelectRow}
          />
        ) : (
          <NoData message="No search results" icon={null} />
        )}
      </div>
    </div>
  );
};

export default TraceTreeViewer;
