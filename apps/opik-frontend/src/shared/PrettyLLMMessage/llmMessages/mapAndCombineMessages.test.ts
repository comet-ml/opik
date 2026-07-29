import { describe, expect, it } from "vitest";

import { mapAndCombineMessages } from "./mapAndCombineMessages";

const AMBIGUOUS_MESSAGES = {
  messages: [
    {
      role: "assistant",
      type: "ai",
      content: "Hello",
    },
  ],
};

describe("mapAndCombineMessages", () => {
  it("uses the format hint for both input and output mapping", () => {
    const result = mapAndCombineMessages(
      AMBIGUOUS_MESSAGES,
      AMBIGUOUS_MESSAGES,
      "langchain",
    );

    expect(result.messages).toHaveLength(1);
    expect(result.messages[0].role).toBe("ai");
  });
});
