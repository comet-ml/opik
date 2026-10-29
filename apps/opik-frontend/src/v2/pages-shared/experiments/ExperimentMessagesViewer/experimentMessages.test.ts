import { describe, it, expect } from "vitest";
import { mapAndCombineMessages } from "@/shared/PrettyLLMMessage/llmMessages";

/**
 * The compare view decides between the role-by-role renderer and the JSON/YAML
 * viewer purely on whether any messages come back, so these cover the payload
 * shapes experiment items actually carry (OPIK-7965).
 */
describe("experiment item message detection", () => {
  it("renders an OpenAI-style chat experiment item role by role", () => {
    const input = {
      messages: [
        { role: "system", content: "You are a support agent." },
        { role: "user", content: "How do I cancel?" },
      ],
    };
    const output = { output: "You can cancel from the bookings page." };

    const { messages } = mapAndCombineMessages(input, output);

    expect(messages.map((m) => m.role)).toEqual([
      "system",
      "user",
      "assistant",
    ]);
  });

  it("keeps the system message that the string prettifier dropped", () => {
    const input = {
      messages: [
        { role: "system", content: "Answer in French." },
        { role: "user", content: "Hello" },
      ],
    };

    const { messages } = mapAndCombineMessages(input, undefined);

    expect(messages.some((m) => m.role === "system")).toBe(true);
  });

  it("preserves an unrendered prompt template's variables", () => {
    const input = {
      messages: [{ role: "user", content: "Summarise {{text}} in one line." }],
    };

    const { messages } = mapAndCombineMessages(input, undefined);
    const block = messages[0].blocks[0];

    expect(block.blockType).toBe("text");
    if (block.blockType === "text") {
      expect(block.props.children).toContain("{{text}}");
    }
  });

  it("reports no messages for plain dataset columns so the JSON viewer stays", () => {
    const { messages } = mapAndCombineMessages(
      { question: "How do I cancel?", expected: "Full refund" },
      undefined,
    );

    expect(messages).toEqual([]);
  });

  it("reports no messages for an empty item", () => {
    expect(mapAndCombineMessages({}, {}).messages).toEqual([]);
  });

  it("still detects messages once Playground editor state is stripped", () => {
    // What the Playground now stores: role and content only, with the editor's
    // id/promptId/promptVersionId/autoImprove left out of the experiment config.
    const templateMessages = [
      { role: "system", content: "You are terse." },
      { role: "user", content: "Summarise {{text}}." },
    ];

    const { messages } = mapAndCombineMessages(
      { messages: templateMessages },
      undefined,
    );

    expect(messages.map((m) => m.role)).toEqual(["system", "user"]);
  });
});
