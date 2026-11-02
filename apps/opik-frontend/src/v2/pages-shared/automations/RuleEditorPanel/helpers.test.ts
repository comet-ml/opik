import { describe, expect, it } from "vitest";

import { getCodeArguments, mergeMessagesIntoPrompt } from "./helpers";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

describe("mergeMessagesIntoPrompt", () => {
  it("joins the text of every message into one user message, and keeps the user media", () => {
    expect(
      mergeMessagesIntoPrompt([
        { id: "1", role: LLM_MESSAGE_ROLE.system, content: "You are a judge." },
        {
          id: "2",
          role: LLM_MESSAGE_ROLE.user,
          content: [
            { type: "text", text: "INPUT: {{input}}" },
            { type: "text", text: "OUTPUT: {{output}}" },
            { type: "image_url", image_url: { url: "{{input.image}}" } },
          ],
        },
        { id: "3", role: LLM_MESSAGE_ROLE.assistant, content: "" },
      ]),
    ).toEqual([
      {
        id: "1",
        role: LLM_MESSAGE_ROLE.user,
        content: [
          {
            type: "text",
            text: "You are a judge.\n\nINPUT: {{input}}\nOUTPUT: {{output}}",
          },
          { type: "image_url", image_url: { url: "{{input.image}}" } },
        ],
      },
    ]);
  });
});

describe("getCodeArguments", () => {
  it("keeps saved mappings, maps known names to themselves, skips optional unknowns and reports required unknowns", () => {
    expect(
      getCodeArguments(
        "class M:\n    def score(self, question, output, answer, threshold: float = 0.5, **kw):\n        pass",
        EVALUATORS_RULE_SCOPE.trace,
        { question: "input.question" },
      ),
    ).toEqual({
      arguments: { question: "input.question", output: "output" },
      unknownArguments: ["answer"],
      parsingError: false,
    });
  });
});
