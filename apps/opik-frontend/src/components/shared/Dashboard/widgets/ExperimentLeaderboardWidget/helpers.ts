import uniq from "lodash/uniq";

import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { Experiment } from "@/types/datasets";
import { COLUMN_TYPE, ColumnData, COLUMN_ID_ID } from "@/types/shared";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import TraceCountCell from "@/components/shared/DataTableCells/TraceCountCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import { getJSONPaths } from "@/lib/utils";

export const PREDEFINED_COLUMNS: ColumnData<Experiment>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "duration.p50",
    label: "Duration (avg.)",
    type: COLUMN_TYPE.duration,
    accessorFn: (row) => row.duration?.p50,
    cell: DurationCell as never,
  },
  {
    id: "duration.p90",
    label: "Duration (p90)",
    type: COLUMN_TYPE.duration,
    accessorFn: (row) => row.duration?.p90,
    cell: DurationCell as never,
  },
  {
    id: "duration.p99",
    label: "Duration (p99)",
    type: COLUMN_TYPE.duration,
    accessorFn: (row) => row.duration?.p99,
    cell: DurationCell as never,
  },
  {
    id: "trace_count",
    label: "Trace count",
    type: COLUMN_TYPE.number,
    cell: TraceCountCell as never,
    customMeta: {
      tooltip: "View experiment traces",
    },
  },
  {
    id: "total_estimated_cost",
    label: "Total estimated cost",
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
  },
  {
    id: "total_estimated_cost_avg",
    label: "Cost per trace (avg.)",
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
  },
];

export const getDefaultConfig = () => ({
  overrideDefaults: false,
  dataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
  experimentIds: [],
  selectedColumns: ["duration.p50"],
  enableRanking: true,
  columnsOrder: ["duration.p50"],
  scoresColumnsOrder: [],
  metadataColumnsOrder: [],
  columnsWidth: {},
  maxRows: 20,
  sorting: [],
});

export const calculateTitle = (config: Record<string, unknown>) => {
  const enableRanking = config.enableRanking as boolean;
  const rankingMetric = config.rankingMetric as string | undefined;

  if (enableRanking && rankingMetric) {
    return `Experiment leaderboard (${formatMetricName(rankingMetric)})`;
  }
  return "Experiment leaderboard";
};

export const parseMetadataKeys = (experiments: Experiment[]): string[] => {
  const allKeys = experiments.reduce<string[]>((acc, exp) => {
    if (exp.metadata) {
      const paths = getJSONPaths(exp.metadata, "", [], true);
      acc.push(...paths);
    }
    return acc;
  }, []);

  return uniq(allKeys).sort();
};

export const formatMetricName = (metric: string): string => {
  const metricLabels: Record<string, string> = {
    "duration.p50": "Duration (avg.)",
    "duration.p90": "Duration (p90)",
    "duration.p99": "Duration (p99)",
    trace_count: "Trace count",
    total_estimated_cost: "Total cost",
    total_estimated_cost_avg: "Cost per trace",
  };
  return metricLabels[metric] || metric;
};

export const formatConfigColumnName = (key: string): string => {
  return `config.${key}`;
};

export const widgetHelpers = {
  getDefaultConfig,
  calculateTitle,
};
