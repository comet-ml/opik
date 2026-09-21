import { describe, it, expect } from "vitest";
import { mapPlaygroundMessages } from "./mapper";

describe("mapPlaygroundMessages", () => {
  it("should map { output: string } to a single assistant turn", () => {
    const data = { output: "Cancelling gives you a full refund." };
    const result = mapPlaygroundMessages(data, { fieldType: "output" });

    expect(result.messages).toHaveLength(1);
    expect(result.messages[0].role).toBe("assistant");
    expect(result.messages[0].blocks).toHaveLength(1);
    expect(result.messages[0].blocks[0].blockType).toBe("text");
    if (result.messages[0].blocks[0].blockType === "text") {
      expect(result.messages[0].blocks[0].props.children).toBe(
        "Cancelling gives you a full refund.",
      );
    }
  });

  it("should map an empty completion to an empty assistant turn", () => {
    const result = mapPlaygroundMessages(
      { output: "" },
      {
        fieldType: "output",
      },
    );

    expect(result.messages).toHaveLength(1);
    if (result.messages[0].blocks[0].blockType === "text") {
      expect(result.messages[0].blocks[0].props.children).toBe("");
    }
  });

  it("should return nothing for the input field", () => {
    const data = { output: "Cancelling gives you a full refund." };
    expect(
      mapPlaygroundMessages(data, { fieldType: "input" }).messages,
    ).toEqual([]);
  });

  it("should return nothing for an unrecognized shape", () => {
    const data = {
      choices: [{ message: { role: "assistant", content: "x" } }],
    };
    expect(
      mapPlaygroundMessages(data, { fieldType: "output" }).messages,
    ).toEqual([]);
  });
});
