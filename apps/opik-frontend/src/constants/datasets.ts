import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import isObject from "lodash/isObject";
import { formatDate } from "@/lib/date";
import { DatasetItem } from "@/types/datasets";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";

export const DATASET_ITEMS_PAGE_COLUMNS: ColumnData<DatasetItem>[] = [
  {
    id: "id",
    label: "Item ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "input",
    label: "Input",
    size: 400,
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.input)
        ? JSON.stringify(row.input, null, 2)
        : row.input || "",
    cell: CodeCell as never,
  },
  {
    id: "expected_output",
    label: "Expected output",
    size: 400,
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.expected_output)
        ? JSON.stringify(row.expected_output, null, 2)
        : row.expected_output || "",
    cell: CodeCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
  },
  {
    id: "metadata",
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.metadata)
        ? JSON.stringify(row.metadata, null, 2)
        : row.metadata || "",
    cell: CodeCell as never,
  },
];

export const DEFAULT_DATASET_ITEMS_PAGE_COLUMNS: string[] = [
  "id",
  "input",
  "expected_output",
  "created_at",
];
