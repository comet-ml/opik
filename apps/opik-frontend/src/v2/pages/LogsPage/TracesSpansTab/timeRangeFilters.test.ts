import { describe, expect, it } from "vitest";
import { COLUMN_TYPE } from "@/types/shared";
import { createStartTimeRangeFilters } from "./timeRangeFilters";

describe("createStartTimeRangeFilters", () => {
  it("filters records by their start time within the selected range", () => {
    expect(
      createStartTimeRangeFilters(
        "2026-07-15T00:00:00Z",
        "2026-07-16T00:00:00Z",
      ),
    ).toEqual([
      {
        id: "logs_start_time_from_range",
        field: "start_time",
        type: COLUMN_TYPE.time,
        operator: ">=",
        value: "2026-07-15T00:00:00Z",
      },
      {
        id: "logs_start_time_to_range",
        field: "start_time",
        type: COLUMN_TYPE.time,
        operator: "<=",
        value: "2026-07-16T00:00:00Z",
      },
    ]);
  });

  it("supports an open-ended range", () => {
    expect(createStartTimeRangeFilters("2026-07-15T00:00:00Z")).toEqual([
      {
        id: "logs_start_time_from_range",
        field: "start_time",
        type: COLUMN_TYPE.time,
        operator: ">=",
        value: "2026-07-15T00:00:00Z",
      },
    ]);
    expect(
      createStartTimeRangeFilters(undefined, "2026-07-16T00:00:00Z"),
    ).toEqual([
      {
        id: "logs_start_time_to_range",
        field: "start_time",
        type: COLUMN_TYPE.time,
        operator: "<=",
        value: "2026-07-16T00:00:00Z",
      },
    ]);
  });
});
