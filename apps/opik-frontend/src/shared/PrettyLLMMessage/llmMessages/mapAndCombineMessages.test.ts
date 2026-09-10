import { describe, it, expect } from "vitest";
import { mapAndCombineMessages } from "./mapAndCombineMessages";
import { detectLLMMessages, canShowLLMMessages } from "./detectLLMMessages";
import { resolveOpenInferenceHint } from "@/lib/openinference";

const config = {
  formatHint: "openinference" as const,
  formatHintIsAuthoritative: true,
};
describe("message detection and combining", () => {
  it.each([{}, { completion_tokens: 5 }])(
    "retains provider usage when span usage is incomplete: %j",
    (spanUsage) => {
      const result = mapAndCombineMessages(
        { messages: [{ role: "user", content: "Question" }] },
        {
          choices: [{ message: { role: "assistant", content: "Answer" } }],
          usage: { prompt_tokens: 3, completion_tokens: 4, total_tokens: 7 },
        },
        { spanUsage },
      );

      expect(result.usage).toEqual({
        prompt_tokens: 3,
        completion_tokens: 4,
        total_tokens: 7,
        ...spanUsage,
      });
    },
  );

  it.each([
    [{ supported: true }, { supported: false, empty: true }, true],
    [{ supported: true }, { supported: false }, false],
    [{ supported: false }, { supported: false }, false],
    [{ supported: false, empty: true }, { supported: true }, true],
  ] as const)(
    "gates ordinary message pairs: %j / %j",
    (input, output, expected) => {
      expect(canShowLLMMessages(input, output)).toBe(expected);
    },
  );
  it.each(["", "   ", { value: "" }, { value: null }, null, []])(
    "rejects blank LLM fallback %j",
    (input) => {
      expect(
        detectLLMMessages(input, { ...config, fieldType: "input" }).supported,
      ).toBe(false);
    },
  );
  it.each([0, false])("keeps scalar %j", (input) => {
    expect(
      mapAndCombineMessages(input, undefined, config).messages,
    ).toHaveLength(1);
  });
  it("keeps LangChain message roles under an OpenInference hint", () => {
    const input = {
      messages: [
        { type: "human", content: "Question" },
        { type: "ai", content: "Answer" },
      ],
    };
    expect(
      mapAndCombineMessages(input, undefined, config).messages.map(
        (m) => m.role,
      ),
    ).toEqual(["human", "ai"]);
  });
  it("does not promote an unrecognized sibling to a raw message", () => {
    const result = mapAndCombineMessages(
      { prompt_id: "p1" },
      { "llm.output_messages.0.message.content": "Answer" },
      { formatHint: "openinference" },
    );
    expect(result.messages).toHaveLength(1);
    expect(result.messages[0].blocks[0].props).toMatchObject({
      children: "Answer",
    });
  });
  it.each(["answer", "response", "reply", "final_output"])(
    "uses current %s before historical output in Messages",
    (key) => {
      for (const settings of [{}, config]) {
        const result = mapAndCombineMessages(
          {
            "llm.input_messages.0.message.content": "Question",
            "llm.output_messages.0.message.content": "Stale answer",
          },
          { [key]: "Current answer" },
          settings,
        );
        expect(
          result.messages.map((message) => message.blocks[0].props),
        ).toMatchObject([
          { children: "Question" },
          { children: "Current answer" },
        ]);
        expect(result.messages).toHaveLength(2);
      }
    },
  );
  it("retains unmarked legacy output recovery", () => {
    const result = mapAndCombineMessages(
      {
        "llm.input_messages.0.message.content": "Question",
        "llm.output_messages.0.message.content": "Answer",
      },
      undefined,
    );
    expect(result.messages.map((m) => m.blocks[0].props)).toMatchObject([
      { children: "Question" },
      { children: "Answer" },
    ]);
  });
  it("preserves the raw envelope of an unmarked historical span", () => {
    const result = mapAndCombineMessages(
      { "llm.input_messages.0.message.content": "Question" },
      { value: "Legacy raw answer", mime_type: "text/plain" },
    );
    expect(
      result.messages.map((message) => message.blocks[0].props),
    ).toMatchObject([
      { children: "Question" },
      { children: "Legacy raw answer" },
    ]);
  });
  it("does not use legacy configuration to invent messages on a non-LLM span", () => {
    const result = mapAndCombineMessages(
      { query: "weather", "llm.invocation_parameters": { temperature: 0.5 } },
      { value: "tool result", mime_type: "text/plain" },
      { formatHint: "openinference", formatHintIsAuthoritative: false },
    );
    expect(result.messages).toEqual([]);
  });
  it.each(["CHAIN", "AGENT", "TOOL", "RETRIEVER"])(
    "does not invent chat for raw %s spans",
    (kind) => {
      const hint = resolveOpenInferenceHint(
        { "openinference.span.kind": kind },
        { query: "weather" },
      );
      const settings = {
        ...config,
        formatHintIsAuthoritative: hint.authoritative,
      };
      expect(hint.detected).toBe(true);
      expect(
        mapAndCombineMessages({ query: "weather" }, undefined, settings)
          .messages,
      ).toEqual([]);
      expect(
        mapAndCombineMessages(
          { messages: [{ role: "user", content: "Question" }] },
          undefined,
          settings,
        ).messages,
      ).toHaveLength(1);
    },
  );
  it.each([undefined, "unknown", "image"])(
    "renders media when type is %j",
    (type) => {
      const output = {
        messages: [
          {
            role: "assistant",
            contents: [
              { type, image: { url: "https://example.test/image.png" } },
            ],
          },
        ],
      };
      const result = mapAndCombineMessages(undefined, output, config);
      expect(result.messages[0].blocks[0]).toMatchObject({
        blockType: "image",
      });
    },
  );
  it("shows only raw remainder alongside structured prompts", () => {
    const result = mapAndCombineMessages(
      { query: "weather", prompts: [{ text: "weather" }] },
      undefined,
      config,
    );
    expect(result.messages).toHaveLength(2);
    expect(result.messages[0].blocks[0].props).toMatchObject({
      code: JSON.stringify({ query: "weather" }, null, 2),
    });
  });
});
