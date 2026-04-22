import { describe, it, expect } from "vitest";
import { validateAndParseJson } from "./ChatPromptRawView";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

describe("validateAndParseJson", () => {
  describe("empty and invalid inputs", () => {
    it("should return invalid for empty string", () => {
      const result = validateAndParseJson("");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for whitespace-only string", () => {
      const result = validateAndParseJson("   ");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for invalid JSON syntax", () => {
      const result = validateAndParseJson("{ invalid json }");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for malformed JSON with missing closing brace", () => {
      const result = validateAndParseJson('{"role": "user"');
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for JSON with trailing comma", () => {
      const result = validateAndParseJson('{"role": "user",}');
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });
  });

  describe("non-array inputs", () => {
    it("should return invalid for JSON object", () => {
      const result = validateAndParseJson(
        '{"role": "user", "content": "hello"}',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for JSON string", () => {
      const result = validateAndParseJson('"hello"');
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for JSON number", () => {
      const result = validateAndParseJson("123");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for JSON boolean", () => {
      const result = validateAndParseJson("true");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for JSON null", () => {
      const result = validateAndParseJson("null");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });
  });

  describe("empty array", () => {
    it("should return invalid for empty array", () => {
      const result = validateAndParseJson("[]");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for array with only whitespace", () => {
      const result = validateAndParseJson("[   ]");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });
  });

  describe("invalid message objects", () => {
    it("should return invalid for message missing role", () => {
      const result = validateAndParseJson('[{"content": "hello"}]');
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message with invalid role", () => {
      const result = validateAndParseJson(
        '[{"role": "invalid_role", "content": "hello"}]',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message missing content", () => {
      const result = validateAndParseJson('[{"role": "user"}]');
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message with null content", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": null}]',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message with number content", () => {
      const result = validateAndParseJson('[{"role": "user", "content": 123}]');
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message with boolean content", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": true}]',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message with object content (not string or array)", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": {"nested": "object"}}]',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message with invalid multimodal content array", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": [{"invalid": "object"}]}]',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for message with multimodal content missing type", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": [{"text": "hello"}]}]',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for null message", () => {
      const result = validateAndParseJson("[null]");
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid for string message", () => {
      const result = validateAndParseJson('["hello"]');
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });

    it("should return invalid when one message in array is invalid", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": "hello"}, {"role": "invalid"}]',
      );
      expect(result.isValid).toBe(false);
      expect(result.messages).toBeUndefined();
    });
  });

  describe("valid message arrays", () => {
    it("should return valid for single message with string content", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": "hello"}]',
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.user,
          content: "hello",
        },
      ]);
    });

    it("should return valid for single system message", () => {
      const result = validateAndParseJson(
        '[{"role": "system", "content": "You are a helpful assistant"}]',
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.system,
          content: "You are a helpful assistant",
        },
      ]);
    });

    it("should return valid for single assistant message", () => {
      const result = validateAndParseJson(
        '[{"role": "assistant", "content": "Hello! How can I help?"}]',
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.assistant,
          content: "Hello! How can I help?",
        },
      ]);
    });

    it("should return valid for multiple messages", () => {
      const result = validateAndParseJson(
        `[
          {"role": "system", "content": "You are helpful"},
          {"role": "user", "content": "Hello"},
          {"role": "assistant", "content": "Hi there!"}
        ]`,
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.system,
          content: "You are helpful",
        },
        {
          id: "msg-1",
          role: LLM_MESSAGE_ROLE.user,
          content: "Hello",
        },
        {
          id: "msg-2",
          role: LLM_MESSAGE_ROLE.assistant,
          content: "Hi there!",
        },
      ]);
    });

    it("should return valid for message with empty string content", () => {
      const result = validateAndParseJson('[{"role": "user", "content": ""}]');
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.user,
          content: "",
        },
      ]);
    });

    it("should return valid for message with multimodal content array", () => {
      const result = validateAndParseJson(
        `[
          {
            "role": "user",
            "content": [
              {"type": "text", "text": "What's in this image?"},
              {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}}
            ]
          }
        ]`,
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.user,
          content: [
            { type: "text", text: "What's in this image?" },
            {
              type: "image_url",
              image_url: { url: "https://example.com/image.jpg" },
            },
          ],
        },
      ]);
    });

    it("should return valid for message with special characters in content", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": "Hello! @#$%^&*()_+-=[]{}|;:,.<>?"}]',
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.user,
          content: "Hello! @#$%^&*()_+-=[]{}|;:,.<>?",
        },
      ]);
    });

    it("should return valid for message with unicode characters", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": "Hello 世界 🌍"}]',
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.user,
          content: "Hello 世界 🌍",
        },
      ]);
    });

    it("should return valid for message with newlines in content", () => {
      const result = validateAndParseJson(
        '[{"role": "user", "content": "Line 1\\nLine 2\\nLine 3"}]',
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.user,
          content: "Line 1\nLine 2\nLine 3",
        },
      ]);
    });

    it("should assign sequential IDs to messages", () => {
      const result = validateAndParseJson(
        `[
          {"role": "user", "content": "First"},
          {"role": "user", "content": "Second"},
          {"role": "user", "content": "Third"}
        ]`,
      );
      expect(result.isValid).toBe(true);
      expect(result.messages?.[0].id).toBe("msg-0");
      expect(result.messages?.[1].id).toBe("msg-1");
      expect(result.messages?.[2].id).toBe("msg-2");
    });

    it("should handle messages with extra fields (only role and content are used)", () => {
      const result = validateAndParseJson(
        `[
          {"role": "user", "content": "Hello", "extra": "field", "timestamp": 123456}
        ]`,
      );
      expect(result.isValid).toBe(true);
      expect(result.messages).toEqual([
        {
          id: "msg-0",
          role: LLM_MESSAGE_ROLE.user,
          content: "Hello",
        },
      ]);
    });
  });
});
