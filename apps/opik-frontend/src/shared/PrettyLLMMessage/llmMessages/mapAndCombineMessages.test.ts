import { describe, expect, it } from "vitest";
import { mapAndCombineMessages } from "./mapAndCombineMessages";

describe("mapAndCombineMessages", () => {
  it("keeps renderable output visible when an optional tool call is malformed", () => {
    const result = mapAndCombineMessages(
      {},
      {
        messages: [
          {
            role: "assistant",
            content: "The response is still renderable",
            tool_calls: [null],
          },
        ],
      },
    );

    expect(result.messages).toHaveLength(1);
    expect(result.messages[0].blocks.map((block) => block.blockType)).toEqual([
      "text",
      "code",
    ]);
  });

  it("does not duplicate a complete OpenWebUI output conversation", () => {
    const input = {
      messages: [{ role: "user", content: "What is Opik?" }],
    };
    const output = {
      messages: [
        { role: "user", content: "What is Opik?" },
        { role: "assistant", content: "An observability platform." },
      ],
    };

    const result = mapAndCombineMessages(input, output);

    expect(result.messages).toHaveLength(2);
    expect(result.messages.map((message) => message.role)).toEqual([
      "user",
      "assistant",
    ]);
  });

  it("keeps standard OpenAI input and output messages separate", () => {
    const input = {
      messages: [{ role: "user", content: "What is Opik?" }],
    };
    const output = {
      choices: [
        {
          message: { role: "assistant", content: "An observability platform." },
        },
      ],
    };

    const result = mapAndCombineMessages(input, output);

    expect(result.messages).toHaveLength(2);
    expect(result.messages.map((message) => message.role)).toEqual([
      "user",
      "assistant",
    ]);
  });
});
