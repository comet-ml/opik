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
      messages: [["role", "User message"]],
    };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "User message", prettified: true });
  });

  it("handles LangGraph output message format with multiple human messages", () => {
    const message = {
      messages: [
        { type: "human", content: "Message 1" },
        { type: "ai", content: "AI response" },
        { type: "human", content: "Message 2" },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({
      message: "Message 1\n\n  ----------------- \n\nMessage 2",
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
});
