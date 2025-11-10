import { describe, it, expect } from "vitest";
import { formatMessagesAsString } from "../../../src/opik/evaluation/utils/formatMessages";
import type { OpikMessage } from "../../../src/opik/evaluation/models";

describe("formatMessagesAsString", () => {
  it("should format a single message correctly", () => {
    const messages: OpikMessage[] = [
      { role: "user", content: "Hello, world!" },
    ];

    const result = formatMessagesAsString(messages);

    expect(result).toBe("user: Hello, world!");
  });

  it("should format multiple messages with newlines", () => {
    const messages: OpikMessage[] = [
      { role: "system", content: "You are a helpful assistant." },
      { role: "user", content: "What is the capital of France?" },
      { role: "assistant", content: "The capital of France is Paris." },
    ];

    const result = formatMessagesAsString(messages);

    expect(result).toBe(
      "system: You are a helpful assistant.\n" +
        "user: What is the capital of France?\n" +
        "assistant: The capital of France is Paris."
    );
  });

  it("should handle empty messages array", () => {
    const messages: OpikMessage[] = [];

    const result = formatMessagesAsString(messages);

    expect(result).toBe("");
  });

  it("should handle messages with multiline content", () => {
    const messages: OpikMessage[] = [
      {
        role: "user",
        content: "This is line 1\nThis is line 2\nThis is line 3",
      },
    ];

    const result = formatMessagesAsString(messages);

    expect(result).toBe("user: This is line 1\nThis is line 2\nThis is line 3");
  });

  it("should preserve message order", () => {
    const messages: OpikMessage[] = [
      { role: "user", content: "First message" },
      { role: "assistant", content: "Second message" },
      { role: "user", content: "Third message" },
    ];

    const result = formatMessagesAsString(messages);

    expect(result).toBe(
      "user: First message\nassistant: Second message\nuser: Third message"
    );
  });

  it("should handle special characters in content", () => {
    const messages: OpikMessage[] = [
      { role: "user", content: "Question: What's 2+2?" },
      { role: "assistant", content: "Answer: 2+2 = 4 (100%)" },
    ];

    const result = formatMessagesAsString(messages);

    expect(result).toBe(
      "user: Question: What's 2+2?\nassistant: Answer: 2+2 = 4 (100%)"
    );
  });

  it("should handle empty content", () => {
    const messages: OpikMessage[] = [
      { role: "user", content: "" },
      { role: "assistant", content: "Response" },
    ];

    const result = formatMessagesAsString(messages);

    expect(result).toBe("user: \nassistant: Response");
  });
});
