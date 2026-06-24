import TimeCell from "@/shared/DataTableCells/TimeCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import { COLUMN_DATASET_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { Optimization } from "@/types/optimizations";
import { getFeedbackScore } from "@/lib/feedback-scores";
import { getOptimizerLabel } from "@/lib/optimizations";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import ItemSourceCell, {
  ITEM_SOURCE_LABEL,
} from "@/v2/pages-shared/experiments/ItemSourceCell";
import OptimizationStatusCell from "@/v2/pages/OptimizationsPage/OptimizationStatusCell";
import {
  OptimizationPassRateCell,
  OptimizationAccuracyCell,
  OptimizationLatencyCell,
  OptimizationCostCell,
  OptimizationTotalCostCell,
} from "@/v2/pages/OptimizationsPage/OptimizationMetricCells";

// NOTE: the `cell: X as never` casts match the codebase-wide convention
// (~100 files). They exist because `ColumnData.cell` in types/shared.ts is typed
// as tanstack's `Cell<T>` (the cell *instance* type) rather than a cell
// *renderer*, so every render function needs the cast. Removing them properly
// means re-typing the shared `ColumnData.cell` field, which ripples across all
// tables — out of scope here; tracked separately.

// Bumped from v1: drops the dead `deploy` column id from persisted prefs and
// adds the new Name / Run ID / Algorithm / Metric columns.
export const SELECTED_COLUMNS_KEY = "optimizations-selected-columns-v2";
export const COLUMNS_WIDTH_KEY = "optimizations-columns-width-v2";
export const COLUMNS_ORDER_KEY = "optimizations-columns-order-v2";

export const DEFAULT_COLUMNS: ColumnData<Optimization>[] = [
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.name,
    size: 220,
  },
  {
    id: "id",
    label: "Run ID",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.id,
    cell: IdCell as never,
    size: 160,
  },
  {
    id: "dataset_name",
    label: ITEM_SOURCE_LABEL,
    type: COLUMN_TYPE.string,
    cell: ItemSourceCell as never,
    customMeta: {
      nameKey: "dataset_name",
      idKey: "dataset_id",
      resource: RESOURCE_TYPE.testSuite,
    },
    size: 200,
  },
  {
    id: "algorithm",
    label: "Algorithm",
    type: COLUMN_TYPE.string,
    accessorFn: (row) =>
      row.studio_config?.optimizer?.type
        ? getOptimizerLabel(row.studio_config.optimizer.type)
        : "",
    size: 180,
  },
  {
    id: "metric",
    label: "Metric",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.objective_name,
    size: 160,
  },
  {
    id: "created_at",
    label: "Start time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    size: 140,
  },
  {
    id: "status",
    label: "Status",
    type: COLUMN_TYPE.string,
    cell: OptimizationStatusCell as never,
    size: 120,
  },
  {
    id: "pass_rate",
    label: "Pass rate",
    type: COLUMN_TYPE.numberDictionary,
    size: 200,
    accessorFn: (row) => row.best_objective_score,
    cell: OptimizationPassRateCell as never,
  },
  {
    id: "accuracy",
    label: "Accuracy",
    type: COLUMN_TYPE.numberDictionary,
    size: 200,
    accessorFn: (row) =>
      getFeedbackScore(row.feedback_scores ?? [], row.objective_name),
    cell: OptimizationAccuracyCell as never,
  },
  {
    id: "latency",
    label: "Latency",
    type: COLUMN_TYPE.duration,
    size: 180,
    accessorFn: (row) => row.best_duration,
    cell: OptimizationLatencyCell as never,
  },
  {
    id: "cost",
    label: "Cost",
    type: COLUMN_TYPE.cost,
    size: 180,
    accessorFn: (row) => row.best_cost,
    cell: OptimizationCostCell as never,
  },
  {
    id: "opt_cost",
    label: "Opt. cost",
    type: COLUMN_TYPE.cost,
    size: 120,
    accessorFn: (row) => row.total_optimization_cost,
    cell: OptimizationTotalCostCell as never,
  },
];

export const FILTER_COLUMNS: ColumnData<Optimization>[] = [
  {
    id: COLUMN_DATASET_ID,
    label: "Test suite",
    type: COLUMN_TYPE.string,
    disposable: true,
  },
];

// Default-visible set matches the Figma runs-list (562:37189): Name, Start time,
// Status, Pass rate, Accuracy, Latency, Cost, Opt. cost. The newly-added Run ID,
// Item source, Algorithm and Metric columns ship as available-but-hidden (users
// enable them from the Columns picker).
export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "name",
  "created_at",
  "status",
  "pass_rate",
  "accuracy",
  "latency",
  "cost",
  "opt_cost",
];

export const DEFAULT_COLUMNS_ORDER: string[] = [
  "name",
  "id",
  "dataset_name",
  "algorithm",
  "metric",
  "created_at",
  "status",
  "pass_rate",
  "accuracy",
  "latency",
  "cost",
  "opt_cost",
];
