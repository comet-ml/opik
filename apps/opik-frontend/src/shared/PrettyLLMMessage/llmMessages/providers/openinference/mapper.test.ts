import { describe, expect, it } from "vitest";
import { mapAndCombineMessages } from "../../mapAndCombineMessages";
import {
  combineOpenInferenceMessages,
  mapOpenInferenceMessages,
} from "./mapper";

describe("OpenInference message mapping", () => {
  it.each(["canonical", "legacy", "both"])(
    "preserves repeated turns in %s messages",
    (representation) => {
      const messages = [
        { role: "user", content: "Continue" },
        { role: "assistant", content: "First answer" },
        { role: "user", content: "Continue" },
      ];
      const legacy = Object.fromEntries(
        messages.flatMap((message, index) => [
          [`llm.input_messages.${index}.message.role`, message.role],
          [`llm.input_messages.${index}.message.content`, message.content],
        ]),
      );
      const input = {
        ...(representation !== "legacy" && { messages }),
        ...(representation !== "canonical" && legacy),
      };

      const result = mapAndCombineMessages(input, undefined, {
        formatHint: "openinference",
      });

      expect(result.messages).toMatchObject(
        messages.map(({ role, content }) => ({
          role,
          blocks: [{ blockType: "text", props: { children: content } }],
        })),
      );
    },
  );

  it("preserves repeated completion choices", () => {
    const result = mapAndCombineMessages(
      undefined,
      {
        choices: [{ text: "Same answer" }, { text: "Same answer" }],
      },
      { formatHint: "openinference" },
    );

    expect(result.messages).toMatchObject([
      { blocks: [{ blockType: "text", props: { children: "Same answer" } }] },
      { blocks: [{ blockType: "text", props: { children: "Same answer" } }] },
    ]);
  });

  it.each([
    ["image", "png", "images"],
    ["audio", "wav", "audios"],
  ])(
    "passes backend %s placeholders to the media resolver",
    (type, extension, prop) => {
      const url = `[output-attachment-1-1768916401606.${extension}]`;
      const result = mapAndCombineMessages(
        undefined,
        {
          messages: [
            {
              role: "assistant",
              contents: [{ type, [type]: { url } }],
            },
          ],
        },
        { formatHint: "openinference", formatHintIsAuthoritative: true },
      );

      expect(result.messages).toMatchObject([
        {
          role: "assistant",
          blocks: [{ blockType: type, props: { [prop]: [{ url }] } }],
        },
      ]);
    },
  );

  it("does not render a null function call", () => {
    const result = mapAndCombineMessages(
      undefined,
      {
        messages: [
          { role: "assistant", content: "Answer", function_call: null },
        ],
      },
      { formatHint: "openinference" },
    );

    expect(result.messages[0].blocks).toMatchObject([
      { blockType: "text", props: { children: "Answer" } },
    ]);
  });

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

  it("maps canonical chat, roles, tools, usage and ordered multimodal content", () => {
    const toolCall = {
      id: "call-1",
      function: { name: "weather", arguments: '{"city":"Paris"}' },
      reasoning_signature: "opaque-tool-signature",
    };
    const input = {
      messages: [
        { role: "developer", content: "Be concise" },
        { role: "human", content: "What is the weather?" },
      ],
      prompts: [{ text: "Weather for Paris" }],
      tools: [{ name: "weather", json_schema: { type: "object" } }],
    };
    const output = {
      messages: [
        {
          role: "model",
          contents: [
            {
              type: "reasoning",
              text: "I should call the tool",
              encrypted_content: "kept-in-details",
            },
            { type: "text", text: "Checking now." },
            { type: "image", image: { url: "[image_0]" } },
            {
              type: "audio",
              audio: {
                url: "https://example.test/answer.wav",
                mime_type: "audio/wav",
                transcript: "Checking now",
              },
            },
            {
              type: "tool_use",
              tool_call: {
                function: toolCall.function,
                reasoning_signature: toolCall.reasoning_signature,
              },
            },
          ],
          tool_calls: [toolCall],
        },
      ],
      finish_reason: "tool_calls",
    };

    const result = mapAndCombineMessages(input, output, {
      formatHint: "openinference",
      spanUsage: {
        prompt_tokens: 12,
        completion_tokens: 4,
        total_tokens: 16,
      },
    });

    expect(result.messages.map((message) => message.role)).toEqual([
      "system",
      "user",
      "user",
      "system",
      "assistant",
    ]);
    const outputMessage = result.messages.at(-1)!;
    expect(outputMessage.blocks.map((block) => block.blockType)).toEqual([
      "text",
      "text",
      "image",
      "audio",
      "text",
      "code",
    ]);
    expect(
      outputMessage.blocks.filter((block) => block.blockType === "code"),
    ).toHaveLength(1);
    expect(outputMessage.finishReason).toBe("tool_calls");
    expect(result.usage).toEqual({
      prompt_tokens: 12,
      completion_tokens: 4,
      total_tokens: 16,
    });
  });

  it("maps completion prompts, choices and a legacy function call", () => {
    const result = combineOpenInferenceMessages(
      {
        raw: {
          prompts: [{ text: "Complete me" }],
          tools: [
            {
              json_schema: {
                type: "function",
                function: { name: "calculator", description: "Calculate" },
              },
            },
          ],
        },
        mapped: { messages: [] },
      },
      {
        raw: {
          choices: [{ text: "Completed" }],
          function_call: { name: "calculator", arguments: { x: 2 } },
          finish_reason: "stop",
        },
        mapped: { messages: [] },
      },
    );

    expect(result.messages.map((message) => message.label)).toEqual([
      "Prompt",
      "Available tools",
      "Completion",
    ]);
    expect(result.messages[1].blocks[0].props).toMatchObject({
      label: "calculator",
    });
    expect(result.messages.at(-1)?.finishReason).toBe("stop");
  });

  it("restores the exact historical storage shape and removes raw output duplication", () => {
    const legacyInput = {
      "openinference.span.kind": "LLM",
      value: { prompt: "raw request" },
      mime_type: "application/json",
      "llm.input_messages.3.message.role": "human",
      "llm.input_messages.3.message.content": "Hello",
      "llm.output_messages.8.message.role": "model",
      "llm.output_messages.8.message.content": "Semantic answer",
      "llm.output_messages.8.message.tool_calls.4.tool_call.id": "call-4",
      "llm.output_messages.8.message.tool_calls.4.tool_call.function.name":
        "search",
      "llm.output_messages.8.message.tool_calls.4.tool_call.function.arguments":
        {
          query: "Opik",
        },
      "llm.finish_reason": "stop",
    };
    const legacyOutput = {
      value: {
        choices: [
          { message: { role: "assistant", content: "Semantic answer" } },
        ],
      },
      mime_type: "application/json",
    };

    const result = mapAndCombineMessages(legacyInput, legacyOutput, {
      formatHint: "openinference",
    });

    expect(result.messages).toHaveLength(2);
    expect(result.messages[0]).toMatchObject({
      id: "openinference-input-0",
      role: "user",
    });
    expect(result.messages[1]).toMatchObject({
      id: "openinference-output-0",
      role: "assistant",
      finishReason: "stop",
    });
    expect(
      result.messages.some((message) => message.id.includes("fallback")),
    ).toBe(false);
    const code = result.messages[1].blocks.find(
      (block) => block.blockType === "code",
    );
    expect(code?.props).toMatchObject({
      label: "search",
      code: '{\n  "query": "Opik"\n}',
    });
  });

  it("uses old value/mime_type as raw fallback when no semantic output exists", () => {
    const result = mapAndCombineMessages(
      {
        "openinference.span.kind": "LLM",
        "llm.input_messages.0.message.role": "user",
        "llm.input_messages.0.message.content": "Hello",
      },
      { value: "Raw answer", mime_type: "text/plain" },
      { formatHint: "openinference" },
    );

    expect(result.messages).toHaveLength(2);
    expect(result.messages[1].id).toBe("openinference-output-fallback-0");
    expect(result.messages[1].blocks[0].props).toMatchObject({
      children: "Raw answer",
    });
  });

  it("renders unwrapped raw objects when an exact span marker supplied the hint", () => {
    const result = mapAndCombineMessages(
      { request_id: "request-7" },
      { response_id: "response-7" },
      {
        formatHint: "openinference",
        formatHintIsAuthoritative: true,
      },
    );

    expect(result.messages).toHaveLength(2);
    expect(result.messages[0]).toMatchObject({
      id: "openinference-input-fallback-0",
      role: "user",
      blocks: [{ blockType: "code", props: { label: "Input" } }],
    });
    expect(result.messages[1]).toMatchObject({
      id: "openinference-output-fallback-0",
      role: "assistant",
      blocks: [{ blockType: "code", props: { label: "Output" } }],
    });
  });

  it("handles malformed and partial messages without throwing", () => {
    expect(() =>
      mapOpenInferenceMessages(
        {
          messages: [
            { role: 42, contents: [{ type: "image", image: {} }] },
            { role: "unknown-role", contents: [null, { type: "text" }] },
          ],
        },
        { fieldType: "output", formatHint: "openinference" },
      ),
    ).not.toThrow();

    const result = mapOpenInferenceMessages(
      { messages: [{ role: "unknown-role", content: "safe" }] },
      { fieldType: "output", formatHint: "openinference" },
    );
    expect(result.messages[0].role).toBe("assistant");
  });

  it("uses raw fallback when partial semantic messages have no renderable blocks", () => {
    const result = combineOpenInferenceMessages(
      {
        raw: { messages: [{ role: "user" }], value: "Raw question" },
        mapped: { messages: [] },
      },
      {
        raw: { messages: [{ role: "assistant" }], value: "Raw answer" },
        mapped: { messages: [] },
      },
    );

    expect(result.messages).toHaveLength(2);
    expect(result.messages[0]).toMatchObject({
      id: "openinference-input-fallback-0",
      role: "user",
    });
    expect(result.messages[0].blocks[0].props).toMatchObject({
      children: "Raw question",
    });
    expect(result.messages[1]).toMatchObject({
      id: "openinference-output-fallback-0",
      role: "assistant",
    });
    expect(result.messages[1].blocks[0].props).toMatchObject({
      children: "Raw answer",
    });
  });

  it.each([
    { name: "distinct ids", orderedId: "ordered-call", ids: ["separate-call"] },
    {
      name: "one match per ordered call",
      orderedId: undefined,
      ids: ["call-1", "call-2"],
    },
  ])("preserves distinct tool calls: $name", ({ orderedId, ids }) => {
    const fn = { name: "weather", arguments: '{"city":"Paris"}' };
    const result = mapOpenInferenceMessages(
      {
        messages: [
          {
            role: "assistant",
            contents: [
              {
                type: "tool_use",
                tool_call: {
                  id: orderedId,
                  function: fn,
                },
              },
            ],
            tool_calls: ids.map((id) => ({ id, function: fn })),
          },
        ],
      },
      { fieldType: "output", formatHint: "openinference" },
    );

    expect(
      result.messages[0].blocks.filter((block) => block.blockType === "code"),
    ).toHaveLength(2);
  });

  it("uses raw fallback instead of rendering an id-only tool call", () => {
    const result = mapOpenInferenceMessages(
      {
        messages: [
          {
            role: "assistant",
            tool_calls: [{ id: "only-id" }],
          },
        ],
        value: "Raw answer",
      },
      { fieldType: "output", formatHint: "openinference" },
    );

    expect(result.messages).toHaveLength(1);
    expect(result.messages[0]).toMatchObject({
      id: "openinference-output-fallback-0",
      blocks: [{ blockType: "text", props: { children: "Raw answer" } }],
    });
  });

  it("does not synthesize a user message from canonical configuration", () => {
    const result = mapOpenInferenceMessages(
      {
        invocation_parameters: { temperature: 0.2 },
        prompt_template: {
          template: "Answer {{question}}",
          variables: { question: "Why?" },
        },
      },
      {
        fieldType: "input",
        formatHint: "openinference",
        formatHintIsAuthoritative: true,
      },
    );

    expect(result.messages).toEqual([]);
  });

  it("rejects unsafe media URLs while preserving safe media and transcripts", () => {
    const result = mapOpenInferenceMessages(
      {
        messages: [
          {
            role: "assistant",
            contents: [
              { type: "image", image: { url: "javascript:alert(1)" } },
              {
                type: "image",
                image: { url: "https://example.test/image.png" },
              },
              { type: "image", image: { url: "data:text/html,<script />" } },
              { type: "image", image: { url: "data:image/png;base64,AA==" } },
              {
                type: "audio",
                audio: {
                  url: "custom:unsafe",
                  transcript: "Transcript remains visible",
                },
              },
              {
                type: "audio",
                audio: { url: "data:audio/wav;base64,AA==" },
              },
            ],
          },
        ],
      },
      { fieldType: "output", formatHint: "openinference" },
    );

    expect(result.messages[0].blocks.map((block) => block.blockType)).toEqual([
      "image",
      "image",
      "text",
      "audio",
    ]);
    expect(result.messages[0].blocks).toMatchObject([
      {
        blockType: "image",
        props: { images: [{ url: "https://example.test/image.png" }] },
      },
      {
        blockType: "image",
        props: { images: [{ url: "data:image/png;base64,AA==" }] },
      },
      { blockType: "text" },
      {
        blockType: "audio",
        props: { audios: [{ url: "data:audio/wav;base64,AA==" }] },
      },
    ]);
  });

  it("preserves a separately detected OpenAI output", () => {
    const result = mapAndCombineMessages(
      {
        "openinference.span.kind": "LLM",
        "llm.input_messages.0.message.role": "user",
        "llm.input_messages.0.message.content": "Question",
      },
      {
        id: "chatcmpl-1",
        model: "provider-model",
        choices: [{ message: { role: "assistant", content: "OpenAI answer" } }],
        usage: { total_tokens: 7 },
      },
      {
        formatHint: "openinference",
        formatHintIsAuthoritative: true,
      },
    );

    expect(result.messages).toHaveLength(2);
    expect(result.messages[1]).toMatchObject({ role: "assistant" });
    expect(result.messages[1].blocks[0].props).toMatchObject({
      children: "OpenAI answer",
    });
  });
});
