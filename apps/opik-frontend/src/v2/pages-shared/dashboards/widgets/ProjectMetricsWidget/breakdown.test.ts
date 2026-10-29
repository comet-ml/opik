import { describe, expect, it } from "vitest";

import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { BREAKDOWN_FIELD } from "@/types/dashboard";
import { getCompatibleBreakdownFields, getMetricEntityType } from "./breakdown";

describe("ProjectMetricsWidget breakdown", () => {
  describe("getMetricEntityType", () => {
    it.each([
      [METRIC_NAME_TYPE.COST, "trace"],
      [METRIC_NAME_TYPE.THREAD_COST, "thread"],
      [METRIC_NAME_TYPE.SPAN_COST, "span"],
    ])("treats %s as a %s metric", (metricName, entityType) => {
      expect(getMetricEntityType(metricName)).toBe(entityType);
    });
  });

  describe("getCompatibleBreakdownFields", () => {
    it.each([METRIC_NAME_TYPE.THREAD_COST, METRIC_NAME_TYPE.SPAN_COST])(
      "offers only no grouping for %s",
      (metricName) => {
        expect(getCompatibleBreakdownFields(metricName)).toEqual([
          BREAKDOWN_FIELD.NONE,
        ]);
      },
    );

    it.each([
      [METRIC_NAME_TYPE.COST, BREAKDOWN_FIELD.METADATA],
      [METRIC_NAME_TYPE.THREAD_DURATION, BREAKDOWN_FIELD.TAGS],
      [METRIC_NAME_TYPE.SPAN_TOKEN_USAGE, BREAKDOWN_FIELD.MODEL],
    ])("still offers %s grouping by %s", (metricName, field) => {
      expect(getCompatibleBreakdownFields(metricName)).toContain(field);
    });
  });
});
