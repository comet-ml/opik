import React from "react";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import useLocalStorageState from "use-local-storage-state";
import {
  COLUMNS_ORDER_KEY,
  CONFIGURABLE_COLUMNS,
  DEFAULT_SELECTED_COLUMNS,
  SELECTED_COLUMNS_KEY,
} from "./constants";
import FeedbackScoreTable, {
  FeedbackScoreTableProps,
} from "./FeedbackScoreTable";

export type ConfigurableFeedbackScoreTableProps = FeedbackScoreTableProps;

const ConfigurableFeedbackScoreTable: React.FunctionComponent<
  ConfigurableFeedbackScoreTableProps
> = (tableProps) => {
  const { entityType } = tableProps;

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${entityType}-${SELECTED_COLUMNS_KEY}`,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    `${entityType}-${COLUMNS_ORDER_KEY}`,
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
