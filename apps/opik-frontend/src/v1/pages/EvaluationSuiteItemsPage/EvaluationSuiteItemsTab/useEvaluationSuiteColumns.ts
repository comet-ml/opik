import { useMemo } from "react";
import get from "lodash/get";

import { DatasetItem } from "@/types/datasets";
import {
  COLUMN_ID_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
} from "@/types/shared";
import AutodetectCell from "@/shared/DataTableCells/AutodetectCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import { AssertionsCountCell } from "./AssertionsCountCell";
import { ExecutionPolicyCell } from "./ExecutionPolicyCell";

interface UseEvaluationSuiteColumnsParams {
  isEvaluationSuite: boolean;
  dynamicDatasetColumns: DynamicColumn[];
}

export function useEvaluationSuiteColumns({
  isEvaluationSuite,
  dynamicDatasetColumns,
}: UseEvaluationSuiteColumnsParams): ColumnData<DatasetItem>[] {
  return useMemo((): ColumnData<DatasetItem>[] => {
    const cols: ColumnData<DatasetItem>[] = [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
      },
    ];

    if (isEvaluationSuite) {
      cols.push(
        {
          id: "description",
          label: "Description",
          type: COLUMN_TYPE.string,
          accessorFn: (row) => row.description ?? "",
        },
        {
          id: "last_updated_at",
          label: "Last updated",
          type: COLUMN_TYPE.time,
          cell: TimeCell as never,
        },
        {
          id: "data",
          label: "Data",
          type: COLUMN_TYPE.dictionary,
          accessorFn: (row) => row.data,
          cell: AutodetectCell as never,
          size: 400,
        },
        {
          id: "assertions",
          label: "Assertions",
          type: COLUMN_TYPE.string,
          iconType: "assertions",
          cell: AssertionsCountCell as never,
          size: 120,
        },
        {
          id: "execution_policy",
          label: "Execution policy",
          type: COLUMN_TYPE.string,
          iconType: "execution_policy",
          cell: ExecutionPolicyCell as never,
        },
      );
    } else {
      cols.push(
        ...dynamicDatasetColumns.map(
          ({ label, id, columnType }) =>
            ({
              id,
              label,
              type: columnType,
              accessorFn: (row) => get(row, ["data", label], ""),
              cell: AutodetectCell as never,
            }) as ColumnData<DatasetItem>,
        ),
      );
    }

    cols.push(
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
        cell: TimeCell as never,
      },
    );

    if (!isEvaluationSuite) {
      cols.push({
        id: "last_updated_at",
        label: "Last updated",
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
      });
    }

    cols.push({
      id: "created_by",
      label: "Created by",
      type: COLUMN_TYPE.string,
    });

    return cols;
  }, [isEvaluationSuite, dynamicDatasetColumns]);
}
