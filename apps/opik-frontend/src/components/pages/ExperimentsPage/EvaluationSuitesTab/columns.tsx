import { ColumnData, COLUMN_TYPE } from "@/types/shared";
import { Experiment } from "@/types/datasets";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import { formatDate } from "@/lib/date";

export const EVALUATION_SUITES_PINNED_COLUMN: ColumnData<Experiment> = {
  id: "dataset_name",
  label: "Evaluation suite",
  type: COLUMN_TYPE.string,
  sortable: false,
  size: 250,
};

export const EVALUATION_SUITES_SELECTABLE_COLUMNS: ColumnData<Experiment>[] = [
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row: Experiment) => formatDate(row.created_at),
  },
  {
    id: "duration.p50",
    label: "Duration (avg.)",
    type: COLUMN_TYPE.duration,
    accessorFn: (row: Experiment) => row.duration?.p50,
    cell: DurationCell as never,
  },
  {
    id: "total_estimated_cost_avg",
    label: "Cost per trace (avg.)",
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
  },
  {
    id: "pass_rate",
    label: "Pass rate",
    type: COLUMN_TYPE.string,
    accessorFn: (row: Experiment) => {
      // When BE provides pass_rate on experiment, display as "76.2% (16/21)"
      const record = row as unknown as Record<string, unknown>;
      const passRate = record.pass_rate as number | undefined;
      const passedCount = record.passed_count as number | undefined;
      const totalCount = record.total_count as number | undefined;
      if (passRate != null && passedCount != null && totalCount != null) {
        return `${(passRate * 100).toFixed(1)}% (${passedCount}/${totalCount})`;
      }
      return "\u2014";
    },
  },
];

export const EVALUATION_SUITES_DEFAULT_SELECTED_COLUMNS: string[] = [
  "created_at",
  "duration.p50",
  "total_estimated_cost_avg",
  "pass_rate",
];
