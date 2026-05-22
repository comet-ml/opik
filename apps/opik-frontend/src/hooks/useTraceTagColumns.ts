import { useCallback, useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { generateTagFilter } from "@/lib/filters";
import { convertColumnDataToColumn } from "@/lib/table";
import { buildDynamicTagColumns } from "@/lib/tags";
import { Filter } from "@/types/filters";
import { BaseTraceData } from "@/types/traces";
import { ColumnData } from "@/types/shared";

export const COLUMNS_TAGS_ORDER_KEY_SUFFIX = "tag-columns-order";
const TAG_COLUMNS_SECTION_TITLE = "Tag values";

type UseTagFilterHandlerParams = {
  setFilters: (
    filters: Filter[] | ((filters?: Filter[] | null) => Filter[]),
  ) => void;
  setPage: (page: number) => void;
};

const hasTagFilter = (filters: Filter[], tag: string) =>
  filters.some(
    (filter) =>
      filter.field === "tags" &&
      filter.operator === "contains" &&
      filter.value === tag,
  );

export const useTagFilterHandler = ({
  setFilters,
  setPage,
}: UseTagFilterHandlerParams) => {
  return useCallback(
    (tag: string) => {
      let didAppendFilter = false;

      setFilters((currentFilters) => {
        const latestFilters = Array.isArray(currentFilters)
          ? currentFilters
          : [];

        if (hasTagFilter(latestFilters, tag)) {
          return latestFilters;
        }

        didAppendFilter = true;
        return [...latestFilters, ...generateTagFilter(tag)];
      });

      if (didAppendFilter) {
        setPage(1);
      }
    },
    [setFilters, setPage],
  );
};

export const getTraceDynamicColumnIdsExcludedFromSelectAll = (
  metadataColumnsData: Array<Pick<ColumnData<BaseTraceData>, "id">>,
  tagColumnIdsExcludedFromSelectAll: string[],
) => [
  ...metadataColumnsData.map((col) => col.id),
  ...tagColumnIdsExcludedFromSelectAll,
];

type UseTraceTagColumnsParams<Row extends Pick<BaseTraceData, "tags">> = {
  rows: Row[];
  storageKey: string;
  selectedColumns?: string[];
  sortableColumns?: string[];
};

type TraceTagColumnSection = {
  title: string;
  columns: ColumnData<BaseTraceData>[];
  order: string[];
  onOrderChange: (order: string[]) => void;
};

export const useTraceTagColumns = <
  Row extends Pick<BaseTraceData, "tags">,
  TableRow = Row,
>({
  rows,
  storageKey,
  selectedColumns,
  sortableColumns = [],
}: UseTraceTagColumnsParams<Row>) => {
  const [tagColumnsOrder, setTagColumnsOrder] = useLocalStorageState<string[]>(
    storageKey,
    {
      defaultValue: [],
    },
  );

  const dynamicTagColumns = useMemo(() => {
    return buildDynamicTagColumns(rows.flatMap((row) => row.tags ?? []));
  }, [rows]);

  const tagColumnsData = useMemo(() => {
    return dynamicTagColumns.map(({ label, id, columnType }) => {
      return {
        id,
        label,
        type: columnType,
        sortable: false,
        accessorFn: (row) => (row.tags?.includes(label) ? "Yes" : "-"),
      } as ColumnData<BaseTraceData>;
    });
  }, [dynamicTagColumns]);

  const tagColumnIdsExcludedFromSelectAll = useMemo(
    () => tagColumnsData.map((column) => column.id),
    [tagColumnsData],
  );

  const tagColumns = useMemo(
    () =>
      convertColumnDataToColumn<BaseTraceData, TableRow>(tagColumnsData, {
        columnsOrder: tagColumnsOrder,
        selectedColumns,
        sortableColumns,
      }),
    [tagColumnsData, tagColumnsOrder, selectedColumns, sortableColumns],
  );

  const tagColumnSection = useMemo<TraceTagColumnSection | undefined>(() => {
    if (tagColumnsData.length === 0) return undefined;

    return {
      title: TAG_COLUMNS_SECTION_TITLE,
      columns: tagColumnsData,
      order: tagColumnsOrder,
      onOrderChange: setTagColumnsOrder,
    };
  }, [tagColumnsData, tagColumnsOrder, setTagColumnsOrder]);

  return {
    tagColumns,
    tagColumnsData,
    tagColumnSection,
    tagColumnIdsExcludedFromSelectAll,
  };
};
