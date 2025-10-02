import React, { useMemo } from "react";
import get from "lodash/get";
import isEmpty from "lodash/isEmpty";
import { ColumnDef, createColumnHelper } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";

import DataTable from "@/components/shared/DataTable/DataTable";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { COLUMN_TYPE, ColumnData, ROW_HEIGHT } from "@/types/shared";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";

import { convertColumnDataToColumn } from "@/lib/table";
import { getAlphabetLetter } from "@/lib/utils";
import PlaygroundOutputCell from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputCell";
import SectionHeader from "@/components/shared/DataTableHeaders/SectionHeader";

import PlaygroundVariableCell from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundVariableCell";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

type PlaygroundOutputTableData = {
  variables: { [key: string]: string };
};

const columnHelper = createColumnHelper<PlaygroundOutputTableData>();

interface PlaygroundOutputTableProps {
  datasetItems: DatasetItem[];
  datasetColumns: DatasetItemColumn[];
  promptIds: string[];
  isLoadingDatasetItems: boolean;
}

const COLUMNS_WIDTH_KEY = "playground-output-table-width-keys";

const PlaygroundOutputTable = ({
  datasetItems,
  promptIds,
  datasetColumns,
  isLoadingDatasetItems,
}: PlaygroundOutputTableProps) => {
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const noDataMessage = isLoadingDatasetItems
    ? "Loading..."
    : "No dataset items";

  const rows = useMemo(() => {
    return datasetItems.map((di) => {
      return {
        id: di.id,
        dataItemId: di.id,
        variables: {
          ...di.data,
        },
      };
    });
  }, [datasetItems]);

  const columns = useMemo(() => {
    if (isEmpty(datasetColumns)) {
      return [];
    }

    const retVal: ColumnDef<PlaygroundOutputTableData>[] = [];
    const explainer =
      EXPLAINERS_MAP[EXPLAINER_ID.how_do_i_use_the_dataset_in_the_playground];

    const inputColumns = datasetColumns
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map(
        (c, i) =>
          ({
            id: c.name,
            label: c.name,
            type: mapDynamicColumnTypesToColumnType(c.types),
            customMeta: {
              showIndex: i === 0,
            },
            accessorFn: (row) => get(row, ["variables", c.name], ""),
            cell: PlaygroundVariableCell as never,
            explainer: {
              ...explainer,
              description: explainer.description + `{{${c.name}}}`,
            },
          }) as ColumnData<PlaygroundOutputTableData>,
      );

    retVal.push(
      columnHelper.group({
        id: "variables",
        meta: {
          header: "Variables",
        },
        header: SectionHeader,
        columns: convertColumnDataToColumn<
          PlaygroundOutputTableData,
          PlaygroundOutputTableData
        >(inputColumns, {}),
      }),
    );

    const outputColumns = promptIds.map((promptId, promptIdx) => {
      return {
        id: `output-${promptId}`,
        label: `Output ${getAlphabetLetter(promptIdx)}`,
        type: COLUMN_TYPE.string,
        cell: PlaygroundOutputCell as never,
        customMeta: {
          promptId: promptId,
        },
      } as ColumnData<PlaygroundOutputTableData>;
    });

    retVal.push(
      columnHelper.group({
        id: "output",
        meta: {
          header: "Output",
        },
        header: SectionHeader,
        columns: convertColumnDataToColumn<
          PlaygroundOutputTableData,
          PlaygroundOutputTableData
        >(outputColumns, {}),
      }),
    );

    return retVal;
  }, [datasetColumns, promptIds]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  return (
    <div
      className="pt-10"
      style={{ "--cell-top-height": "28px" } as React.CSSProperties}
    >
      <DataTable
        columns={columns}
        data={rows}
        rowHeight={ROW_HEIGHT.large}
        resizeConfig={resizeConfig}
        noData={<DataTableNoData title={noDataMessage} />}
      />
    </div>
  );
};

export default PlaygroundOutputTable;
