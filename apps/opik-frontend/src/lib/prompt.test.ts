import { describe, expect, it } from "vitest";

import {
  extractMessageContent,
  extractOpenAIMessages,
  extractPromptData,
  formatMessagesAsText,
  isValidOpenAIMessages,
  OpenAIMessage,
} from "./prompt";

describe("prompt utilities", () => {
  describe("extractMessageContent", () => {
    it("returns string content as-is", () => {
      expect(extractMessageContent("Hello world")).toBe("Hello world");
    });

    it("concatenates text entries from array content", () => {
      const content = [
        { type: "text", text: "Hello" },
        { type: "text", text: "world" },
      ];
      expect(extractMessageContent(content)).toBe("Hello\nworld");
    });

    it("skips non-text entries", () => {
      const content = [
        { type: "image_url", image_url: "https://example.com" },
        { type: "text", text: "Visible text" },
      ];
      expect(extractMessageContent(content)).toBe("Visible text");
    });

    it("returns empty string for unsupported input", () => {
      expect(extractMessageContent({})).toBe("");
      expect(extractMessageContent(undefined)).toBe("");
    });
  });

  describe("isValidOpenAIMessages", () => {
    it("returns true for valid messages", () => {
      const messages: OpenAIMessage[] = [
        { role: "user", content: "Hello" },
        { role: "assistant", content: [{ type: "text", text: "Hi!" }] },
      ];
      expect(isValidOpenAIMessages(messages)).toBe(true);
    });

    it("returns false when role missing", () => {
      const invalidMessages = [{ content: "Hello" }];
      expect(isValidOpenAIMessages(invalidMessages as unknown[])).toBe(false);
    });

    it("returns false when content missing", () => {
      const invalidMessages = [{ role: "user" }];
      expect(isValidOpenAIMessages(invalidMessages as unknown[])).toBe(false);
    });
  });

  describe("extractOpenAIMessages", () => {
    it("returns messages when input is already an array", () => {
      const messages: OpenAIMessage[] = [{ role: "system", content: "Rules" }];
      expect(extractOpenAIMessages(messages)).toEqual(messages);
    });

    it("extracts messages from object with messages property", () => {
      const messages: OpenAIMessage[] = [{ role: "user", content: "Hi" }];
      expect(extractOpenAIMessages({ messages })).toEqual(messages);
    });

    it("unwraps the opik-optimizer { 'chat-prompt': [...] } wrapper", () => {
      const messages: OpenAIMessage[] = [
        { role: "system", content: "Answer the question." },
        { role: "user", content: "{question}" },
      ];
      expect(extractOpenAIMessages({ "chat-prompt": messages })).toEqual(
        messages,
      );
    });

    it("unwraps a single-prompt wrapper keyed by a custom ChatPrompt name", () => {
      const messages: OpenAIMessage[] = [
        { role: "system", content: "You are a triage agent." },
      ];
      expect(extractOpenAIMessages({ "triage-agent": messages })).toEqual(
        messages,
      );
    });

    it("does not unwrap the wrapper when sibling prompts are present", () => {
      const chatPrompt: OpenAIMessage[] = [
        { role: "system", content: "Answer the question." },
      ];
      const otherPrompt: OpenAIMessage[] = [
        { role: "system", content: "Summarize the answer." },
      ];
      expect(
        extractOpenAIMessages({
          "chat-prompt": chatPrompt,
          "other-prompt": otherPrompt,
        }),
      ).toBeNull();
    });

    it("returns null for invalid structures", () => {
      expect(extractOpenAIMessages({})).toBeNull();
      expect(extractOpenAIMessages("invalid")).toBeNull();
    });
  });

  describe("extractPromptData", () => {
    it("classifies the single-key 'chat-prompt' wrapper as single", () => {
      const messages: OpenAIMessage[] = [{ role: "user", content: "Hi" }];
      expect(extractPromptData({ "chat-prompt": messages })).toEqual({
        type: "single",
        data: messages,
      });
    });

    it("classifies a single-key custom-named wrapper as single", () => {
      const messages: OpenAIMessage[] = [{ role: "user", content: "Hi" }];
      expect(extractPromptData({ "triage-agent": messages })).toEqual({
        type: "single",
        data: messages,
      });
    });

    it("keeps all prompts when the wrapper has siblings", () => {
      const prompts = {
        "chat-prompt": [
          { role: "system", content: "Answer the question." },
        ] as OpenAIMessage[],
        "other-prompt": [
          { role: "system", content: "Summarize the answer." },
        ] as OpenAIMessage[],
      };
      expect(extractPromptData(prompts)).toEqual({
        type: "multi",
        data: prompts,
      });
    });
  });

  describe("formatMessagesAsText", () => {
    it("formats messages with role display names and content", () => {
      const messages: OpenAIMessage[] = [
        { role: "system", content: "You are ChatGPT." },
        { role: "user", content: [{ type: "text", text: "Hello" }] },
      ];
      expect(formatMessagesAsText(messages)).toBe(
        "System: You are ChatGPT.\n\nUser: Hello",
      );
    });
  });
});
