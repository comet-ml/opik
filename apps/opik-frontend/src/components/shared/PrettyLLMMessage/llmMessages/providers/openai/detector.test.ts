import { describe, it, expect } from "vitest";
import { detectOpenAIFormat } from "./detector";

describe("detectOpenAIFormat", () => {
  describe("Input formats", () => {
    it("should detect standard OpenAI input format", () => {
      const data = {
        messages: [
          { role: "user", content: "Hello" },
          { role: "assistant", content: "Hi there!" },
        ],
      };
      expect(detectOpenAIFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should detect direct array format", () => {
      const data = [
        { role: "user", content: "Hello" },
        { role: "assistant", content: "Hi there!" },
      ];
      expect(detectOpenAIFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should detect custom input format with text/files", () => {
      const data = {
        input: [
          {
            role: "system",
            text: "You are a helpful assistant",
            files: [],
          },
          {
            role: "user",
            text: "What is 2+2?",
            files: [],
          },
        ],
      };
      expect(detectOpenAIFormat(data, { fieldType: "input" })).toBe(true);
    });

    it("should reject invalid input format", () => {
      const data = { invalid: "format" };
      expect(detectOpenAIFormat(data, { fieldType: "input" })).toBe(false);
    });

    it("should reject empty arrays", () => {
      expect(detectOpenAIFormat([], { fieldType: "input" })).toBe(false);
    });
  });

  describe("Output formats", () => {
    it("should detect standard OpenAI output format", () => {
      const data = {
        choices: [
          {
            message: { role: "assistant", content: "Hello!" },
            index: 0,
          },
        ],
      };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(true);
    });

    it("should detect custom output format", () => {
      const data = {
        text: "This is the answer",
        usage: {
          prompt_tokens: 180,
          completion_tokens: 219,
          total_tokens: 399,
        },
        finish_reason: "stop",
      };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(true);
    });

    it("should detect custom output format without optional fields", () => {
      const data = {
        text: "Simple response",
      };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(true);
    });

    it("should reject invalid output format", () => {
      const data = { invalid: "format" };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(false);
    });

    it("should reject output format with non-string text", () => {
      const data = {
        text: 123,
        usage: {},
      };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(false);
    });

    it("should detect conversation output format with messages array containing assistant message", () => {
      const data = {
        chat_id: "some-uuid",
        model: "llama3",
        messages: [
          { role: "user", content: "Hello" },
          { role: "assistant", content: "Hi there!" },
        ],
      };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(true);
    });

    it("should reject conversation output format with no assistant message", () => {
      const data = {
        messages: [{ role: "user", content: "Hello" }],
      };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(false);
    });

    it("should reject conversation output format with empty messages array", () => {
      const data = { messages: [] };
      expect(detectOpenAIFormat(data, { fieldType: "output" })).toBe(false);
    });
  });

  describe("Edge cases", () => {
    it("should reject null data", () => {
      expect(detectOpenAIFormat(null, { fieldType: "input" })).toBe(false);
    });

    it("should reject undefined data", () => {
      expect(detectOpenAIFormat(undefined, { fieldType: "input" })).toBe(false);
    });

    it("should reject when fieldType is not specified", () => {
      const data = [{ role: "user", content: "Hello" }];
      expect(detectOpenAIFormat(data, {})).toBe(false);
    });

    it("should reject when prettifyConfig is not provided", () => {
      const data = [{ role: "user", content: "Hello" }];
      expect(detectOpenAIFormat(data)).toBe(false);
    });
  });
});
