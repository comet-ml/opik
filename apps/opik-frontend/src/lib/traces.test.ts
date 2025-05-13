import { describe, expect, it } from "vitest";
import { prettifyMessage } from "./traces";

describe("prettifyMessage", () => {
  it("should return the content of the last message if config type is 'input'", () => {
    const message = {
      messages: [{ content: "Hello" }],
    };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "Hello", prettified: true });
  });

  it("should find the last text content if the last message content is an array", () => {
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

  it("should return the content of the last choice if config type is 'output'", () => {
    const message = {
      choices: [
        { message: { content: "How are you?" } },
        { message: { content: "I'm fine" } },
      ],
    };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({ message: "I'm fine", prettified: true });
  });

  it("should unwrap a single key object to get its string value", () => {
    const message = { question: "What is your name?" };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "What is your name?", prettified: true });
  });

  it("should use predefined keys map to extract string when multiple keys exist in input config", () => {
    const message = { query: "Explain recursion.", extra: "unused" };
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({ message: "Explain recursion.", prettified: true });
  });

  it("should return the original message if it is already a string", () => {
    const message = "Simple string message";
    const result = prettifyMessage(message, { type: "input" });
    expect(result).toEqual({
      message: "Simple string message",
      prettified: false,
    });
  });

  it("should return the original message when it cannot be prettified", () => {
    const message = { otherKey: "Not relevant" };
    const result = prettifyMessage(message, { type: "output" });
    expect(result).toEqual({ message: message.otherKey, prettified: true });
  });

  it("should handle undefined message gracefully", () => {
    const result = prettifyMessage(undefined, { type: "input" });
    expect(result).toEqual({ message: undefined, prettified: false });
  });
});
