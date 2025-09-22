import React from "react";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import useLocalStorageState from "use-local-storage-state";
import {
  CONFIGURABLE_COLUMNS,
  DEFAULT_SELECTED_COLUMNS,
  ENTITY_TYPE_TO_STORAGE_KEYS,
} from "./constants";
import FeedbackScoreTable, {
  FeedbackScoreTableProps,
} from "./FeedbackScoreTable";

export type ConfigurableFeedbackScoreTableProps = FeedbackScoreTableProps;

const ConfigurableFeedbackScoreTable: React.FunctionComponent<
  ConfigurableFeedbackScoreTableProps
> = (tableProps) => {
  const { entityType } = tableProps;

  const storageKeys = ENTITY_TYPE_TO_STORAGE_KEYS[entityType];

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    storageKeys.selectedColumns,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    storageKeys.columnsOrder,
    {
      defaultValue: [],
    },
  );

  return (
    <>
      <div className="mb-4 flex justify-end">
        <ColumnsButton
          columns={CONFIGURABLE_COLUMNS}
          selectedColumns={selectedColumns}
          onSelectionChange={setSelectedColumns}
          order={columnsOrder}
          onOrderChange={setColumnsOrder}
        ></ColumnsButton>
      </div>

      <FeedbackScoreTable
        {...tableProps}
        selectedColumns={selectedColumns}
        columnsOrder={columnsOrder}
      />
    </>
  );
};

export default ConfigurableFeedbackScoreTable;
