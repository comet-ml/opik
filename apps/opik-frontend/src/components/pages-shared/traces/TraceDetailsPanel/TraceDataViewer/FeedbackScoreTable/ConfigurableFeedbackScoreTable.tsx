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

export type ConfigurableFeedbackScoreTableProps = FeedbackScoreTableProps & {
  title?: string;
};

const ConfigurableFeedbackScoreTable: React.FunctionComponent<
  ConfigurableFeedbackScoreTableProps
> = (tableProps) => {
  const { entityType, title } = tableProps;

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
      {title && (
        <div className="mb-4 flex items-center justify-between">
          <div className="comet-body-s-accented">{title}</div>
          <ColumnsButton
            columns={CONFIGURABLE_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
        </div>
      )}
      {!title && (
        <div className="mb-4 flex justify-end">
          <ColumnsButton
            columns={CONFIGURABLE_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
        </div>
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
