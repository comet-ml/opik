import { describe, expect, it } from "vitest";

import PrettyLLMMessage from "@/shared/PrettyLLMMessage";
import { LLMMessageDescriptor } from "@/shared/PrettyLLMMessage/llmMessages";
import { Span, SPAN_TYPE, Trace } from "@/types/traces";
import {
  canOpenSpanInPlayground,
  convertLLMMessagesToPlaygroundMessages,
  getPlaygroundMessagesFromInput,
} from "./spanPlaygroundMessages";

const IMAGE_URL = "https://example.com/cat.png";
const DATA_URI = "data:image/png;base64,iVBORw0KGgo=";

const createSpan = (overrides: Partial<Span> = {}): Span =>
  ({
    id: "span-1",
    name: "chat_completion_create",
    type: SPAN_TYPE.llm,
    trace_id: "trace-1",
    parent_span_id: "",
    project_id: "project-1",
    input: {
      messages: [{ role: "user", content: "Hi" }],
    },
    output: {},
    ...overrides,
  }) as Span;

describe("getPlaygroundMessagesFromInput", () => {
  describe("OpenAI format", () => {
    it("keeps string content and roles", () => {
      const input = {
        messages: [
          { role: "system", content: "You are helpful." },
          { role: "user", content: "What is 2 + 2?" },
          { role: "assistant", content: "4" },
          { role: "user", content: "And 3 + 3?" },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "system", content: "You are helpful." },
        { role: "user", content: "What is 2 + 2?" },
        { role: "assistant", content: "4" },
        { role: "user", content: "And 3 + 3?" },
      ]);
    });

    it("reads a bare message array", () => {
      const input = [
        { role: "system", content: "Be brief." },
        { role: "user", content: "Hello" },
      ];

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "system", content: "Be brief." },
        { role: "user", content: "Hello" },
      ]);
    });

    it("turns multi-part content into one text part followed by images", () => {
      const input = {
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "What is in this image?" },
              { type: "image_url", image_url: { url: IMAGE_URL } },
              { type: "text", text: "Answer in one word." },
              { type: "image_url", image_url: { url: DATA_URI } },
            ],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        {
          role: "user",
          content: [
            {
              type: "text",
              text: "What is in this image?\n\nAnswer in one word.",
            },
            { type: "image_url", image_url: { url: IMAGE_URL } },
            { type: "image_url", image_url: { url: DATA_URI } },
          ],
        },
      ]);
    });

    it("keeps text-only multi-part content as a string", () => {
      const input = {
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "First" },
              { type: "text", text: "Second" },
            ],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "user", content: "First\n\nSecond" },
      ]);
    });

    it("skips images that are attachment references", () => {
      const input = {
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Describe it" },
              {
                type: "image_url",
                image_url: { url: "[input-attachment-1-1700000000.png]" },
              },
            ],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "user", content: "Describe it" },
      ]);
    });

    it("keeps images on user messages only", () => {
      const input = {
        messages: [
          {
            role: "assistant",
            content: [
              { type: "text", text: "Here it is" },
              { type: "image_url", image_url: { url: IMAGE_URL } },
            ],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "assistant", content: "Here it is" },
      ]);
    });

    it("drops a message whose only content can't be loaded", () => {
      const input = {
        messages: [
          { role: "user", content: "Look" },
          {
            role: "user",
            content: [{ type: "image_url", image_url: { url: "[image_0]" } }],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "user", content: "Look" },
      ]);
    });

    it("skips raw audio data that isn't a URL", () => {
      const input = {
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: "Transcribe" },
              {
                type: "input_audio",
                input_audio: { data: "UklGRiQAAABXQVZF", format: "wav" },
              },
            ],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "user", content: "Transcribe" },
      ]);
    });
  });

  describe("tool and function messages", () => {
    it("drops tool calls and tool results, keeping the rest in order", () => {
      const input = {
        messages: [
          { role: "system", content: "Use tools when needed." },
          { role: "user", content: "Weather in Paris?" },
          {
            role: "assistant",
            content: null,
            tool_calls: [
              {
                id: "call_1",
                type: "function",
                function: {
                  name: "get_weather",
                  arguments: '{"city":"Paris"}',
                },
              },
            ],
          },
          {
            role: "tool",
            tool_call_id: "call_1",
            name: "get_weather",
            content: '{"temp":20}',
          },
          { role: "user", content: "Thanks" },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "system", content: "Use tools when needed." },
        { role: "user", content: "Weather in Paris?" },
        { role: "user", content: "Thanks" },
      ]);
    });

    it("keeps the text of an assistant message that also calls a tool", () => {
      const input = {
        messages: [
          {
            role: "assistant",
            content: "Let me check.",
            tool_calls: [
              {
                id: "call_1",
                type: "function",
                function: { name: "search", arguments: "{}" },
              },
            ],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "assistant", content: "Let me check." },
      ]);
    });

    it("drops legacy function messages", () => {
      const input = {
        messages: [
          { role: "user", content: "Run it" },
          { role: "function", name: "run", content: "done" },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "user", content: "Run it" },
      ]);
    });

    it("returns nothing when only tool messages are left", () => {
      const input = {
        messages: [{ role: "tool", tool_call_id: "call_1", content: "42" }],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([]);
    });
  });

  describe("LangChain format", () => {
    it("maps human / ai / system types onto Playground roles", () => {
      const input = {
        messages: [
          { type: "system", content: "You are a poet." },
          { type: "human", content: "Write a haiku." },
          { type: "ai", content: "Autumn moonlight..." },
          { type: "tool", name: "lookup", content: "ignored" },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "system", content: "You are a poet." },
        { role: "user", content: "Write a haiku." },
        { role: "assistant", content: "Autumn moonlight..." },
      ]);
    });

    it("uses the first batch of a batched input", () => {
      const input = {
        messages: [
          [
            { type: "human", content: "Hello" },
            {
              type: "human",
              content: [
                { type: "text", text: "And this?" },
                { type: "image_url", image_url: { url: IMAGE_URL } },
              ],
            },
          ],
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "user", content: "Hello" },
        {
          role: "user",
          content: [
            { type: "text", text: "And this?" },
            { type: "image_url", image_url: { url: IMAGE_URL } },
          ],
        },
      ]);
    });
  });

  describe("Playground format", () => {
    it("reads the input of a span logged by the Playground", () => {
      const input = {
        messages: [
          { role: "system", content: "Answer briefly." },
          {
            role: "user",
            content: [
              { type: "text", text: "Name this animal" },
              { type: "image_url", image_url: { url: IMAGE_URL } },
            ],
          },
        ],
      };

      expect(getPlaygroundMessagesFromInput(input)).toEqual([
        { role: "system", content: "Answer briefly." },
        {
          role: "user",
          content: [
            { type: "text", text: "Name this animal" },
            { type: "image_url", image_url: { url: IMAGE_URL } },
          ],
        },
      ]);
    });

    it("doesn't read a Playground output as input", () => {
      expect(getPlaygroundMessagesFromInput({ output: "Hi there" })).toEqual(
        [],
      );
    });
  });

  describe("unsupported input", () => {
    it.each([
      ["undefined", undefined],
      ["null", null],
      ["an empty object", {}],
      ["an empty array", []],
      ["a string", "What is 2 + 2?"],
      ["a non-message object", { prompt: "What is 2 + 2?", temperature: 0 }],
      ["an empty message list", { messages: [] }],
      ["messages with unknown roles", { messages: [{ role: "x", text: "a" }] }],
      ["only empty messages", { messages: [{ role: "user", content: "  " }] }],
      // Accepted by format detection, but the OpenAI mapper can't read them.
      [
        "a null content part",
        { messages: [{ role: "user", content: [null] }] },
      ],
      [
        "a null tool call",
        { messages: [{ role: "assistant", content: "", tool_calls: [null] }] },
      ],
    ])("returns nothing for %s", (_, input) => {
      expect(getPlaygroundMessagesFromInput(input)).toEqual([]);
    });
  });
});

describe("convertLLMMessagesToPlaygroundMessages", () => {
  it("keeps video and audio URLs on user messages", () => {
    const messages: LLMMessageDescriptor[] = [
      {
        id: "input-0",
        role: "user",
        blocks: [
          {
            blockType: "video",
            component: PrettyLLMMessage.VideoBlock,
            props: {
              videos: [{ url: "https://example.com/a.mp4", name: "a.mp4" }],
            },
          },
          {
            blockType: "audio",
            component: PrettyLLMMessage.AudioPlayerBlock,
            props: {
              audios: [{ url: "https://example.com/a.wav", name: "a.wav" }],
            },
          },
        ],
      },
    ];

    expect(convertLLMMessagesToPlaygroundMessages(messages)).toEqual([
      {
        role: "user",
        content: [
          {
            type: "video_url",
            video_url: { url: "https://example.com/a.mp4" },
          },
          {
            type: "audio_url",
            audio_url: { url: "https://example.com/a.wav" },
          },
        ],
      },
    ]);
  });

  it("skips code blocks", () => {
    const messages: LLMMessageDescriptor[] = [
      {
        id: "input-0",
        role: "assistant",
        blocks: [
          {
            blockType: "code",
            component: PrettyLLMMessage.CodeBlock,
            props: { code: "{}", label: "search" },
          },
        ],
      },
    ];

    expect(convertLLMMessagesToPlaygroundMessages(messages)).toEqual([]);
  });
});

describe("canOpenSpanInPlayground", () => {
  it("is true for an LLM span with input messages", () => {
    expect(canOpenSpanInPlayground(createSpan())).toBe(true);
  });

  it("is false for a trace", () => {
    const trace = {
      id: "trace-1",
      name: "trace",
      project_id: "project-1",
      input: { messages: [{ role: "user", content: "Hi" }] },
      output: {},
    } as Trace;

    expect(canOpenSpanInPlayground(trace)).toBe(false);
  });

  it.each([SPAN_TYPE.general, SPAN_TYPE.tool, SPAN_TYPE.guardrail])(
    "is false for a %s span",
    (type) => {
      expect(canOpenSpanInPlayground(createSpan({ type }))).toBe(false);
    },
  );

  it("is false while the span input hasn't loaded", () => {
    expect(
      canOpenSpanInPlayground(createSpan({ input: undefined as never })),
    ).toBe(false);
  });

  it("is false when the input isn't messages", () => {
    expect(
      canOpenSpanInPlayground(createSpan({ input: { query: "Hi" } })),
    ).toBe(false);
  });

  it("is false when no message maps to the Playground", () => {
    const input = {
      messages: [{ role: "tool", tool_call_id: "call_1", content: "42" }],
    };

    expect(canOpenSpanInPlayground(createSpan({ input }))).toBe(false);
  });
});
