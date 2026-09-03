import { describe, expect, it } from "vitest";
import { mapAndCombineMessages } from "../../mapAndCombineMessages";
import {
  combineOpenInferenceMessages,
  mapOpenInferenceMessages,
} from "./mapper";

describe("OpenInference message mapping", () => {
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

    const result = mapAndCombineMessages(input, output, "openinference", {
      prompt_tokens: 12,
      completion_tokens: 4,
      total_tokens: 16,
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

    const result = mapAndCombineMessages(
      legacyInput,
      legacyOutput,
      "openinference",
    );

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
      "openinference",
    );

    expect(result.messages).toHaveLength(2);
    expect(result.messages[1].id).toBe("openinference-output-fallback-0");
    expect(result.messages[1].blocks[0].props).toMatchObject({
      children: "Raw answer",
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
});
