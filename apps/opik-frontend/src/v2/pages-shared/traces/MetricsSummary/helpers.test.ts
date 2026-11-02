import { describe, expect, it } from "vitest";

import {
  KpiEntityType,
  KpiMetricType,
} from "@/api/projects/useProjectKpiCards";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { getChartConfig } from "./helpers";

describe("MetricsSummary helpers", () => {
  describe("getChartConfig", () => {
    it.each<[KpiMetricType, KpiEntityType, METRIC_NAME_TYPE, string]>([
      ["count", "traces", METRIC_NAME_TYPE.TRACE_COUNT, "traces"],
      ["count", "spans", METRIC_NAME_TYPE.SPAN_COUNT, "spans"],
      ["count", "threads", METRIC_NAME_TYPE.THREAD_COUNT, "threads"],
      [
        "errors",
        "traces",
        METRIC_NAME_TYPE.TRACE_ERROR_RATE,
        "trace_error_rate",
      ],
      ["errors", "spans", METRIC_NAME_TYPE.SPAN_ERROR_RATE, "span_error_rate"],
      [
        "avg_duration",
        "traces",
        METRIC_NAME_TYPE.TRACE_AVERAGE_DURATION,
        "trace_average_duration",
      ],
      [
        "avg_duration",
        "spans",
        METRIC_NAME_TYPE.SPAN_AVERAGE_DURATION,
        "span_average_duration",
      ],
      [
        "avg_duration",
        "threads",
        METRIC_NAME_TYPE.THREAD_AVERAGE_DURATION,
        "thread_average_duration",
      ],
      ["total_cost", "traces", METRIC_NAME_TYPE.COST, "cost"],
      ["total_cost", "spans", METRIC_NAME_TYPE.SPAN_COST, "span_cost"],
      ["total_cost", "threads", METRIC_NAME_TYPE.THREAD_COST, "thread_cost"],
    ])(
      "maps %s on %s to %s and colors the %s series",
      (kpiType, entityType, metricName, lineName) => {
        const config = getChartConfig(kpiType, entityType);

        expect(config.metricName).toBe(metricName);
        expect(Object.keys(config.colorMap ?? {})).toEqual([lineName]);
      },
    );

    it.each<[KpiEntityType, string]>([
      ["traces", "cost"],
      ["spans", "span_cost"],
      ["threads", "thread_cost"],
    ])(
      "labels the %s cost series %s with the card title",
      (entityType, lineName) => {
        expect(getChartConfig("total_cost", entityType).labelsMap).toEqual({
          [lineName]: "Total cost",
        });
      },
    );
  });
});
