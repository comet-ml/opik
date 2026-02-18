import { describe, it, expect } from "vitest";
import { detectLangChainFormat } from "./detector";

describe("detectLangChainFormat", () => {
  describe("Input formats", () => {
    it("should detect flat LangGraph state messages", () => {
      const data = {
        messages: [
          { type: "human", content: "Hello" },
          { type: "ai", content: "Hi there!" },
        ],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should detect batched LangChain messages", () => {
      const data = {
        messages: [
          [
            { type: "human", content: "Hello", id: null, name: null },
            { type: "ai", content: "Hi!", id: null, tool_calls: [] },
          ],
        ],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should detect messages with all valid types", () => {
      const data = {
        messages: [
          { type: "human", content: "Hello" },
          { type: "ai", content: "Let me check" },
          { type: "system", content: "You are helpful" },
          { type: "tool", content: "result", name: "search" },
        ],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should detect chat type messages", () => {
      const data = {
        messages: [{ type: "chat", content: "Hello" }],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should detect function type messages", () => {
      const data = {
        messages: [{ type: "function", content: "result" }],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should reject empty messages array", () => {
      const data = { messages: [] };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(false);
    });

    it("should reject messages with role field (OpenAI format)", () => {
      const data = {
        messages: [
          { role: "user", content: "Hello" },
          { role: "assistant", content: "Hi" },
        ],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(false);
    });

    it("should reject messages with invalid type values", () => {
      const data = {
        messages: [{ type: "text", content: "Hello" }],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(false);
    });

    it("should reject empty batched messages", () => {
      const data = { messages: [[]] };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(false);
    });
  });

  describe("Output formats", () => {
    it("should detect flat output messages", () => {
      const data = {
        messages: [
          {
            type: "ai",
            content: "Response",
            response_metadata: { finish_reason: "stop" },
          },
        ],
      };
      expect(detectLangChainFormat(data, { fieldType: "output" })).toBe(true);
    });

    it("should detect generations format", () => {
      const data = {
        generations: [
          [
            {
              text: "Response text",
              generation_info: { finish_reason: "stop" },
              type: "ChatGeneration",
            },
          ],
        ],
      };
      expect(detectLangChainFormat(data, { fieldType: "output" })).toBe(true);
    });

    it("should reject multi-batch generations", () => {
      const data = {
        generations: [[{ text: "Response 1" }], [{ text: "Response 2" }]],
      };
      expect(detectLangChainFormat(data, { fieldType: "output" })).toBe(false);
    });

    it("should reject empty generations", () => {
      const data = { generations: [[]] };
      expect(detectLangChainFormat(data, { fieldType: "output" })).toBe(false);
    });

    it("should reject generations without text field", () => {
      const data = {
        generations: [[{ content: "not text" }]],
      };
      expect(detectLangChainFormat(data, { fieldType: "output" })).toBe(false);
    });
  });

  describe("Edge cases", () => {
    it("should reject null data", () => {
      expect(detectLangChainFormat(null, { fieldType: "input" })).toBe(false);
    });

    it("should reject undefined data", () => {
      expect(detectLangChainFormat(undefined, { fieldType: "input" })).toBe(
        false,
      );
    });

    it("should reject when fieldType not specified", () => {
      const data = {
        messages: [{ type: "human", content: "Hello" }],
      };
      expect(detectLangChainFormat(data, {})).toBe(false);
      expect(detectLangChainFormat(data)).toBe(false);
    });

    it("should not false-positive on OpenAI format", () => {
      const data = {
        messages: [
          { role: "user", content: "Hello" },
          { role: "assistant", content: "Hi" },
        ],
      };
      expect(detectLangChainFormat(data, { fieldType: "input" })).toBe(false);
      expect(detectLangChainFormat(data, { fieldType: "output" })).toBe(false);
    });

    it("should not detect OpenAI choices format", () => {
      const data = {
        choices: [{ message: { role: "assistant", content: "Hi" }, index: 0 }],
      };
      expect(detectLangChainFormat(data, { fieldType: "output" })).toBe(false);
    });
  });
});
