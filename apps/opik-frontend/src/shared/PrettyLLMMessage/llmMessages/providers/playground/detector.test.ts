import { describe, it, expect } from "vitest";
import { detectPlaygroundFormat } from "./detector";

describe("detectPlaygroundFormat", () => {
  it("should detect { output: string }", () => {
    const data = { output: "Cancelling gives you a full refund." };
    expect(detectPlaygroundFormat(data, { fieldType: "output" })).toBe(true);
  });

  it("should detect an empty completion", () => {
    expect(
      detectPlaygroundFormat({ output: "" }, { fieldType: "output" }),
    ).toBe(true);
  });

  it("should reject an output that is not a string", () => {
    const data = { output: { nested: true } };
    expect(detectPlaygroundFormat(data, { fieldType: "output" })).toBe(false);
  });

  it("should reject a null output", () => {
    expect(
      detectPlaygroundFormat({ output: null }, { fieldType: "output" }),
    ).toBe(false);
  });

  it("should not claim the input field", () => {
    const data = { output: "Cancelling gives you a full refund." };
    expect(detectPlaygroundFormat(data, { fieldType: "input" })).toBe(false);
  });

  it("should not claim data without an output key", () => {
    const data = {
      choices: [{ message: { role: "assistant", content: "x" } }],
    };
    expect(detectPlaygroundFormat(data, { fieldType: "output" })).toBe(false);
  });

  it("should reject null and undefined", () => {
    expect(detectPlaygroundFormat(null, { fieldType: "output" })).toBe(false);
    expect(detectPlaygroundFormat(undefined, { fieldType: "output" })).toBe(
      false,
    );
  });

  it("should reject when fieldType is missing", () => {
    expect(detectPlaygroundFormat({ output: "hi" }, {})).toBe(false);
    expect(detectPlaygroundFormat({ output: "hi" })).toBe(false);
  });
});
