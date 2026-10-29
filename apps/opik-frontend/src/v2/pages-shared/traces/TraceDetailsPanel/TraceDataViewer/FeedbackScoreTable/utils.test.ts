import { describe, expect, it } from "vitest";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { ExpandingFeedbackScoreRow } from "./types";
import {
  getHasDeletableFeedbackScoreRow,
  getIsDeletableFeedbackScoreRow,
} from "./utils";

const buildRow = (
  overrides: Partial<ExpandingFeedbackScoreRow> = {},
): ExpandingFeedbackScoreRow => ({
  id: "row",
  name: "accuracy",
  value: 1,
  source: FEEDBACK_SCORE_TYPE.sdk,
  ...overrides,
});

describe("getIsDeletableFeedbackScoreRow", () => {
  it("is deletable when the current user authored the score", () => {
    expect(
      getIsDeletableFeedbackScoreRow(buildRow({ author: "alice" }), "alice"),
    ).toBe(true);
  });

  it("falls back to created_by when author is missing", () => {
    expect(
      getIsDeletableFeedbackScoreRow(
        buildRow({ created_by: "alice" }),
        "alice",
      ),
    ).toBe(true);
  });

  it("is not deletable when another user authored the score", () => {
    expect(
      getIsDeletableFeedbackScoreRow(buildRow({ created_by: "bob" }), "alice"),
    ).toBe(false);
  });

  it("is not deletable for a parent row", () => {
    const parent = buildRow({
      created_by: "alice",
      subRows: [buildRow({ id: "child", author: "alice" })],
    });

    expect(getIsDeletableFeedbackScoreRow(parent, "alice")).toBe(false);
  });
});

describe("getHasDeletableFeedbackScoreRow", () => {
  it("returns false when no row belongs to the current user", () => {
    const rows = [
      buildRow({ id: "a", created_by: "bob" }),
      buildRow({ id: "b", created_by: "online-eval" }),
    ];

    expect(getHasDeletableFeedbackScoreRow(rows, "alice")).toBe(false);
  });

  it("returns false for an empty table", () => {
    expect(getHasDeletableFeedbackScoreRow([], "alice")).toBe(false);
  });

  it("finds a deletable child row nested under a parent", () => {
    const rows = [
      buildRow({
        id: "parent",
        subRows: [
          buildRow({ id: "child-1", author: "bob" }),
          buildRow({ id: "child-2", author: "alice" }),
        ],
      }),
    ];

    expect(getHasDeletableFeedbackScoreRow(rows, "alice")).toBe(true);
  });
});
