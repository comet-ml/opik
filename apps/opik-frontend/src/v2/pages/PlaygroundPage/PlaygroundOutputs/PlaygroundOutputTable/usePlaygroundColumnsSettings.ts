import { useCallback, useMemo } from "react";
import difference from "lodash/difference";
import union from "lodash/union";
import useLocalStorageState from "use-local-storage-state";

const HIDDEN_COLUMNS_KEY = "playground-output-table-hidden-columns";
const COLUMNS_ORDER_KEY = "playground-output-table-columns-order";

const EMPTY_IDS: string[] = [];

export const TAGS_COLUMN_ID = "tags";

export const getVariableColumnId = (name: string) => `variables.${name}`;

const usePlaygroundColumnsSettings = (
  datasetId: string,
  columnIds: string[],
) => {
  // Hidden ids rather than selected ones, so a field added to the dataset later
  // shows up instead of starting hidden. Ids missing from `columnIds` (another
  // version, a filter) are kept so they are still hidden when they come back.
  const [hiddenColumnsMap, setHiddenColumnsMap] = useLocalStorageState<
    Record<string, string[]>
  >(HIDDEN_COLUMNS_KEY, { defaultValue: {} });

  const [columnsOrderMap, setColumnsOrderMap] = useLocalStorageState<
    Record<string, string[]>
  >(COLUMNS_ORDER_KEY, { defaultValue: {} });

  const hiddenColumns = hiddenColumnsMap[datasetId] ?? EMPTY_IDS;
  const columnsOrder = columnsOrderMap[datasetId] ?? EMPTY_IDS;

  const selectedColumns = useMemo(
    () => difference(columnIds, hiddenColumns),
    [columnIds, hiddenColumns],
  );

  const setSelectedColumns = useCallback(
    (selected: string[]) =>
      setHiddenColumnsMap((map) => ({
        ...map,
        [datasetId]: union(
          difference(map[datasetId] ?? EMPTY_IDS, columnIds),
          difference(columnIds, selected),
        ),
      })),
    [datasetId, columnIds, setHiddenColumnsMap],
  );

  const setColumnsOrder = useCallback(
    (order: string[]) =>
      setColumnsOrderMap((map) => ({ ...map, [datasetId]: order })),
    [datasetId, setColumnsOrderMap],
  );

  return {
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
  };
};

export default usePlaygroundColumnsSettings;
