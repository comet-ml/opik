import { describe, expect, it } from "vitest";

import { COLUMN_TYPE } from "@/types/shared";

import { THREAD_FILTER_COLUMNS } from "./constants";
import { buildRuleFilters, normalizeFilters } from "./helpers";

describe("THREAD_FILTER_COLUMNS", () => {
  it("should expose the message count so a thread rule can filter on it", () => {
    // The threads list endpoint accepts number_of_messages as a NUMBER filter
    // field (TraceThreadField.NUMBER_OF_MESSAGES, pushed down to ClickHouse),
    // and the dashboards filter section exposes the same column; the rule
    // editor was the only thread filter surface missing it.
    expect(
      THREAD_FILTER_COLUMNS.find((c) => c.id === "number_of_messages"),
    ).toEqual({
      id: "number_of_messages",
      label: "Message count",
      type: COLUMN_TYPE.number,
    });
  });

  it("should resolve a saved message-count filter to the number type on re-open", () => {
    // Filters persisted by the API carry no type, so the edit path falls back
    // to the column list: without the column entry a saved number_of_messages
    // filter would re-open as a string row (contains) instead of a number row.
    const result = normalizeFilters(
      [
        {
          id: "1",
          field: "number_of_messages",
          type: "",
          operator: ">=",
          value: "5",
          key: "",
        },
      ],
      THREAD_FILTER_COLUMNS as Parameters<typeof normalizeFilters>[1],
    );

    expect(result).toEqual([
      {
        id: "1",
        field: "number_of_messages",
        type: COLUMN_TYPE.number,
        operator: ">=",
        value: "5",
        key: "",
      },
    ]);
  });

  it("should round-trip a message-count filter through the rule payload", () => {
    const result = buildRuleFilters([
      {
        id: "1",
        field: "number_of_messages",
        type: COLUMN_TYPE.number,
        operator: ">=",
        value: "5",
        key: "",
      },
    ]);

    expect(result).toEqual([
      {
        id: "1",
        field: "number_of_messages",
        type: COLUMN_TYPE.number,
        operator: ">=",
        value: "5",
        key: "",
      },
    ]);
  });
});
