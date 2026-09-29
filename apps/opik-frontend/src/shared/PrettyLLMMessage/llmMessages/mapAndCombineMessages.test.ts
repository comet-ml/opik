import { describe, it, expect } from "vitest";
import { mapAndCombineMessages } from "./mapAndCombineMessages";

const textOf = (message: { blocks: { blockType: string }[] }) => {
  const block = message.blocks[0];
  return block.blockType === "text"
    ? (block as unknown as { props: { children: string } }).props.children
    : undefined;
};

describe("mapAndCombineMessages", () => {
  describe("playground output alongside openai input", () => {
    const input = {
      messages: [
        { role: "system", content: "You are a support assistant." },
        { role: "user", content: "Can I cancel?" },
      ],
    };

    it("should combine the openai input and the playground output in order", () => {
      const result = mapAndCombineMessages(input, {
        output: "Yes, with a full refund.",
      });

      expect(result.messages.map((m) => m.role)).toEqual([
        "system",
        "user",
        "assistant",
      ]);
      expect(result.messages.map(textOf)).toEqual([
        "You are a support assistant.",
        "Can I cancel?",
        "Yes, with a full refund.",
      ]);
    });

    it("should keep the input turns when the run produced no output", () => {
      const result = mapAndCombineMessages(input, { output: "" });

      expect(result.messages.map((m) => m.role)).toEqual(["system", "user"]);
    });

    it("should keep the input turns when the output shape is unrecognized", () => {
      const result = mapAndCombineMessages(input, { unexpected: { a: 1 } });

      expect(result.messages.map((m) => m.role)).toEqual(["system", "user"]);
    });

    it("should map a multi-turn conversation with the output appended last", () => {
      const result = mapAndCombineMessages(
        {
          messages: [
            { role: "system", content: "s" },
            { role: "user", content: "u1" },
            { role: "assistant", content: "a1" },
            { role: "user", content: "u2" },
          ],
        },
        { output: "a2" },
      );

      expect(result.messages.map((m) => m.role)).toEqual([
        "system",
        "user",
        "assistant",
        "user",
        "assistant",
      ]);
      expect(textOf(result.messages[4])).toBe("a2");
    });
  });

  it("should still combine an openai input with an openai choices output", () => {
    const result = mapAndCombineMessages(
      { messages: [{ role: "user", content: "hi" }] },
      { choices: [{ message: { role: "assistant", content: "hello" } }] },
    );

    expect(result.messages.map((m) => m.role)).toEqual(["user", "assistant"]);
  });
});
