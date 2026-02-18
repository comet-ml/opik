import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { BehaviorDisplayRow } from "@/types/evaluation-suites";
import BehaviorNameCell from "./BehaviorNameCell";
import MetricTypeCell from "./MetricTypeCell";

export const BEHAVIOR_COLUMNS: ColumnData<BehaviorDisplayRow>[] = [
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: BehaviorNameCell as never,
  },
  {
    id: "metric_type",
    label: "Metric type",
    type: COLUMN_TYPE.string,
    cell: MetricTypeCell as never,
  },
];
