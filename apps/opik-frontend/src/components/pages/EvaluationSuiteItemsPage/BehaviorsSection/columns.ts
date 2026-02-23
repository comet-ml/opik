import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";
import MetricTypeCell from "./MetricTypeCell";
import ExpectedBehaviorCell from "./ExpectedBehaviorCell";

export const EVALUATOR_COLUMNS: ColumnData<BehaviorDisplayRow>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "assertions",
    label: "Assertions",
    type: COLUMN_TYPE.string,
    cell: ExpectedBehaviorCell as never,
  },
  {
    id: "type",
    label: "Metric type",
    type: COLUMN_TYPE.string,
    cell: MetricTypeCell as never,
  },
];
