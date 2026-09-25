import { describe, it, expect } from "vitest";
import { splitOutputForMessages } from "./splitOutputForMessages";

describe("splitOutputForMessages", () => {
  describe("unrecognised output", () => {
    it("falls back to the JSON viewer for a structured task result", () => {
      expect(splitOutputForMessages({ answer: "Yes", score: 1 })).toEqual({
        rendersAsMessages: false,
        remainingOutput: {},
      });
    });

    it("falls back when output is an object under the output key", () => {
      expect(
        splitOutputForMessages({ output: { answer: "Yes" } }).rendersAsMessages,
      ).toBe(false);
    });

    it("falls back for empty and missing output", () => {
      expect(splitOutputForMessages({}).rendersAsMessages).toBe(false);
      expect(splitOutputForMessages(undefined).rendersAsMessages).toBe(false);
    });

    it("falls back when a recognised shape maps to no messages", () => {
      expect(splitOutputForMessages({ output: "" })).toEqual({
        rendersAsMessages: false,
        remainingOutput: {},
      });
      expect(
        splitOutputForMessages({ output: "", context: ["policy.md"] })
          .rendersAsMessages,
      ).toBe(false);
    });
  });

  describe("single-key output shapes", () => {
    it("keeps sibling keys next to a playground-style output", () => {
      expect(
        splitOutputForMessages({
          output: "Yes, a full refund.",
          context: ["policy.md"],
          reasoning: "Within 24 hours",
        }),
      ).toEqual({
        rendersAsMessages: true,
        remainingOutput: {
          context: ["policy.md"],
          reasoning: "Within 24 hours",
        },
      });
    });

    it("reports nothing remaining for a bare playground output", () => {
      expect(splitOutputForMessages({ output: "Yes" })).toEqual({
        rendersAsMessages: true,
        remainingOutput: {},
      });
    });

    it("keeps sibling keys next to a text output but not usage or finish_reason", () => {
      expect(
        splitOutputForMessages({
          text: "Yes",
          usage: { total_tokens: 3 },
          finish_reason: "stop",
          sources: ["policy.md"],
        }),
      ).toEqual({
        rendersAsMessages: true,
        remainingOutput: { sources: ["policy.md"] },
      });
    });
  });

  describe("full provider responses", () => {
    it("renders an OpenAI chat completion without a remainder", () => {
      expect(
        splitOutputForMessages({
          id: "chatcmpl-1",
          model: "gpt-4o",
          choices: [
            {
              index: 0,
              message: { role: "assistant", content: "Yes" },
              finish_reason: "stop",
            },
          ],
        }),
      ).toEqual({ rendersAsMessages: true, remainingOutput: {} });
    });
  });
});
