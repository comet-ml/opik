import React, { useEffect, useMemo, useState } from "react";
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
import { useHydrateDatasetItemData } from "@/components/pages/PlaygroundPage/useHydrateDatasetItemData";
import ListCell from "@/components/shared/DataTableCells/ListCell";

type PlaygroundOutputTableData = {
  variables: { [key: string]: string };
  tags: string[];
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

  const hydrateDatasetItemData = useHydrateDatasetItemData();
  const [hydratedDatasetItems, setHydratedDatasetItems] = useState<
    DatasetItem[]
  >([]);

  // Hydrate dataset items when they change
  useEffect(() => {
    const hydrateItems = async () => {
      const hydratedItems = await Promise.all(
        datasetItems.map(async (item) => {
          const hydratedData = await hydrateDatasetItemData(item);
          return {
            ...item,
            data: hydratedData,
          };
        }),
      );
      setHydratedDatasetItems(hydratedItems);
    };

    if (datasetItems.length > 0) {
      hydrateItems();
    } else {
      setHydratedDatasetItems([]);
    }
  }, [datasetItems, hydrateDatasetItemData]);

  const noDataMessage = isLoadingDatasetItems
    ? "Loading..."
    : "No dataset items";

  const rows = useMemo(() => {
    return hydratedDatasetItems.map((di) => {
      return {
        id: di.id,
        dataItemId: di.id,
        variables: {
          ...di.data,
        },
        tags: di.tags || [],
      };
    });
  }, [hydratedDatasetItems]);

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
            id: `variables.${c.name}`,
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

    // Add tags column
    inputColumns.push({
      id: "tags",
      label: "Tags",
      type: COLUMN_TYPE.list,
      accessorFn: (row) => row.tags || [],
      cell: ListCell as never,
    } as ColumnData<PlaygroundOutputTableData>);

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
        id: "playground-output",
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
      className="playground-table overflow-x-auto pt-10" // eslint-disable-line tailwindcss/no-custom-classname
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
