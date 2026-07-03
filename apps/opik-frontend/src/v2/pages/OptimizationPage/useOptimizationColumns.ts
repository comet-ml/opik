import { useMemo } from "react";

import {
  CELL_HORIZONTAL_ALIGNMENT,
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

type UseOptimizationColumnsParams = {
  candidates: AggregatedCandidate[];
  experiments: Experiment[];
  baselineExperiment?: Experiment;
  columnsOrder: string[];
  selectedColumns: string[];
  sortableBy: string[];
  bestCandidateId?: string;
  baselineCandidate?: AggregatedCandidate;
  isTestSuite?: boolean;
  isInProgress?: boolean;
  inProgressInfo?: {
    candidateId: string;
    stepIndex: number;
    parentCandidateIds: string[];
  };
  objectiveName?: string;
};

export const useOptimizationColumns = ({
  candidates,
  experiments,
  baselineExperiment,
  columnsOrder,
  selectedColumns,
  sortableBy,
  bestCandidateId,
  baselineCandidate,
  isTestSuite,
  isInProgress,
  inProgressInfo,
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
        label: "Trial",
        type: COLUMN_TYPE.string,
        size: 80,
        cell: TrialNumberCell,
      },
      {
        id: "step",
        label: "Step",
        type: COLUMN_TYPE.string,
        size: 80,
        accessorFn: (row) => row.stepIndex,
        cell: TrialStepCell,
      },
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
      },
      {
        id: "prompt",
        label: "Prompt",
        type: COLUMN_TYPE.string,
        size: 322,
        accessorFn: (row) => row.experimentIds?.[0],
        cell: TrialPromptCell,
        customMeta: {
          experimentMap,
          baselineExperiment,
        },
      },
      {
        id: "objective_name",
        label: getObjectiveLabel(isTestSuite, objectiveName),
        type: COLUMN_TYPE.numberDictionary,
        size: 130,
        // numberDictionary defaults to start; all metric columns key to the
        // right edge.
        horizontalAlignment: CELL_HORIZONTAL_ALIGNMENT.end,
        accessorFn: (row) => row.score,
        cell: TrialAccuracyCell,
        customMeta: {
          baselineCandidate,
          isTestSuite,
        },
      },
      {
        id: "runtime_cost",
        label: "Opt. cost",
        type: COLUMN_TYPE.cost,
        size: 130,
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
        size: 130,
        accessorFn: (row) => row.latencyP50,
        cell: TrialCandidateLatencyCell,
        customMeta: {
          baselineCandidate,
        },
      },
      {
        id: "trace_count",
        label: "Trial items",
        type: COLUMN_TYPE.number,
        size: 80,
        accessorFn: (row) => row.totalDatasetItemCount,
      },
      {
        id: "trial_status",
        label: "Status",
        type: COLUMN_TYPE.string,
        size: 120,
        accessorFn: () => undefined,
        cell: TrialStatusCell,
        customMeta: {
          candidates,
          bestCandidateId,
          isTestSuite,
          isInProgress,
          inProgressInfo,
        },
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        size: 140,
        // TimeCell is shared and typed for unknown rows; the one remaining cast.
        cell: TimeCell as never,
        customMeta: {
          timeMode: "absolute",
        },
      },
    ];
  }, [
    candidates,
    experimentMap,
    baselineExperiment,
    bestCandidateId,
    baselineCandidate,
    isTestSuite,
    isInProgress,
    inProgressInfo,
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
