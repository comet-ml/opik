import React, { useMemo } from "react";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import useLocalStorageState from "use-local-storage-state";
import {
  CONFIGURABLE_COLUMNS,
  DEFAULT_SELECTED_COLUMNS,
  DEFAULT_SELECTED_COLUMNS_WITH_TYPE,
  ENTITY_TYPE_TO_STORAGE_KEYS,
  getConfigurableColumnsWithoutType,
  getStorageKeyType,
} from "./constants";
import FeedbackScoreTable, {
  FeedbackScoreTableProps,
} from "./FeedbackScoreTable";

export type ConfigurableFeedbackScoreTableProps = FeedbackScoreTableProps & {
  title?: string;
};

const ConfigurableFeedbackScoreTable: React.FunctionComponent<
  ConfigurableFeedbackScoreTableProps
> = (tableProps) => {
  const { entityType, title, isAggregatedSpanScores = false } = tableProps;

  // Use "span" storage keys for aggregated span scores, otherwise use entityType
  const storageKeyType = getStorageKeyType(entityType, isAggregatedSpanScores);
  const storageKeys = ENTITY_TYPE_TO_STORAGE_KEYS[storageKeyType];

  // Filter columns based on isAggregatedSpanScores - Type column only for aggregated span scores
  const availableColumns = useMemo(() => {
    return isAggregatedSpanScores
      ? CONFIGURABLE_COLUMNS
      : getConfigurableColumnsWithoutType();
  }, [isAggregatedSpanScores]);

  // Default columns: include Type only for aggregated span scores at trace level
  const defaultColumnsForEntityType = useMemo(() => {
    return isAggregatedSpanScores
      ? DEFAULT_SELECTED_COLUMNS_WITH_TYPE
      : DEFAULT_SELECTED_COLUMNS;
  }, [isAggregatedSpanScores]);

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    storageKeys.selectedColumns,
    {
      defaultValue: defaultColumnsForEntityType,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    storageKeys.columnsOrder,
    {
      defaultValue: [],
    },
  );

  const columnsButton = (
    <ColumnsButton
      columns={availableColumns}
      selectedColumns={selectedColumns}
      onSelectionChange={setSelectedColumns}
      order={columnsOrder}
      onOrderChange={setColumnsOrder}
    />
  );

  return (
    <>
      {title ? (
        <div className="mb-2 flex items-center justify-between">
          <div className="comet-body-s-accented">{title}</div>
          {columnsButton}
        </div>
      ) : (
        <div className="mb-2 flex justify-end">{columnsButton}</div>
      )}

      <FeedbackScoreTable
        {...tableProps}
        selectedColumns={selectedColumns}
        columnsOrder={columnsOrder}
      />
    </>
  );
};

export default ConfigurableFeedbackScoreTable;
