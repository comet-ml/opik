import { describe, expect, it } from "vitest";

import { isCostMetricType, widgetHelpers } from "./helpers";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";

describe("ProjectMetricsWidget helpers", () => {
  describe("isCostMetricType", () => {
    it.each([
      METRIC_NAME_TYPE.COST,
      METRIC_NAME_TYPE.THREAD_COST,
      METRIC_NAME_TYPE.SPAN_COST,
    ])("treats %s as a cost metric", (metricName) => {
      expect(isCostMetricType(metricName)).toBe(true);
    });

    it.each([
      METRIC_NAME_TYPE.TRACE_COUNT,
      METRIC_NAME_TYPE.THREAD_COUNT,
      METRIC_NAME_TYPE.SPAN_COUNT,
      METRIC_NAME_TYPE.TRACE_DURATION,
      METRIC_NAME_TYPE.THREAD_DURATION,
      METRIC_NAME_TYPE.TOKEN_USAGE,
      METRIC_NAME_TYPE.FEEDBACK_SCORES,
    ])("does not treat %s as a cost metric", (metricName) => {
      expect(isCostMetricType(metricName)).toBe(false);
    });

    it("handles an unset metric", () => {
      expect(isCostMetricType(undefined)).toBe(false);
    });
  });

  describe("calculateTitle", () => {
    it.each([
      [METRIC_NAME_TYPE.COST, "Estimated cost"],
      [METRIC_NAME_TYPE.THREAD_COST, "Thread estimated cost"],
      [METRIC_NAME_TYPE.SPAN_COST, "Span estimated cost"],
    ])("labels %s as %s", (metricType, expected) => {
      expect(widgetHelpers.calculateTitle({ metricType })).toBe(expected);
    });

    it("falls back to the default title for an unknown metric", () => {
      expect(widgetHelpers.calculateTitle({ metricType: "NOPE" })).toBe(
        "Project metrics",
      );
    });
  });
});
