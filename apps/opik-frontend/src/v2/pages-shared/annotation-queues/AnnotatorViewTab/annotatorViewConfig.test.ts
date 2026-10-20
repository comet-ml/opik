import { describe, expect, it } from "vitest";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import {
  DEFAULT_ANNOTATOR_FIELDS,
  buildFieldsTree,
  getAnnotatorFields,
  getFieldKind,
  getFieldScores,
  labelFromPath,
} from "./annotatorViewConfig";

const scores = [
  { name: "accuracy", value: 0.8, source: FEEDBACK_SCORE_TYPE.online_scoring },
  {
    name: "hallucination",
    value: 0,
    source: FEEDBACK_SCORE_TYPE.online_scoring,
  },
];

describe("getFieldKind", () => {
  it.each([
    ["attachments", "attachments"],
    ["feedback_scores", "feedback_scores"],
    ["feedback_scores.accuracy", "feedback_scores"],
    ["input.messages[-1].content", "value"],
    ["metadata", "value"],
  ])("%s is %s", (path, kind) => {
    expect(getFieldKind(path)).toBe(kind);
  });
});

describe("getFieldScores", () => {
  it("returns every score for the root path", () => {
    expect(getFieldScores("feedback_scores", scores)).toEqual(scores);
  });

  it("returns only the named score", () => {
    expect(getFieldScores("feedback_scores.accuracy", scores)).toEqual([
      scores[0],
    ]);
  });

  it("returns nothing when the named score is missing", () => {
    expect(getFieldScores("feedback_scores.tone", scores)).toEqual([]);
  });
});

describe("labelFromPath", () => {
  it.each([
    ["input", "Input"],
    ["attachments", "Attachments"],
    ["feedback_scores", "Feedback scores"],
    ["output.answer", "answer"],
    ["input.messages[-1].content", "content"],
    ["feedback_scores.accuracy", "accuracy"],
  ])("%s → %s", (path, label) => {
    expect(labelFromPath(path)).toBe(label);
  });
});

describe("buildFieldsTree", () => {
  it("keys feedback scores by name and adds attachments", () => {
    expect(
      buildFieldsTree({ input: { q: 1 }, output: { a: 2 } }, scores),
    ).toEqual({
      input: { q: 1 },
      output: { a: 2 },
      metadata: {},
      attachments: "All attachments",
      feedback_scores: { accuracy: 0.8, hallucination: 0 },
    });
  });
});

describe("getAnnotatorFields", () => {
  it("defaults to today's blocks when no fields are saved", () => {
    expect(getAnnotatorFields({})).toBe(DEFAULT_ANNOTATOR_FIELDS);
    expect(getAnnotatorFields({ fields: [] })).toEqual([]);
  });
});
