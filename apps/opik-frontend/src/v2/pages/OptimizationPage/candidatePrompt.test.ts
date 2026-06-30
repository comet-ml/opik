import { describe, it, expect } from "vitest";

import { getCandidatePrompt } from "./candidatePrompt";
import { Experiment } from "@/types/datasets";

const makeExperiment = (id: string, configuration: unknown): Experiment =>
  ({ id, metadata: { configuration } }) as unknown as Experiment;

const messages = [
  { role: "system", content: "You are a classifier." },
  { role: "user", content: "{text}" },
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

    expect(prompt).toEqual(messages);
  });

  it("falls back to prompt_messages", () => {
    const experimentsById = new Map([
      ["e2", makeExperiment("e2", { prompt_messages: messages })],
    ]);

    const prompt = getCandidatePrompt(
      { experimentIds: ["e2"] },
      experimentsById,
    );

    expect(prompt).toEqual(messages);
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
