import { useCallback, useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { generateTagFilter } from "@/lib/filters";
import { buildDynamicTagColumns } from "@/lib/tags";
import { Filter } from "@/types/filters";
import { BaseTraceData } from "@/types/traces";
import { ColumnData } from "@/types/shared";

export const COLUMNS_TAGS_ORDER_KEY_SUFFIX = "tag-columns-order";

type UseTagFilterHandlerParams = {
  setFilters: (
    filters: Filter[] | ((filters?: Filter[] | null) => Filter[]),
  ) => void;
  setPage: (page: number) => void;
};

export const useTagFilterHandler = ({
  setFilters,
  setPage,
}: UseTagFilterHandlerParams) => {
  return useCallback(
    (tag: string) => {
      setFilters((currentFilters) => {
        const filters = Array.isArray(currentFilters) ? currentFilters : [];
        const tagFilterExists = filters.some(
          (filter) =>
            filter.field === "tags" &&
            filter.operator === "contains" &&
            filter.value === tag,
        );

        if (tagFilterExists) {
          return filters;
        }

        setPage(1);
        return [...filters, ...generateTagFilter(tag)];
      });
    },
    [setFilters, setPage],
  );
};

type UseTraceTagColumnsParams<Row extends Pick<BaseTraceData, "tags">> = {
  rows: Row[];
  storageKey: string;
};

export const useTraceTagColumns = <Row extends Pick<BaseTraceData, "tags">>({
  rows,
  storageKey,
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

  return {
    tagColumnsData,
    tagColumnsOrder,
    setTagColumnsOrder,
    tagColumnIdsExcludedFromSelectAll,
  };
};
