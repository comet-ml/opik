import get from "lodash/get";

import { AssertionsCountCell } from "@/v2/pages-shared/datasets/TestSuiteComponents/AssertionsCountCell";
import { ExecutionPolicyCell } from "@/v2/pages-shared/datasets/TestSuiteComponents/ExecutionPolicyCell";
import AutodetectCell from "@/shared/DataTableCells/AutodetectCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import { StorageKeysConfig } from "@/v2/pages-shared/datasets/DatasetItemsTab/DatasetItemsTab";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import {
  COLUMN_ID_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
} from "@/types/shared";

export const SUITE_STORAGE_KEYS: StorageKeysConfig = {
  selectedColumnsKey: "test-suite-items-selected-columns-v2",
  selectedColumnsMigrationKey: "test-suite-items-selected-columns",
  migrationNewColumns: ["last_updated_at", "assertions"],
  columnsWidthKey: "test-suite-items-columns-width",
  columnsOrderKey: "test-suite-items-columns-order",
  dynamicColumnsKey: "test-suite-items-dynamic-columns",
  paginationSizeKey: "test-suite-items-pagination-size",
  rowHeightKey: "test-suite-items-row-height",
};

export const DATASET_STORAGE_KEYS: StorageKeysConfig = {
  selectedColumnsKey: "dataset-items-selected-columns",
  columnsWidthKey: "dataset-items-columns-width",
  columnsOrderKey: "dataset-items-columns-order",
  dynamicColumnsKey: "dataset-items-dynamic-columns",
  paginationSizeKey: "dataset-items-pagination-size",
  rowHeightKey: "dataset-items-row-height",
};

export const SUITE_DEFAULT_SELECTED_COLUMNS: string[] = [
  "description",
  "last_updated_at",
  "data",
  "assertions",
  "execution_policy",
];

export const DATASET_DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID,
  "created_at",
  "tags",
];

export const buildSuiteColumns = (): ColumnData<DatasetItem>[] => [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.description ?? "",
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "data",
    label: "Data",
    type: COLUMN_TYPE.dictionary,
    accessorFn: (row) => row.data,
    cell: AutodetectCell as never,
    size: 400,
  },
  {
    id: "assertions",
    label: "Custom assertions",
    type: COLUMN_TYPE.string,
    iconType: "assertions",
    cell: AssertionsCountCell as never,
    size: 120,
  },
  {
    id: "execution_policy",
    label: "Execution policy",
    type: COLUMN_TYPE.string,
    iconType: "execution_policy",
    cell: ExecutionPolicyCell as never,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    accessorFn: (row) => row.tags || [],
    cell: ListCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const buildDatasetColumns = (
  _datasetColumns: DatasetItemColumn[],
  dynamicDatasetColumns: DynamicColumn[],
): ColumnData<DatasetItem>[] => [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  ...dynamicDatasetColumns.map(
    ({ label, id, columnType }) =>
      ({
        id,
        label,
        type: columnType,
        accessorFn: (row) => get(row, ["data", label], ""),
        cell: AutodetectCell as never,
      }) as ColumnData<DatasetItem>,
  ),
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    accessorFn: (row) => row.tags || [],
    cell: ListCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];
