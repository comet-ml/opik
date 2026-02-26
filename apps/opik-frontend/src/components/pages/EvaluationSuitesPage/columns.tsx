import IdCell from "@/components/shared/DataTableCells/IdCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { formatDate } from "@/lib/date";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";

export const TYPE_LABELS: Record<string, string> = {
  [DATASET_TYPE.EVALUATION_SUITE]: "Evaluation suite",
  [DATASET_TYPE.DATASET]: "Dataset",
};

export const DEFAULT_COLUMNS: ColumnData<Dataset>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "dataset_items_count",
    label: "Item count",
    type: COLUMN_TYPE.number,
  },
  {
    id: "most_recent_experiment_at",
    label: "Most recent experiment",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.most_recent_experiment_at),
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.string,
    accessorFn: (row) =>
      TYPE_LABELS[row.type ?? DATASET_TYPE.DATASET] ?? "Dataset",
  },
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    cell: ListCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const FILTERS_COLUMNS: ColumnData<Dataset>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.category,
  },
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "last_created_experiment_at",
    label: "Most recent experiment",
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "description",
  "dataset_items_count",
  "most_recent_experiment_at",
  "last_updated_at",
];
