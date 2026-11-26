import { describe, expect, it } from "vitest";

import { COLUMN_TYPE } from "@/types/shared";
import { FilterOperator } from "@/types/filters";
import { ChipDefinition } from "@/shared/filter-chips/types";
import {
  fromAutomationFilters,
  incompleteAutomationBlocks,
  splitAutomationFilters,
  toAutomationChipDefinitions,
  toAutomationFilters,
} from "./automationFilters";

const START_TIME: ChipDefinition = {
  id: "start_time",
  field: "start_time",
  label: "Start time",
  kind: "time",
  columnType: COLUMN_TYPE.time,
};

const DEFINITIONS: ChipDefinition[] = [
  {
    id: "duration",
    field: "duration",
    label: "Duration",
    kind: "numeric",
    columnType: COLUMN_TYPE.duration,
    format: "duration",
  },
  {
    id: "with_errors",
    field: "error_info",
    label: "With errors",
    kind: "boolean",
    onOperator: "is_not_empty",
    columnType: COLUMN_TYPE.errors,
  },
  {
    id: "feedback_scores",
    field: "feedback_scores",
    label: "Trace feedback scores",
    kind: "query-builder",
    columnType: COLUMN_TYPE.numberDictionary,
    operators: [">=", "<=", ">", "<", "="],
    key: { options: { value: { items: [], isLoading: false } } },
    value: { type: "numeric" },
  },
];

describe("automation filters", () => {
  it("stores durations in milliseconds, as the backend filters on them", () => {
    expect(
      toAutomationFilters(DEFINITIONS, {
        duration: { mode: "atLeast", min: 2 },
      }),
    ).toEqual([{ field: "duration", operator: ">=", value: "2000" }]);
  });

  it("reads back what it stored", () => {
    const values = {
      duration: { mode: "atLeast" as const, min: 2 },
      with_errors: { applied: true as const },
      feedback_scores: {
        rows: [
          {
            id: "row-1",
            field: "feedback_scores",
            type: COLUMN_TYPE.numberDictionary,
            operator: "<=" as const,
            key: "accuracy",
            value: "0.5",
          },
        ],
      },
    };

    const stored = toAutomationFilters(DEFINITIONS, values);
    const restored = fromAutomationFilters(DEFINITIONS, stored);

    expect(restored.duration).toEqual(values.duration);
    expect(restored.with_errors).toEqual(values.with_errors);
    expect(restored.feedback_scores).toEqual({
      rows: [
        expect.objectContaining({
          field: "feedback_scores",
          operator: "<=",
          key: "accuracy",
          value: "0.5",
        }),
      ],
    });
  });

  describe("stored filters the dialog cannot represent", () => {
    const score = (operator: FilterOperator, value: string) => ({
      field: "feedback_scores",
      operator,
      key: "safety",
      value,
    });

    it("shows strict score thresholds as they are, so saving does not turn < into <=", () => {
      const stored = [score("<", "0.5"), score(">", "0.1")];

      expect(splitAutomationFilters(DEFINITIONS, stored)).toEqual({
        shown: stored,
        kept: [],
      });
    });

    it("keeps a filter its block would rewrite: a strict duration becomes inclusive in a block", () => {
      const duration = {
        field: "duration",
        operator: ">" as const,
        value: "1000",
      };

      expect(splitAutomationFilters(DEFINITIONS, [duration]).kept).toEqual([
        duration,
      ]);
    });

    it("keeps a filter its block would drop: an operator the block does not offer, or a field with no block", () => {
      const unsupported = score("!=", "1");
      const noBlock = {
        field: "guardrails",
        operator: "=" as const,
        value: "failed",
      };

      expect(
        splitAutomationFilters(DEFINITIONS, [unsupported, noBlock]).kept,
      ).toEqual([unsupported, noBlock]);
    });

    it("shows every filter the blocks themselves produce, time ranges included", () => {
      const definitions = [...DEFINITIONS, START_TIME];
      const stored = toAutomationFilters(definitions, {
        duration: { mode: "between", min: 1.5, max: 3 },
        with_errors: { applied: true },
        start_time: {
          mode: "between",
          start: "2026-11-01T10:00:00.000Z",
          end: "2026-11-02T18:30:00.000Z",
        },
        feedback_scores: {
          rows: [
            {
              id: "row-1",
              field: "feedback_scores",
              type: COLUMN_TYPE.numberDictionary,
              operator: "<" as const,
              key: "safety",
              value: "0.5",
            },
          ],
        },
      });

      expect(splitAutomationFilters(definitions, stored).kept).toEqual([]);
    });
  });

  describe("incomplete blocks", () => {
    const scoreRow = (key: string, value: string) => ({
      id: `row-${key}-${value}`,
      field: "feedback_scores",
      type: COLUMN_TYPE.numberDictionary,
      operator: ">=" as const,
      key,
      value,
    });

    it("flags a block added but never filled in", () => {
      expect(
        incompleteAutomationBlocks(DEFINITIONS, {
          ids: ["duration"],
          values: {},
        }),
      ).toEqual(["duration"]);
    });

    it("flags a block with a row filled in only partly, next to a complete one", () => {
      expect(
        incompleteAutomationBlocks(DEFINITIONS, {
          ids: ["feedback_scores"],
          values: {
            feedback_scores: {
              rows: [scoreRow("safety", "0.5"), scoreRow("helpfulness", "")],
            },
          },
        }),
      ).toEqual(["feedback_scores"]);
    });

    it("accepts complete blocks, toggles and blank extra rows", () => {
      expect(
        incompleteAutomationBlocks(DEFINITIONS, {
          ids: ["feedback_scores", "with_errors", "duration"],
          values: {
            feedback_scores: {
              rows: [scoreRow("safety", "0.5"), scoreRow("", "")],
            },
            with_errors: { applied: true },
            duration: { mode: "atLeast", min: 5 },
          },
        }),
      ).toEqual([]);
    });
  });

  it("leaves out filters that pick specific items", () => {
    const ids = toAutomationChipDefinitions([
      ...DEFINITIONS,
      { ...DEFINITIONS[0], id: "id", field: "id" },
      { ...DEFINITIONS[0], id: "thread_id", field: "thread_id" },
      {
        ...DEFINITIONS[0],
        id: "annotation_queue_ids",
        field: "annotation_queue_ids",
      },
    ] as ChipDefinition[]).map((def) => def.id);

    expect(ids).toEqual(["duration", "with_errors", "feedback_scores"]);
  });
});
