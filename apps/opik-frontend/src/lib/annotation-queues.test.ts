import { describe, it, expect } from "vitest";
import {
  getItemState,
  getDistinctAnnotatorCount,
  isItemProcessedByUser,
  hashCode,
  ITEM_STATE,
} from "./annotation-queues";
import { Trace, FEEDBACK_SCORE_TYPE } from "@/types/traces";

const makeTrace = (overrides?: Partial<Trace>): Trace =>
  ({
    id: "trace-1",
    name: "test",
    input: {},
    output: {},
    start_time: "",
    end_time: "",
    duration: 0,
    created_at: "",
    last_updated_at: "",
    metadata: {},
    tags: [],
    project_id: "proj-1",
    feedback_scores: [],
    comments: [],
    ...overrides,
  }) as Trace;

const makeScore = (
  name: string,
  lastUpdatedBy: string,
  valueByAuthor?: Record<
    string,
    { value: number; source: FEEDBACK_SCORE_TYPE; last_updated_at: string }
  >,
) => ({
  name,
  value: 1,
  source: FEEDBACK_SCORE_TYPE.ui,
  last_updated_by: lastUpdatedBy,
  last_updated_at: new Date().toISOString(),
  ...(valueByAuthor ? { value_by_author: valueByAuthor } : {}),
});

describe("getItemState", () => {
  it("should return DEFAULT when item has no feedback scores or comments", () => {
    const item = makeTrace();
    expect(getItemState(item, ["accuracy"], "alice", 1)).toBe(
      ITEM_STATE.DEFAULT,
    );
  });

  it("should return SCORED when current user has a feedback score", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("accuracy", "alice")],
    });
    expect(getItemState(item, ["accuracy"], "alice", 2)).toBe(
      ITEM_STATE.SCORED,
    );
  });

  it("should return SCORED when current user has a comment", () => {
    const item = makeTrace({
      comments: [
        { id: "c1", text: "good", created_by: "alice", created_at: "" },
      ],
    } as Partial<Trace>);
    expect(getItemState(item, [], "alice", 2)).toBe(ITEM_STATE.SCORED);
  });

  it("should return COMPLETED when distinct annotator count meets threshold", () => {
    const item = makeTrace({
      feedback_scores: [
        makeScore("accuracy", "alice"),
        makeScore("accuracy", "bob"),
      ],
    });
    expect(getItemState(item, ["accuracy"], "alice", 2)).toBe(
      ITEM_STATE.COMPLETED,
    );
  });

  it("should return COMPLETED even if current user has not scored", () => {
    const item = makeTrace({
      feedback_scores: [
        makeScore("accuracy", "bob"),
        makeScore("accuracy", "charlie"),
      ],
    });
    expect(getItemState(item, ["accuracy"], "alice", 2)).toBe(
      ITEM_STATE.COMPLETED,
    );
  });
});

describe("getDistinctAnnotatorCount", () => {
  it("should count authors from value_by_author", () => {
    const item = makeTrace({
      feedback_scores: [
        makeScore("accuracy", "alice", {
          alice: {
            value: 1,
            source: FEEDBACK_SCORE_TYPE.ui,
            last_updated_at: "",
          },
          bob: {
            value: 0.8,
            source: FEEDBACK_SCORE_TYPE.ui,
            last_updated_at: "",
          },
        }),
      ],
    });
    expect(getDistinctAnnotatorCount(item, ["accuracy"])).toBe(2);
  });

  it("should count comment authors", () => {
    const item = makeTrace({
      comments: [
        { id: "c1", text: "good", created_by: "alice", created_at: "" },
        { id: "c2", text: "ok", created_by: "bob", created_at: "" },
      ],
    } as Partial<Trace>);
    expect(getDistinctAnnotatorCount(item, [])).toBe(2);
  });

  it("should deduplicate users who both scored and commented", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("accuracy", "alice")],
      comments: [
        { id: "c1", text: "good", created_by: "alice", created_at: "" },
      ],
    } as Partial<Trace>);
    expect(getDistinctAnnotatorCount(item, ["accuracy"])).toBe(1);
  });

  it("should ignore scores not in feedbackScoreNames", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("irrelevant", "alice")],
    });
    expect(getDistinctAnnotatorCount(item, ["accuracy"])).toBe(0);
  });
});

describe("isItemProcessedByUser", () => {
  it("should return false when userName is undefined", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("accuracy", "alice")],
    });
    expect(isItemProcessedByUser(item, ["accuracy"], undefined)).toBe(false);
  });

  it("should return true when user has a matching feedback score", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("accuracy", "alice")],
    });
    expect(isItemProcessedByUser(item, ["accuracy"], "alice")).toBe(true);
  });

  it("should return false when user has a score but not for the right feedback name", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("other", "alice")],
    });
    expect(isItemProcessedByUser(item, ["accuracy"], "alice")).toBe(false);
  });

  it("should return true when user has a comment even without scores", () => {
    const item = makeTrace({
      comments: [
        { id: "c1", text: "note", created_by: "alice", created_at: "" },
      ],
    } as Partial<Trace>);
    expect(isItemProcessedByUser(item, [], "alice")).toBe(true);
  });
});

describe("getItemState with lockStatus", () => {
  it("should return IN_REVIEW when all slots taken by other users' locks", () => {
    const item = makeTrace();
    const lockStatus = { active_locks: 1, locked_by: ["bob"] };
    expect(getItemState(item, ["accuracy"], "alice", 1, lockStatus)).toBe(
      ITEM_STATE.IN_REVIEW,
    );
  });

  it("should return DEFAULT when locked by current user", () => {
    const item = makeTrace();
    const lockStatus = { active_locks: 1, locked_by: ["alice"] };
    expect(getItemState(item, ["accuracy"], "alice", 1, lockStatus)).toBe(
      ITEM_STATE.DEFAULT,
    );
  });

  it("should return DEFAULT when locked but slots still available", () => {
    const item = makeTrace();
    const lockStatus = { active_locks: 1, locked_by: ["bob"] };
    expect(getItemState(item, ["accuracy"], "alice", 2, lockStatus)).toBe(
      ITEM_STATE.DEFAULT,
    );
  });

  it("should return IN_REVIEW when scored + locks fill all slots", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("accuracy", "bob")],
    });
    const lockStatus = { active_locks: 1, locked_by: ["charlie"] };
    expect(getItemState(item, ["accuracy"], "alice", 2, lockStatus)).toBe(
      ITEM_STATE.IN_REVIEW,
    );
  });

  it("should return COMPLETED over IN_REVIEW when fully scored", () => {
    const item = makeTrace({
      feedback_scores: [
        makeScore("accuracy", "bob"),
        makeScore("accuracy", "charlie"),
      ],
    });
    const lockStatus = { active_locks: 1, locked_by: ["dave"] };
    expect(getItemState(item, ["accuracy"], "alice", 2, lockStatus)).toBe(
      ITEM_STATE.COMPLETED,
    );
  });

  it("should return SCORED over IN_REVIEW when current user has scored", () => {
    const item = makeTrace({
      feedback_scores: [makeScore("accuracy", "alice")],
    });
    const lockStatus = { active_locks: 1, locked_by: ["bob"] };
    expect(getItemState(item, ["accuracy"], "alice", 2, lockStatus)).toBe(
      ITEM_STATE.SCORED,
    );
  });

  it("should return DEFAULT when no lockStatus provided", () => {
    const item = makeTrace();
    expect(getItemState(item, ["accuracy"], "alice", 1)).toBe(
      ITEM_STATE.DEFAULT,
    );
  });
});

describe("hashCode", () => {
  it("should return the same value for the same input", () => {
    expect(hashCode("test")).toBe(hashCode("test"));
  });

  it("should return different values for different inputs", () => {
    expect(hashCode("alice+queue1")).not.toBe(hashCode("bob+queue1"));
  });

  it("should return a number", () => {
    expect(typeof hashCode("test")).toBe("number");
  });

  it("should handle empty string", () => {
    expect(hashCode("")).toBe(0);
  });
});
