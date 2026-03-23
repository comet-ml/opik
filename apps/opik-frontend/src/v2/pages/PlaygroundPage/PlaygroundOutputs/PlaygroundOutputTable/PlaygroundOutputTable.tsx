import React, { useMemo, useCallback } from "react";
import get from "lodash/get";
import isEmpty from "lodash/isEmpty";
import { ColumnDef } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { Resizable } from "re-resizable";
import { GripVertical } from "lucide-react";

import StickyScrollTable from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/StickyScrollTable";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { COLUMN_TYPE, ColumnData, ROW_HEIGHT } from "@/types/shared";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";

import { convertColumnDataToColumn } from "@/lib/table";
import { getAlphabetLetter } from "@/lib/utils";
import PlaygroundOutputCell from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputCell";
import PlaygroundOutputColumnHeader from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputColumnHeader";
import PlaygroundVariableCell from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundVariableCell";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { useIncrementalDatasetHydration } from "@/v2/pages/PlaygroundPage/useIncrementalDatasetHydration";
import PlaygroundTagsCell from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundTagsCell";

type PlaygroundOutputTableData = {
  variables: { [key: string]: string };
  tags: string[];
};

interface PlaygroundOutputTableProps {
  datasetItems: DatasetItem[];
  datasetColumns: DatasetItemColumn[];
  promptIds: string[];
  isLoadingDatasetItems: boolean;
  isFetchingData: boolean;
}

const COLUMNS_WIDTH_KEY = "playground-output-table-width-keys";
const LEFT_PANEL_WIDTH_KEY = "playground-output-left-panel-width";
const DEFAULT_LEFT_WIDTH = "50%";
const MIN_LEFT_WIDTH = "10%";
const MAX_LEFT_WIDTH = "90%";

const PlaygroundOutputTable = ({
  datasetItems,
  promptIds,
  datasetColumns,
  isLoadingDatasetItems,
  isFetchingData,
}: PlaygroundOutputTableProps) => {
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const [leftPanelWidth, setLeftPanelWidth] = useLocalStorageState<
    string | number
  >(LEFT_PANEL_WIDTH_KEY, { defaultValue: DEFAULT_LEFT_WIDTH });

  const { hydratedItems: hydratedDatasetItems } =
    useIncrementalDatasetHydration(datasetItems);

  const noDataMessage = isLoadingDatasetItems
    ? "Loading..."
    : "No dataset items";

  const rows = useMemo(() => {
    if (isLoadingDatasetItems) {
      return [];
    }

    return hydratedDatasetItems.map((di) => ({
      id: di.id,
      dataItemId: di.id,
      variables: {
        ...di.data,
      },
      tags: di.tags || [],
    }));
  }, [hydratedDatasetItems, isLoadingDatasetItems]);

  const leftColumns = useMemo(() => {
    if (isEmpty(datasetColumns)) {
      return [];
    }

    const retVal: ColumnDef<PlaygroundOutputTableData>[] = [];
    const explainer =
      EXPLAINERS_MAP[
        EXPLAINER_ID.how_do_i_use_the_evaluation_suite_in_the_playground
      ];

    const inputColumns = [...datasetColumns]
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

    inputColumns.push({
      id: "tags",
      label: "Tags",
      type: COLUMN_TYPE.list,
      iconType: "tags",
      accessorFn: (row) => row.tags || [],
      cell: PlaygroundTagsCell as never,
    } as ColumnData<PlaygroundOutputTableData>);

    retVal.push(
      ...convertColumnDataToColumn<
        PlaygroundOutputTableData,
        PlaygroundOutputTableData
      >(inputColumns, {}),
    );

    return retVal;
  }, [datasetColumns]);

  const rightColumns = useMemo(() => {
    if (promptIds.length === 0) {
      return [];
    }

    const retVal: ColumnDef<PlaygroundOutputTableData>[] = [];

    const outputColumns = promptIds.map((promptId, promptIdx) => {
      return {
        id: `output-${promptId}`,
        label: `Output ${getAlphabetLetter(promptIdx)}`,
        type: COLUMN_TYPE.string,
        header: PlaygroundOutputColumnHeader as never,
        cell: PlaygroundOutputCell as never,
        minSize: 350,
        customMeta: {
          promptId: promptId,
          promptIndex: promptIdx,
        },
      } as ColumnData<PlaygroundOutputTableData>;
    });

    retVal.push(
      ...convertColumnDataToColumn<
        PlaygroundOutputTableData,
        PlaygroundOutputTableData
      >(outputColumns, {}),
    );

    return retVal;
  }, [promptIds]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleResizeStop = useCallback(
    (_e: unknown, _direction: unknown, ref: HTMLElement) => {
      setLeftPanelWidth(ref.offsetWidth);
    },
    [setLeftPanelWidth],
  );

  const hasLeftColumns = leftColumns.length > 0;
  const hasRightColumns = rightColumns.length > 0;

  if (!hasLeftColumns && !hasRightColumns) {
    return null;
  }

  return (
    <div
      className="playground-table flex" // eslint-disable-line tailwindcss/no-custom-classname
      style={{ "--cell-top-height": "28px" } as React.CSSProperties}
    >
      {hasLeftColumns && (
        <Resizable
          size={{ width: leftPanelWidth, height: "auto" }}
          minWidth={MIN_LEFT_WIDTH}
          maxWidth={MAX_LEFT_WIDTH}
          enable={{ right: true }}
          onResizeStop={handleResizeStop}
          handleComponent={{
            right: (
              <div className="flex h-full w-[13px] justify-center border-x border-b hover:bg-gray-100">
                <GripVertical className="sticky top-4 z-10 mt-4 size-3 text-light-slate" />
              </div>
            ),
          }}
          handleStyles={{ right: { right: 0, width: 1, zIndex: 10 } }}
          className="mr-3 shrink-0"
        >
          <div className="h-full">
            <StickyScrollTable
              columns={leftColumns}
              data={rows}
              rowHeight={ROW_HEIGHT.large}
              resizeConfig={resizeConfig}
              noData={<DataTableNoData title={noDataMessage} />}
              showLoadingOverlay={isFetchingData}
            />
          </div>
        </Resizable>
      )}

      {hasRightColumns && (
        <div className="min-w-0 flex-1">
          <StickyScrollTable
            columns={rightColumns}
            data={rows}
            rowHeight={ROW_HEIGHT.large}
            resizeConfig={resizeConfig}
            noData={<DataTableNoData title={noDataMessage} />}
            showLoadingOverlay={isFetchingData}
          />
        </div>
      )}
    </div>
  );
};

export default PlaygroundOutputTable;
