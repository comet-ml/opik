import React, { useMemo } from "react";

import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { useDatasetVariables } from "@/store/PlaygroundStore";
import usePlaygroundColumnsSettings, {
  getVariableColumnId,
  TAGS_COLUMN_ID,
} from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/usePlaygroundColumnsSettings";

interface PlaygroundColumnsButtonProps {
  datasetId: string;
}

const PlaygroundColumnsButton = ({
  datasetId,
}: PlaygroundColumnsButtonProps) => {
  const datasetVariables = useDatasetVariables();

  const columns = useMemo<ColumnData<unknown>[]>(
    () => [
      ...[...datasetVariables]
        .sort((v1, v2) => v1.localeCompare(v2))
        .map((name) => ({
          id: getVariableColumnId(name),
          label: name,
          type: COLUMN_TYPE.string,
        })),
      { id: TAGS_COLUMN_ID, label: "Tags", type: COLUMN_TYPE.list },
    ],
    [datasetVariables],
  );

  const columnIds = useMemo(() => columns.map(({ id }) => id), [columns]);

  const { selectedColumns, setSelectedColumns, columnsOrder, setColumnsOrder } =
    usePlaygroundColumnsSettings(datasetId, columnIds);

  if (datasetVariables.length === 0) return null;

  return (
    <ColumnsButton
      columns={columns}
      selectedColumns={selectedColumns}
      onSelectionChange={setSelectedColumns}
      order={columnsOrder}
      onOrderChange={setColumnsOrder}
      layout="labeled"
      size="2xs"
    />
  );
};

export default PlaygroundColumnsButton;
