import { useMemo } from "react";

import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { convertColumnDataToColumn } from "@/lib/table";
import TrialStatusCell from "@/components/pages/CompareOptimizationsPage/TrialStatusCell";
import {
  TrialNumberCell,
  TrialStepCell,
  TrialAccuracyCell,
  TrialCandidateCostCell,
  TrialCandidateLatencyCell,
} from "@/components/pages/CompareOptimizationsPage/TrialMetricCells";

type UseCompareOptimizationsColumnsParams = {
  candidates: AggregatedCandidate[];
  columnsOrder: string[];
  selectedColumns: string[];
  sortableBy: string[];
  isOptimizationFinished?: boolean;
  bestCandidateId?: string;
  isEvaluationSuite?: boolean;
};

export const useCompareOptimizationsColumns = ({
  candidates,
  columnsOrder,
  selectedColumns,
  sortableBy,
  isOptimizationFinished,
  bestCandidateId,
  isEvaluationSuite,
}: UseCompareOptimizationsColumnsParams) => {
  const columnsDef: ColumnData<AggregatedCandidate>[] = useMemo(() => {
    return [
      {
        id: COLUMN_NAME_ID,
        label: "Trial #",
        type: COLUMN_TYPE.string,
        size: 100,
        cell: TrialNumberCell as never,
      },
      {
        id: "step",
        label: "Step",
        type: COLUMN_TYPE.string,
        size: 100,
        accessorFn: (row) => row.stepIndex,
        cell: TrialStepCell as never,
      },
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
      },
      {
        id: "objective_name",
        label: isEvaluationSuite ? "Pass rate" : "Accuracy",
        type: COLUMN_TYPE.numberDictionary,
        size: 160,
        accessorFn: (row) => row.score,
        cell: TrialAccuracyCell as never,
        customMeta: {
          candidates,
          isEvaluationSuite,
        },
      },
      {
        id: "runtime_cost",
        label: "Runtime cost",
        type: COLUMN_TYPE.cost,
        size: 160,
        accessorFn: (row) => row.runtimeCost,
        cell: TrialCandidateCostCell as never,
        customMeta: {
          candidates,
        },
      },
      {
        id: "latency",
        label: "Latency",
        type: COLUMN_TYPE.duration,
        size: 160,
        accessorFn: (row) => row.latencyP50,
        cell: TrialCandidateLatencyCell as never,
        customMeta: {
          candidates,
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
        cell: TrialStatusCell as never,
        customMeta: {
          candidates,
          isOptimizationFinished,
          bestCandidateId,
        },
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
      },
    ];
  }, [candidates, isOptimizationFinished, bestCandidateId, isEvaluationSuite]);

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
