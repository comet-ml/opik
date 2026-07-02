import { useMemo } from "react";

import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import { convertColumnDataToColumn } from "@/lib/table";
import TrialStatusCell from "@/v2/pages/OptimizationPage/TrialStatusCell";
import {
  TrialNumberCell,
  TrialStepCell,
  TrialAccuracyCell,
  TrialCandidateCostCell,
  TrialCandidateLatencyCell,
} from "@/v2/pages/OptimizationPage/TrialMetricCells";
import { TrialPromptCell } from "@/v2/pages/OptimizationPage/TrialPromptCell";
import { getObjectiveLabel } from "@/lib/optimizations";
import type { TrialStatus } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

type UseOptimizationColumnsParams = {
  experiments: Experiment[];
  baselineExperiment?: Experiment;
  columnsOrder: string[];
  selectedColumns: string[];
  sortableBy: string[];
  bestCandidateId?: string;
  baselineCandidate?: AggregatedCandidate;
  isTestSuite?: boolean;
  /** Page-level status map — the single status source shared with the chart. */
  statusMap: Map<string, TrialStatus>;
  /** Opens the trial sidebar on the Prompt tab (prompt cell's diff button). */
  onViewPromptDiff: (row: AggregatedCandidate) => void;
  objectiveName?: string;
};

export const useOptimizationColumns = ({
  experiments,
  baselineExperiment,
  columnsOrder,
  selectedColumns,
  sortableBy,
  bestCandidateId,
  baselineCandidate,
  isTestSuite,
  statusMap,
  onViewPromptDiff,
  objectiveName,
}: UseOptimizationColumnsParams) => {
  const experimentMap = useMemo(
    () => new Map(experiments.map((e) => [e.id, e])),
    [experiments],
  );

  const columnsDef: ColumnData<AggregatedCandidate>[] = useMemo(() => {
    return [
      {
        id: COLUMN_NAME_ID,
        label: "Trial #",
        type: COLUMN_TYPE.string,
        size: 100,
        cell: TrialNumberCell,
      },
      {
        id: "step",
        label: "Step",
        type: COLUMN_TYPE.string,
        size: 100,
        accessorFn: (row) => row.stepIndex,
        cell: TrialStepCell,
      },
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
      },
      {
        id: "objective_name",
        label: getObjectiveLabel(isTestSuite, objectiveName),
        type: COLUMN_TYPE.numberDictionary,
        size: 160,
        accessorFn: (row) => row.score,
        cell: TrialAccuracyCell,
        customMeta: {
          baselineCandidate,
          isTestSuite,
        },
      },
      {
        id: "runtime_cost",
        label: "Runtime cost",
        type: COLUMN_TYPE.cost,
        size: 160,
        accessorFn: (row) => row.runtimeCost,
        cell: TrialCandidateCostCell,
        customMeta: {
          baselineCandidate,
        },
      },
      {
        id: "latency",
        label: "Latency",
        type: COLUMN_TYPE.duration,
        size: 160,
        accessorFn: (row) => row.latencyP50,
        cell: TrialCandidateLatencyCell,
        customMeta: {
          baselineCandidate,
        },
      },
      {
        id: "prompt",
        label: "Prompt",
        type: COLUMN_TYPE.string,
        size: 280,
        accessorFn: (row) => row.experimentIds?.[0],
        cell: TrialPromptCell,
        customMeta: {
          experimentMap,
          baselineExperiment,
          onViewPromptDiff,
        },
      },
      {
        id: "trial_status",
        label: "Status",
        type: COLUMN_TYPE.string,
        size: 120,
        accessorFn: () => undefined,
        cell: TrialStatusCell,
        customMeta: {
          statusMap,
          bestCandidateId,
          isTestSuite,
        },
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        cell: TimeCell,
        customMeta: {
          timeMode: "absolute",
        },
      },
    ];
  }, [
    experimentMap,
    baselineExperiment,
    bestCandidateId,
    baselineCandidate,
    isTestSuite,
    statusMap,
    onViewPromptDiff,
    objectiveName,
  ]);

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<AggregatedCandidate, AggregatedCandidate>(
        columnsDef,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
    ];
  }, [columnsDef, columnsOrder, selectedColumns, sortableBy]);

  return { columnsDef, columns };
};
