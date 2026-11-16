import { describe, expect, it } from "vitest";
import {
  createBulkAnnotationPayload,
  mergeFeedbackScoreIntoList,
  validateBulkAnnotation,
} from "./utils";
import { TraceFeedbackScore } from "@/types/traces";

describe("validateBulkAnnotation", () => {
  it("validates valid score data", () => {
    const res = validateBulkAnnotation({
      name: "accuracy",
      value: 0.95,
      reason: "High quality output",
    });
    expect(res.valid).toBe(true);
    expect(res.error).toBeUndefined();
  });

  it("fails if name is empty", () => {
    const res = validateBulkAnnotation({
      name: "   ",
      value: 1,
    });
    expect(res.valid).toBe(false);
    expect(res.error).toContain("Feedback score name is required");
  });

  it("fails if value is not a valid number or not finite", () => {
    const resNaN = validateBulkAnnotation({
      name: "accuracy",
      value: NaN,
    });
    expect(resNaN.valid).toBe(false);
    expect(resNaN.error).toContain("Feedback score value must be a valid finite number");

    const resInf = validateBulkAnnotation({
      name: "accuracy",
      value: Infinity,
    });
    expect(resInf.valid).toBe(false);
    expect(resInf.error).toContain("Feedback score value must be a valid finite number");

    const resNegInf = validateBulkAnnotation({
      name: "accuracy",
      value: -Infinity,
    });
    expect(resNegInf.valid).toBe(false);
    expect(resNegInf.error).toContain("Feedback score value must be a valid finite number");
  });
});

describe("createBulkAnnotationPayload", () => {
  it("formats payload with trimmed string values and filtered trace IDs", () => {
    const payload = createBulkAnnotationPayload(
      ["trace-1", "", "trace-2", "   "],
      {
        name: "  hallucination  ",
        value: 0,
        categoryName: "  none  ",
        reason: "  no hallucination detected  ",
      },
    );

    expect(payload.ids).toEqual(["trace-1", "trace-2"]);
    expect(payload.feedbackScore.name).toBe("hallucination");
    expect(payload.feedbackScore.value).toBe(0);
    expect(payload.feedbackScore.categoryName).toBe("none");
    expect(payload.feedbackScore.reason).toBe("no hallucination detected");
  });
});

describe("mergeFeedbackScoreIntoList", () => {
  it("appends new feedback score when not already present", () => {
    const existing: TraceFeedbackScore[] = [];
    const updated = mergeFeedbackScoreIntoList(
      existing,
      { name: "relevance", value: 1 },
      "alice",
    );
    expect(updated).toHaveLength(1);
    expect(updated[0]).toMatchObject({
      name: "relevance",
      value: 1,
      author: "alice",
      source: "ui",
    });
  });

  it("updates existing feedback score with matching name", () => {
    const existing: TraceFeedbackScore[] = [
      {
        name: "relevance",
        value: 0.5,
        author: "bob",
        source: "ui",
      },
    ];
    const updated = mergeFeedbackScoreIntoList(
      existing,
      { name: "relevance", value: 0.9, reason: "Reviewed manually" },
      "alice",
    );
    expect(updated).toHaveLength(1);
    expect(updated[0].value).toBe(0.9);
    expect(updated[0].author).toBe("alice");
    expect(updated[0].reason).toBe("Reviewed manually");
  });
});
