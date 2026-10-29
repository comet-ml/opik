import type OpenAI from "openai";
import type { Opik } from "opik";
import { trackOpenAI } from "../src";

type ToolCallDelta =
  OpenAI.Chat.Completions.ChatCompletionChunk.Choice.Delta.ToolCall;

const chunk = (delta: {
  content?: string;
  tool_calls?: ToolCallDelta[];
}): OpenAI.Chat.Completions.ChatCompletionChunk => ({
  id: "chatcmpl-1",
  object: "chat.completion.chunk",
  created: 0,
  model: "gpt-5.5",
  choices: [{ index: 0, delta, finish_reason: null, logprobs: null }],
});

const fakeOpenAI = (
  chunks: OpenAI.Chat.Completions.ChatCompletionChunk[],
  requests: unknown[]
) => ({
  chat: {
    completions: {
      create: async function* (params: unknown) {
        requests.push(params);
        yield* chunks;
      },
    },
  },
});

const drainStream = async (
  chunks: OpenAI.Chat.Completions.ChatCompletionChunk[]
) => {
  const parent = { span: vi.fn(), update: vi.fn(), end: vi.fn() };
  const client = { flush: vi.fn(), trace: vi.fn() } as unknown as Opik;
  const requests: unknown[] = [];
  const tracked = trackOpenAI(fakeOpenAI(chunks, requests), {
    parent: parent as never,
    client,
  });

  const stream = await tracked.chat.completions.create({
    model: "gpt-5.5",
    messages: [{ role: "user", content: "hi" }],
    stream: true,
  });
  // draining the stream is what records the span
  const received: OpenAI.Chat.Completions.ChatCompletionChunk[] = [];
  for await (const item of stream) {
    received.push(item);
  }

  return { parent, received, requests };
};

describe("trackOpenAI streaming", () => {
  it("records parallel tool calls and the leading text in the span output", async () => {
    const text = `text_${crypto.randomUUID()}`;
    const firstId = `call_${crypto.randomUUID()}`;
    const firstName = `fn_${crypto.randomUUID()}`;
    const firstArgs = JSON.stringify({ value: crypto.randomUUID() });
    const secondId = `call_${crypto.randomUUID()}`;
    const secondName = `fn_${crypto.randomUUID()}`;
    const secondArgs = JSON.stringify({ value: crypto.randomUUID() });
    const cut = Math.floor(firstArgs.length / 2);

    const { parent, received, requests } = await drainStream([
      chunk({ content: text }),
      chunk({
        tool_calls: [
          {
            index: 0,
            id: firstId,
            type: "function",
            function: { name: firstName, arguments: "" },
          },
        ],
      }),
      chunk({
        tool_calls: [
          { index: 0, function: { arguments: firstArgs.slice(0, cut) } },
        ],
      }),
      chunk({
        tool_calls: [
          {
            index: 1,
            id: secondId,
            type: "function",
            function: { name: secondName, arguments: secondArgs },
          },
        ],
      }),
      chunk({
        tool_calls: [
          { index: 0, function: { arguments: firstArgs.slice(cut) } },
        ],
      }),
    ]);

    const expectedOutput = {
      message: text,
      tool_calls: [
        {
          id: firstId,
          type: "function",
          function: { name: firstName, arguments: firstArgs },
        },
        {
          id: secondId,
          type: "function",
          function: { name: secondName, arguments: secondArgs },
        },
      ],
    };
    expect(requests).toHaveLength(1);
    expect(received).toHaveLength(5);
    expect(parent.span).toHaveBeenCalledTimes(1);
    expect(parent.span.mock.calls[0][0].output).toEqual(expectedOutput);
  });

  it("records only the message for a text stream", async () => {
    const first = crypto.randomUUID();
    const second = crypto.randomUUID();

    const { parent } = await drainStream([
      chunk({ content: first }),
      chunk({ content: second }),
    ]);

    expect(parent.span).toHaveBeenCalledTimes(1);
    expect(parent.span.mock.calls[0][0].output).toEqual({
      message: `${first}${second}`,
    });
  });
});
