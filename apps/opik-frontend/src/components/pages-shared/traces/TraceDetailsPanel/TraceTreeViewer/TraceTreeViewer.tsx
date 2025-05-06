import React, { useCallback, useMemo, useState } from "react";
import {
  ControlledTreeEnvironment,
  Tree,
  TreeItem,
  TreeItemIndex,
} from "react-complex-tree";

import {
  addAllParentIds,
  constructDataMapAndSearchIds,
  searchFunction,
  getSpansWithLevel,
  TreeItemWidthObject,
} from "./helpers";
import { treeRenderers } from "./treeRenderers";
import { BASE_TRACE_DATA_TYPE, Span, Trace } from "@/types/traces";
import { getTextWidth } from "@/lib/utils";
import { SPANS_COLORS_MAP, TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { Button } from "@/components/ui/button";
import useDeepMemo from "@/hooks/useDeepMemo";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import NoData from "@/components/shared/NoData/NoData";

type SpanWithMetadata = Omit<Span, "type"> & {
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

type TreeData = Record<string, TreeItem<SpanWithMetadata>>;

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

  const spanIds = useDeepMemo(() => {
    return traceSpans.map((chain: Span) => chain.id).sort();
  }, [traceSpans]);

  const fullTreeList = useMemo(
    () => [trace.id, ...spanIds],
    [trace.id, spanIds],
  );

  const [collapsedTraceSpans, setCollapsedTraceSpans] = useState<
    TreeItemIndex[]
  >([]);

  const onExpandItem = useCallback((item: TreeItem<SpanWithMetadata>) => {
    setCollapsedTraceSpans((prev) => prev.filter((i) => i !== item.index));
  }, []);

  const onCollapseItem = useCallback((item: TreeItem<SpanWithMetadata>) => {
    setCollapsedTraceSpans((prev) => [...prev, item.index]);
  }, []);

  const expandedTraceSpans = useMemo(() => {
    return fullTreeList.filter((id) => !collapsedTraceSpans.includes(id));
  }, [collapsedTraceSpans, fullTreeList]);
  const isAllExpanded = expandedTraceSpans.length === fullTreeList.length;

  const toggleExpandAll = useCallback(() => {
    setCollapsedTraceSpans(isAllExpanded ? fullTreeList : []);
  }, [isAllExpanded, fullTreeList]);

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

  const treeData = useMemo(() => {
    if (!filteredTraceSpans) return null;

    const sharedData = {
      maxStartTime: new Date(trace.start_time).getTime(),
      maxEndTime: new Date(trace.end_time).getTime(),
      maxDuration: trace.duration,
    };

    const acc: TreeData = {
      root: {
        canMove: false,
        canRename: false,
        children: [trace.id],
        isFolder: true,
      } as TreeItem<never>,
      [trace.id]: {
        canMove: false,
        canRename: false,
        index: trace.id,
        isFolder: true,
        children: [],
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
      },
    };

    const retVal = filteredTraceSpans
      .sort((s1, s2) => s1.start_time.localeCompare(s2.start_time))
      .reduce<TreeData>((accumulator, span: Span) => {
        const spanColor = SPANS_COLORS_MAP[span.type];
        return {
          ...accumulator,
          [span.id]: {
            canMove: false,
            canRename: false,
            data: {
              ...span,
              ...sharedData,
              spanColor,
              tokens: span.usage?.total_tokens,
              duration: span.duration,
              startTimestamp: new Date(span.start_time).getTime(),
              hasError: Boolean(span.error_info),
              isInSearch: searchIds.has(span.id),
            },
            isFolder: true,
            index: span.id,
            children: [],
          },
        };
      }, acc);

    filteredTraceSpans.forEach((span: Span) => {
      const directParentKey = span.parent_span_id;

      if (!directParentKey) {
        retVal[trace.id]?.children?.push(span.id);
      } else if (retVal[directParentKey]) {
        retVal[directParentKey].children?.push(span.id);
      }
      return retVal;
    });

    return retVal;
  }, [filteredTraceSpans, trace, searchIds]);

  const viewState = useMemo(
    () => ({
      ["trace-view"]: {
        focusedItem: rowId,
        expandedItems: expandedTraceSpans,
      },
    }),
    [rowId, expandedTraceSpans],
  );

  const maxWidth = useMemo(() => {
    const map: Record<string, number | undefined> = {};
    const list: TreeItemWidthObject[] = traceSpans.map((s) => ({
      id: s.id,
      name: s.name || "",
      parentId: s.parent_span_id,
      children: [],
    }));
    const rootElement: TreeItemWidthObject = {
      id: trace.id,
      name: trace.name,
      children: [],
      level: 1,
    };

    list.forEach((item, index) => {
      map[item.id] = index;
    });

    list.forEach((item) => {
      if (item.parentId) {
        const listIndex = map[item.parentId];

        if (listIndex !== undefined) {
          list[listIndex].children.push(item);
        } else {
          console.warn(`Parent ${item.parentId} not found for ${item.id}`);
        }
      } else {
        rootElement.children.push(item);
      }
    });

    const items = getSpansWithLevel(rootElement, [], 2);

    const widthArray = getTextWidth(
      items.map((i) => i.name),
      { font: "14px Inter" },
    );

    const OTHER_SPACE = 52;
    const LEVEL_WIDTH = 16;

    return Math.ceil(
      Math.max(
        ...items.map(
          (i, index) =>
            OTHER_SPACE +
            (i.level || 1) * LEVEL_WIDTH +
            widthArray[index] * 1.03, //where 1.03 Letter spacing compensation
        ),
      ),
    );
  }, [traceSpans, trace]);

  return (
    <div
      className="relative size-full max-w-full overflow-auto pb-4"
      style={
        {
          "--details-container-width": `${maxWidth}px`,
        } as React.CSSProperties
      }
    >
      <div className="mt-4 min-w-[400px] max-w-full">
        <div className="mt-2 flex flex-row items-end gap-2 px-6">
          <div className="comet-title-s">Trace spans</div>
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
                onClick={toggleExpandAll}
                variant="ghost"
                size="sm"
                className="-mr-3"
              >
                {isAllExpanded ? "Collapse all" : "Expand all"}
              </Button>
            )}
          </div>
        </div>

        {treeData ? (
          <ControlledTreeEnvironment
            items={treeData}
            onFocusItem={(item) => onSelectRow(item.index as string)}
            viewState={viewState}
            onExpandItem={onExpandItem}
            onCollapseItem={onCollapseItem}
            renderDepthOffset={treeRenderers.renderDepthOffset}
            renderTreeContainer={treeRenderers.renderTreeContainer}
            renderItemsContainer={treeRenderers.renderItemsContainer}
            renderItem={treeRenderers.renderItem}
            renderItemArrow={treeRenderers.renderItemArrow}
            getItemTitle={(item) => item.data.name}
            canSearch={false}
          >
            <Tree
              treeId="trace-view"
              rootItem={"root"}
              treeLabel="Trace tree"
            />
          </ControlledTreeEnvironment>
        ) : (
          <NoData message="No search results" icon={null} />
        )}
      </div>
    </div>
  );
};

export default TraceTreeViewer;
