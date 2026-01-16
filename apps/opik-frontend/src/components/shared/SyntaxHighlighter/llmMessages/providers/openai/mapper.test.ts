import { describe, it, expect } from "vitest";
import { mapOpenAIMessages } from "./mapper";

describe("mapOpenAIMessages", () => {
  describe("Input formats", () => {
    it("should map standard OpenAI input format", () => {
      const data = {
        messages: [
          { role: "user", content: "Hello" },
          { role: "assistant", content: "Hi there!" },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result).toHaveLength(2);
      expect(result[0].role).toBe("user");
      expect(result[0].blocks).toHaveLength(1);
      expect(result[0].blocks[0].blockType).toBe("text");
      expect(result[1].role).toBe("assistant");
    });

    it("should map direct array format", () => {
      const data = [
        { role: "user", content: "Hello" },
        { role: "assistant", content: "Hi there!" },
      ];
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result).toHaveLength(2);
      expect(result[0].role).toBe("user");
      expect(result[0].blocks[0].blockType).toBe("text");
    });

    it("should map custom input format with text field", () => {
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
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result).toHaveLength(2);
      expect(result[0].role).toBe("system");
      expect(result[0].blocks[0].blockType).toBe("text");
      expect(result[0].blocks[0].props.children).toBe(
        "You are a helpful assistant",
      );
      expect(result[1].role).toBe("user");
      expect(result[1].blocks[0].props.children).toBe("What is 2+2?");
    });

    it("should handle multimodal content in custom input format", () => {
      const data = {
        input: [
          {
            role: "user",
            text: [
              { type: "text", text: "What's in this image?" },
              {
                type: "image_url",
                image_url: { url: "https://example.com/image.jpg" },
              },
            ],
            files: [],
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "input" });

      expect(result).toHaveLength(1);
      expect(result[0].blocks).toHaveLength(2);
      expect(result[0].blocks[0].blockType).toBe("text");
      expect(result[0].blocks[1].blockType).toBe("image");
    });
  });

  describe("Output formats", () => {
    it("should map standard OpenAI output format", () => {
      const data = {
        choices: [
          {
            message: { role: "assistant", content: "Hello!" },
            index: 0,
          },
        ],
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result).toHaveLength(1);
      expect(result[0].role).toBe("assistant");
      expect(result[0].blocks[0].blockType).toBe("text");
      expect(result[0].blocks[0].props.children).toBe("Hello!");
    });

    it("should map custom output format", () => {
      const data = {
        text: "This is the answer",
        usage: {
          prompt_tokens: 180,
          completion_tokens: 219,
          total_tokens: 399,
        },
        finish_reason: "stop",
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result).toHaveLength(1);
      expect(result[0].role).toBe("assistant");
      expect(result[0].blocks).toHaveLength(1);
      expect(result[0].blocks[0].blockType).toBe("text");
      expect(result[0].blocks[0].props.children).toBe("This is the answer");
    });

    it("should map custom output format with minimal data", () => {
      const data = {
        text: "Simple response",
      };
      const result = mapOpenAIMessages(data, { fieldType: "output" });

      expect(result).toHaveLength(1);
      expect(result[0].role).toBe("assistant");
      expect(result[0].blocks[0].props.children).toBe("Simple response");
    });
  });

  describe("Edge cases", () => {
    it("should return empty array for null data", () => {
      expect(mapOpenAIMessages(null, { fieldType: "input" })).toEqual([]);
    });

    it("should return empty array for undefined data", () => {
      expect(mapOpenAIMessages(undefined, { fieldType: "input" })).toEqual([]);
    });

    it("should return empty array when fieldType is not specified", () => {
      const data = [{ role: "user", content: "Hello" }];
      expect(mapOpenAIMessages(data, {})).toEqual([]);
    });

    it("should return empty array for invalid format", () => {
      const data = { invalid: "format" };
      expect(mapOpenAIMessages(data, { fieldType: "input" })).toEqual([]);
    });
  });
});
