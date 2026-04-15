import React, { useCallback, useEffect, useMemo, useRef } from "react";

import {
  addAllParentIds,
  constructDataMapAndSearchIds,
  filterFunction,
} from "./helpers";
import { Span, Trace } from "@/types/traces";
import { Filters } from "@/types/filters";
import { SPANS_COLORS_MAP, TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import NoData from "@/shared/NoData/NoData";
import VirtualizedTreeViewer from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceTreeViewer/VirtualizedTreeViewer";
import useTreeDetailsStore, {
  TreeNode,
  TreeNodeConfig,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";

type TraceTreeViewerProps = {
  trace: Trace;
  spans?: Span[];
  rowId: string;
  onSelectRow: (id: string) => void;
  search?: string;
  filters: Filters;
  config: TreeNodeConfig;
};

const TraceTreeViewer: React.FunctionComponent<TraceTreeViewerProps> = ({
  trace,
  spans,
  rowId,
  onSelectRow,
  search,
  filters,
  config,
}) => {
  const traceSpans = useMemo(() => spans ?? [], [spans]);
  const scrollRef = useRef<HTMLDivElement>(null);

  const hasSearch = Boolean(search && search.length);
  const hasFilter = Boolean(filters.length);
  const hasSearchOrFilter = hasSearch || hasFilter;

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

  const { tree, setTree } = useTreeDetailsStore();

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
      className="relative size-full overflow-y-auto overflow-x-hidden pb-4"
      ref={scrollRef}
    >
      <div className="max-w-full pt-2">
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
