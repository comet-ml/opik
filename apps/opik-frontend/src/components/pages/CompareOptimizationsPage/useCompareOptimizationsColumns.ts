import { useMemo } from "react";
import get from "lodash/get";
import isObject from "lodash/isObject";

import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import {
  OPTIMIZATION_EXAMPLES_KEY,
  OPTIMIZATION_OPTIMIZER_KEY,
  OPTIMIZATION_PROMPT_KEY,
} from "@/constants/experiments";
import { getOptimizerLabel } from "@/lib/optimizations";
import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { toString } from "@/lib/utils";
import { convertColumnDataToColumn } from "@/lib/table";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import ObjectiveScoreCell from "@/components/pages/CompareOptimizationsPage/ObjectiveScoreCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";

type ScoreData = {
  score: number;
  percentage?: number;
};

type UseCompareOptimizationsColumnsParams = {
  optimization: Optimization | undefined;
  scoreMap: Record<string, ScoreData>;
  columnsOrder: string[];
  selectedColumns: string[];
  sortableBy: string[];
};

export const useCompareOptimizationsColumns = ({
  optimization,
  scoreMap,
  columnsOrder,
  selectedColumns,
  sortableBy,
}: UseCompareOptimizationsColumnsParams) => {
  const columnsDef: ColumnData<Experiment>[] = useMemo(() => {
    if (!optimization?.objective_name) return [];

    return [
      {
        id: COLUMN_NAME_ID,
        label: "Trial",
        type: COLUMN_TYPE.string,
        cell: TextCell as never,
        sortable: true,
      },
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
      },
      {
        id: "optimizer",
        label: "Optimizer",
        type: COLUMN_TYPE.string,
        size: 200,
        accessorFn: (row) => {
          const metadataVal = get(
            row.metadata ?? {},
            OPTIMIZATION_OPTIMIZER_KEY,
          );
          if (metadataVal) {
            return isObject(metadataVal)
              ? JSON.stringify(metadataVal, null, 2)
              : toString(metadataVal);
          }
          const studioVal = optimization?.studio_config?.optimizer?.type;
          return studioVal ? getOptimizerLabel(studioVal) : "-";
        },
      },
      {
        id: "prompt",
        label: "Prompt",
        type: COLUMN_TYPE.string,
        size: 400,
        accessorFn: (row) => {
          const val = get(row.metadata ?? {}, OPTIMIZATION_PROMPT_KEY, "-");

          return isObject(val) ? JSON.stringify(val, null, 2) : toString(val);
        },
      },
      {
        id: "examples",
        label: "Examples",
        type: COLUMN_TYPE.string,
        size: 400,
        accessorFn: (row) => {
          const val = get(row.metadata ?? {}, OPTIMIZATION_EXAMPLES_KEY, "-");

          return isObject(val) ? JSON.stringify(val, null, 2) : toString(val);
        },
      },
      {
        id: `objective_name`,
        label: optimization.objective_name,
        type: COLUMN_TYPE.number,
        header: FeedbackScoreHeader as never,
        cell: ObjectiveScoreCell as never,
        customMeta: {
          scoreMap,
        },
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
  }, [
    optimization?.objective_name,
    scoreMap,
    optimization?.studio_config?.optimizer?.type,
  ]);

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<Experiment, Experiment>(columnsDef, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
    ];
  }, [columnsDef, columnsOrder, selectedColumns, sortableBy]);

  return { columnsDef, columns };
};
