import { describe, it, expect } from "vitest";

import { getCandidatePrompt, toComparisonCandidate } from "./candidatePrompt";
import { Experiment } from "@/types/datasets";
import { AggregatedCandidate } from "@/types/optimizations";

const makeExperiment = (id: string, configuration: unknown): Experiment =>
  ({ id, metadata: { configuration } }) as unknown as Experiment;

// As stored by the optimizer: single-brace (Python-style) variables, because the
// python-backend rewrites the user's {{text}} to {text} on ingest.
const messages = [
  { role: "system", content: "You are a classifier." },
  { role: "user", content: "{text}" },
];

// As displayed: the resolver restores the syntax the user actually authored.
const restoredMessages = [
  { role: "system", content: "You are a classifier." },
  { role: "user", content: "{{text}}" },
];

describe("getCandidatePrompt", () => {
  it("reads the prompt from a trial experiment's configuration", () => {
    const experimentsById = new Map([
      ["e1", makeExperiment("e1", { prompt: messages })],
    ]);

    const prompt = getCandidatePrompt(
      { experimentIds: ["e1"] },
      experimentsById,
    );

    expect(prompt).toEqual(restoredMessages);
  });

  it("falls back to prompt_messages", () => {
    const experimentsById = new Map([
      ["e2", makeExperiment("e2", { prompt_messages: messages })],
    ]);

    const prompt = getCandidatePrompt(
      { experimentIds: ["e2"] },
      experimentsById,
    );

    expect(prompt).toEqual(restoredMessages);
  });

  // The user-visible bug this guards: a prompt authored as {{text}} was being
  // displayed as {text} everywhere downstream of the run, reading as if the user
  // had typed a broken prompt.
  it("restores the authored {{variable}} syntax for display", () => {
    const experimentsById = new Map([
      [
        "e1",
        makeExperiment("e1", {
          prompt: [{ role: "user", content: "Answer {question}" }],
        }),
      ],
    ]);

    expect(
      getCandidatePrompt({ experimentIds: ["e1"] }, experimentsById),
    ).toEqual([{ role: "user", content: "Answer {{question}}" }]);
  });

  it("returns null when no experiment resolves a prompt", () => {
    const experimentsById = new Map([
      ["e3", makeExperiment("e3", { model: "gpt-4o-mini" })],
    ]);

    expect(
      getCandidatePrompt({ experimentIds: ["e3"] }, experimentsById),
    ).toBeNull();
    expect(
      getCandidatePrompt({ experimentIds: ["missing"] }, experimentsById),
    ).toBeNull();
  });
});

describe("toComparisonCandidate", () => {
  it("maps a candidate to the structural comparison shape", () => {
    const candidate = {
      candidateId: "c2",
      stepIndex: 1,
      parentCandidateIds: ["c1"],
      trialNumber: 2,
      experimentIds: ["e2"],
    } as unknown as AggregatedCandidate;

    expect(toComparisonCandidate(candidate)).toEqual({
      id: "c2",
      stepIndex: 1,
      parentCandidateIds: ["c1"],
      trialNumber: 2,
    });
  });
});
