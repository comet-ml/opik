import { useCallback, useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { generateTagFilter } from "@/lib/filters";
import { buildDynamicTagColumns } from "@/lib/tags";
import { Filter } from "@/types/filters";
import { BaseTraceData } from "@/types/traces";
import { ColumnData } from "@/types/shared";

export const COLUMNS_TAGS_ORDER_KEY_SUFFIX = "tag-columns-order";

type UseTagFilterHandlerParams = {
  filters: Filter[];
  setFilters: (filters: Filter[]) => void;
  setPage: (page: number) => void;
};

export const useTagFilterHandler = ({
  filters,
  setFilters,
  setPage,
}: UseTagFilterHandlerParams) => {
  return useCallback(
    (tag: string) => {
      const tagFilterExists = filters.some(
        (filter) =>
          filter.field === "tags" &&
          filter.operator === "contains" &&
          filter.value === tag,
      );

      if (!tagFilterExists) {
        setFilters([...filters, ...generateTagFilter(tag)]);
        setPage(1);
      }
    },
    [filters, setFilters, setPage],
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
