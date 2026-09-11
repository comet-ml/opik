import { describe, it, expect } from "vitest";

import { getPromptFromExperiment } from "./TrialPromptCell";
import { getCandidatePrompt } from "./candidatePrompt";
import { Experiment } from "@/types/datasets";

const makeExperiment = (id: string, configuration: unknown): Experiment =>
  ({ id, metadata: { configuration } }) as unknown as Experiment;

describe("getPromptFromExperiment", () => {
  // The bug this guards: the trials table rendered the optimizer's single-brace
  // form while the overview's best-trial panel rendered the authored
  // double-brace form, so two surfaces on the same run page disagreed. That is
  // worse than the original papercut, where both were consistently wrong.
  it("restores the authored {{variable}} syntax for display", () => {
    const experiment = makeExperiment("e1", {
      prompt: [{ role: "user", content: "Answer {question}" }],
    });

    expect(getPromptFromExperiment(experiment)).toEqual([
      { role: "user", content: "Answer {{question}}" },
    ]);
  });

  it("restores the legacy prompt_messages key too", () => {
    const experiment = makeExperiment("e2", {
      prompt_messages: [{ role: "user", content: "Answer {question}" }],
    });

    expect(getPromptFromExperiment(experiment)).toEqual([
      { role: "user", content: "Answer {{question}}" },
    ]);
  });

  it("returns null when the experiment carries no prompt", () => {
    expect(
      getPromptFromExperiment(makeExperiment("e3", { model: "gpt-4o-mini" })),
    ).toBeNull();
    expect(
      getPromptFromExperiment({ id: "e4" } as unknown as Experiment),
    ).toBeNull();
  });

  // This is the invariant that actually matters. Two near-identical resolvers
  // read a trial's prompt — this one (single experiment, used by the trials
  // table cell and its diff hover card) and getCandidatePrompt (candidate +
  // experiment map, used by the overview panel and the trial sidebar). The
  // duplication is why the surfaces drifted apart in the first place, so pin
  // them to the same output until one shared resolver replaces both.
  describe("agreement with getCandidatePrompt", () => {
    const cases: Array<[string, unknown]> = [
      [
        "message array under `prompt`",
        { prompt: [{ role: "user", content: "Answer {question}" }] },
      ],
      [
        "message array under legacy `prompt_messages`",
        { prompt_messages: [{ role: "system", content: "Use {context}" }] },
      ],
      [
        "single-prompt named wrapper",
        { prompt: { "chat-prompt": [{ role: "user", content: "Hi {name}" }] } },
      ],
      [
        "multimodal content parts",
        {
          prompt: [
            {
              role: "user",
              content: [
                { type: "text", text: "Describe {image_alt}" },
                { type: "image_url", image_url: { url: "http://x/y{z}.png" } },
              ],
            },
          ],
        },
      ],
      [
        "prompt containing literal JSON braces",
        {
          prompt: [
            { role: "user", content: 'Reply with {"ok": true} for {question}' },
          ],
        },
      ],
    ];

    it.each(cases)("agrees on a %s", (_label, configuration) => {
      const experiment = makeExperiment("e1", configuration);
      const experimentsById = new Map([["e1", experiment]]);

      expect(getPromptFromExperiment(experiment)).toEqual(
        getCandidatePrompt({ experimentIds: ["e1"] }, experimentsById),
      );
    });
  });
});
