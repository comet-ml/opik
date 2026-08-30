import { describe, expect, it } from "vitest";

import { FeedbackScoreBatch } from "@/rest_api/serialization/types/FeedbackScoreBatch.js";
import { FeedbackScoreBatchThread } from "@/rest_api/serialization/resources/traces/client/requests/FeedbackScoreBatchThread.js";

// Mirrors the options the generated clients pass when building the PUT body
// (see traces/client/Client.ts __scoreBatchOfTraces / __scoreBatchOfThreads).
const SERIALIZE_OPTIONS = {
  unrecognizedObjectKeys: "strip",
  omitUndefined: true,
} as const;

describe("feedback score metadata serialization (fetch-boundary)", () => {
  it("trace/span batch keeps metadata after strip", () => {
    const metadata = {
      evaluator: "exact_match",
      revision: "abc123",
      nested: { ok: true },
    };

    const payload = FeedbackScoreBatch.jsonOrThrow(
      {
        scores: [
          {
            id: "00000000-0000-0000-0000-000000000001",
            name: "quality",
            value: 0.9,
            source: "sdk",
            metadata,
          },
        ],
      },
      SERIALIZE_OPTIONS,
    );

    const wire = JSON.parse(JSON.stringify(payload)) as {
      scores: Array<{ metadata?: Record<string, unknown> }>;
    };

    expect(wire.scores[0].metadata).toEqual(metadata);
  });

  it("thread batch keeps metadata after strip", () => {
    const metadata = { evaluator: "llm_as_judge", fingerprint: "fp1" };

    const payload = FeedbackScoreBatchThread.jsonOrThrow(
      {
        scores: [
          {
            name: "quality",
            value: 0.9,
            source: "sdk",
            threadId: "thread-1",
            metadata,
          },
        ],
      },
      SERIALIZE_OPTIONS,
    );

    const wire = JSON.parse(JSON.stringify(payload)) as {
      scores: Array<{ thread_id: string; metadata?: Record<string, unknown> }>;
    };

    expect(wire.scores[0].thread_id).toBe("thread-1");
    expect(wire.scores[0].metadata).toEqual(metadata);
  });

  it("omits metadata when not provided", () => {
    const payload = FeedbackScoreBatch.jsonOrThrow(
      {
        scores: [
          {
            id: "00000000-0000-0000-0000-000000000002",
            name: "quality",
            value: 0.9,
            source: "sdk",
          },
        ],
      },
      SERIALIZE_OPTIONS,
    );

    const wire = JSON.parse(JSON.stringify(payload)) as {
      scores: Array<Record<string, unknown>>;
    };

    expect(wire.scores[0]).not.toHaveProperty("metadata");
  });
});
