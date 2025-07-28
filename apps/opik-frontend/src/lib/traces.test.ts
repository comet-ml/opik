import { describe, expect, it } from "vitest";
import { prettifyMessage } from "./traces";

/**
 * `prettifyMessage` takes a message object, string, or undefined, and transforms it
 * into a structured format with a "prettified" flag indicating whether it has been transformed.
 */
describe("prettifyMessage", () => {
  it("returns the content of the last message if config type is 'input'", () => {
    const message = { messages: [{ content: "Hello" }] };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "Hello", prettified: true });
  });

  it("extracts the last text content when the last message contains an array", () => {
    const message = {
      messages: [
        {
          content: [
            { type: "image", url: "image.png" },
            { type: "text", text: "Hello there" },
          ],
        },
      ],
    };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "Hello there", prettified: true });
  });

  it("returns the content of the last choice if config type is 'output'", () => {
    const message = {
      choices: [
        { message: { content: "How are you?" } },
        { message: { content: "I'm fine" } },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({ message: "I'm fine", prettified: true });
  });

  it("unwraps a single key object to get its string value", () => {
    const message = { question: "What is your name?" };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "What is your name?", prettified: true });
  });

  it("extracts the correct string using a predefined key map when multiple keys exist", () => {
    const message = { query: "Explain recursion.", extra: "unused" };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "Explain recursion.", prettified: true });
  });

  it("returns the original message if it is already a string and marks it as not prettified", () => {
    const message = "Simple string message";
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({
      message: "Simple string message",
      prettified: false,
    });
  });

  it("returns the original message content when it cannot be prettified", () => {
    const message = { otherKey: "Not relevant" };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({ message: "Not relevant", prettified: true });
  });

  it("gracefully handles an undefined message", () => {
    const result = prettifyMessage(undefined, { type: "input" });
    expect(result).toEqual({ message: undefined, prettified: false });
  });

  it("handles ADK input message format", () => {
    const message = { parts: [{ text: "Hello ADK" }] };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "Hello ADK", prettified: true });
  });

  it("handles ADK spans input message format", () => {
    const message = { contents: [{ parts: [{ text: "Hello ADK" }] }] };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "Hello ADK", prettified: true });
  });

  it("handles ADK output message format", () => {
    const message = {
      content: {
        parts: [{ text: "ADK Response" }],
      },
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({ message: "ADK Response", prettified: true });
  });

  it("handles LangGraph input message format", () => {
    const message = {
      messages: [{ type: "human", content: "User message" }],
    };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "User message", prettified: true });
  });

  it("handles LangGraph output message format with multiple AI messages", () => {
    const message = {
      messages: [
        { type: "human", content: "User question" },
        { type: "ai", content: "AI response 1" },
        { type: "human", content: "Follow-up question" },
        { type: "ai", content: "AI response 2" },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({
      message: "AI response 2",
      prettified: true,
    });
  });

  it("uses default input type when config is not provided", () => {
    const message = { question: "Default input type" };
    const result = prettifyMessage(message);
    expect(result).toEqual({ message: "Default input type", prettified: true });
  });

  it("handles empty array content in messages", () => {
    const message = { messages: [{ content: [] }] };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message, prettified: false });
  });

  it("handles malformed choice objects in output", () => {
    const message = {
      choices: [{ incomplete: "data" }],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({ message, prettified: false });
  });

  it("processes nested objects with predefined keys", () => {
    const message = {
      data: {
        response: "Nested response",
      },
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({ message: "Nested response", prettified: true });
  });

  it("handles OpenAI Agents input message with multiple user messages", () => {
    const message = {
      input: [
        { role: "system", content: "System message" },
        { role: "user", content: "User message 1" },
        { role: "assistant", content: "Assistant message" },
        { role: "user", content: "User message 2" },
      ],
    };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({
      message: "User message 1\n\n  ----------------- \n\nUser message 2",
      prettified: true,
    });
  });

  it("handles OpenAI Agents output message with multiple assistant outputs", () => {
    const message = {
      output: [
        {
          role: "assistant",
          type: "message",
          content: [{ type: "output_text", text: "Assistant response 1" }],
        },
        { role: "user", content: "User message" },
        {
          role: "assistant",
          type: "message",
          content: [{ type: "output_text", text: "Assistant response 2" }],
        },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({
      message:
        "Assistant response 1\n\n  ----------------- \n\nAssistant response 2",
      prettified: true,
    });
  });

  it("handles Demo project blocks structure with text content", () => {
    const message = {
      role: "assistant",
      blocks: [
        {
          block_type: "text",
          text: "Opik is a tool that has been specifically designed to support high volumes of traces, making it suitable for monitoring production applications, particularly LLM applications.",
        },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({
      message:
        "Opik is a tool that has been specifically designed to support high volumes of traces, making it suitable for monitoring production applications, particularly LLM applications.",
      prettified: true,
    });
  });

  it("handles Demo project blocks structure with multiple text blocks", () => {
    const message = {
      role: "assistant",
      blocks: [
        {
          block_type: "text",
          text: "First paragraph content.",
        },
        {
          block_type: "text",
          text: "Second paragraph content.",
        },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({
      message: "First paragraph content.\n\nSecond paragraph content.",
      prettified: true,
    });
  });

  it("handles Demo project blocks structure with mixed block types, extracting only text blocks", () => {
    const message = {
      role: "assistant",
      blocks: [
        {
          block_type: "image",
          url: "https://example.com/image.jpg",
        },
        {
          block_type: "text",
          text: "This is the text content.",
        },
        {
          block_type: "code",
          language: "python",
          code: "print('hello')",
        },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({
      message: "This is the text content.",
      prettified: true,
    });
  });

  it("handles Demo project nested blocks structure under output property", () => {
    const message = {
      output: {
        role: "assistant",
        blocks: [
          {
            block_type: "text",
            text: "Opik's morning routine before diving into LLM evaluations involves logging, viewing, and evaluating LLM traces using the Opik platform and LLM as a Judge evaluators. This allows for the identification and fixing of issues in the LLM application.",
          },
        ],
      },
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({
      message:
        "Opik's morning routine before diving into LLM evaluations involves logging, viewing, and evaluating LLM traces using the Opik platform and LLM as a Judge evaluators. This allows for the identification and fixing of issues in the LLM application.",
      prettified: true,
    });
  });
});
