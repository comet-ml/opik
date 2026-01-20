import uniq from "lodash/uniq";
import get from "lodash/get";

import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { Experiment } from "@/types/datasets";
import {
  COLUMN_TYPE,
  ColumnData,
  COLUMN_ID_ID,
  COLUMN_DATASET_ID,
} from "@/types/shared";
import { Filters } from "@/types/filters";
import { createFilter, isFilterValid } from "@/lib/filters";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import TraceCountCell from "@/components/shared/DataTableCells/TraceCountCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import MultiResourceCell from "@/components/shared/DataTableCells/MultiResourceCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { getJSONPaths } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import { parseScoreColumnId } from "@/components/pages-shared/experiments/scoresUtils";
import { DEFAULT_MAX_EXPERIMENTS } from "@/lib/dashboard/utils";

export {
  parseScoreColumnId,
  getExperimentScore,
  buildScoreLabel,
} from "@/components/pages-shared/experiments/scoresUtils";

export const isSelectExperimentsMode = (dataSource: EXPERIMENT_DATA_SOURCE) =>
  dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

interface ExperimentListParamsInput {
  dataSource: EXPERIMENT_DATA_SOURCE;
  experimentIds: string[];
  filters: Filters;
}

export const getExperimentListParams = ({
  dataSource,
  experimentIds,
  filters,
}: ExperimentListParamsInput) => {
  const isSelectMode = isSelectExperimentsMode(dataSource);
  const validFilters = filters.filter(isFilterValid);
  return {
    experimentIds: isSelectMode ? experimentIds : undefined,
    filters: !isSelectMode ? validFilters : undefined,
    isEnabled: isSelectMode ? experimentIds.length > 0 : true,
  };
};

export const PREDEFINED_COLUMNS: ColumnData<Experiment>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: COLUMN_DATASET_ID,
    label: "Dataset",
    type: COLUMN_TYPE.string,
    cell: ResourceCell as never,
    customMeta: {
      nameKey: "dataset_name",
      idKey: "dataset_id",
      resource: RESOURCE_TYPE.dataset,
    },
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
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
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
    id: "prompt",
    label: "Prompt commit",
    type: COLUMN_TYPE.list,
    accessorFn: (row) => get(row, ["prompt_versions"], []),
    cell: MultiResourceCell as never,
    customMeta: {
      nameKey: "commit",
      idKey: "prompt_id",
      resource: RESOURCE_TYPE.prompt,
      getSearch: (data: Experiment) => ({
        activeVersionId: get(data, "id", null),
      }),
    },
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

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_DATASET_ID,
  "created_at",
  "duration.p50",
];

export const getDefaultConfig = () => ({
  overrideDefaults: false,
  dataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
  experimentIds: [],
  selectedColumns: DEFAULT_SELECTED_COLUMNS,
  enableRanking: true,
  columnsOrder: DEFAULT_SELECTED_COLUMNS,
  scoresColumnsOrder: [],
  metadataColumnsOrder: [],
  columnsWidth: {},
  maxRows: DEFAULT_MAX_EXPERIMENTS,
  sorting: [],
});

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

export const formatConfigColumnName = (key: string): string => {
  return `config.${key}`;
};

interface GetRankingSortingParams {
  rankingMetric?: string;
  rankingDirection?: boolean;
}

export const getRankingSorting = ({
  rankingMetric,
  rankingDirection = true,
}: GetRankingSortingParams) => {
  if (!rankingMetric) return undefined;
  return [{ id: rankingMetric, desc: rankingDirection }];
};

export const getRankingFilters = (
  rankingMetric: string | undefined,
  existingFilters: Filters = [],
): Filters => {
  if (!rankingMetric) return existingFilters;

  const parsedScore = parseScoreColumnId(rankingMetric);

  return parsedScore
    ? [
        ...existingFilters,
        createFilter({
          id: `ranking-filter-${rankingMetric}`,
          field: parsedScore.scoreType,
          operator: "is_not_empty",
          key: parsedScore.scoreName,
        }),
      ]
    : existingFilters;
};

export const widgetHelpers = {
  getDefaultConfig,
  calculateTitle: () => "Experiment leaderboard",
};
