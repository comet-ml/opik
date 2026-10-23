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

  it("should keep a message-count filter through the edit-then-save cycle", () => {
    // The full cycle a persisted rule goes through: the API returns filters
    // with no type, the edit path resolves the type from the column list, and
    // the save path builds the payload from the resolved filter. Without the
    // column entry the type resolution degrades to a string row and the
    // operator/value the user configured are lost.
    const saved = [
      {
        id: "1",
        field: "number_of_messages",
        type: "",
        operator: ">=",
        value: "5",
        key: "",
      },
    ] as Parameters<typeof normalizeFilters>[0];

    const reopened = normalizeFilters(
      saved,
      THREAD_FILTER_COLUMNS as Parameters<typeof normalizeFilters>[1],
    );
    const resaved = buildRuleFilters(reopened);

    expect(resaved).toEqual([
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
