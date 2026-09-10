import { describe, expect, it } from "vitest";
import {
  isSafeOpenInferenceMediaUrl,
  parseOpenInferenceFields,
} from "./openinference";

describe("parseOpenInferenceFields", () => {
  it("retains semantic output attributes stored alongside canonical messages", () => {
    const messages = [{ role: "assistant", content: "Answer" }];
    const parsed = parseOpenInferenceFields(
      { "llm.function_call": '{"name":"stale"}' },
      {
        messages,
        "llm.function_call": '{"name":"current"}',
        "llm.finish_reason": "function_call",
      },
    );

    expect(parsed.outputMessages).toEqual(messages);
    expect(parsed.functionCall).toEqual({ name: "current" });
    expect(parsed.finishReason).toBe("function_call");
  });

  it("matches reordered message properties without removing repeated turns", () => {
    const messages = [
      { role: "user", content: "Again" },
      { role: "assistant", content: "Answer" },
      { role: "user", content: "Again" },
    ];
    const input = {
      messages,
      ...Object.fromEntries(
        messages.flatMap((message, index) => [
          [`llm.input_messages.${index * 3}.message.content`, message.content],
          [`llm.input_messages.${index * 3}.message.role`, message.role],
        ]),
      ),
    };

    expect(parseOpenInferenceFields(input, undefined).inputMessages).toEqual(
      messages,
    );
  });

  it("matches reordered nested tool properties and keeps distinct tools", () => {
    const tools = [
      {
        name: "search",
        json_schema: { type: "object", required: ["query", "limit"] },
      },
      { name: "other" },
    ];
    const input = {
      tools,
      "llm.tools.0.tool.json_schema":
        '{"required":["query","limit"],"type":"object"}',
      "llm.tools.0.tool.name": "search",
    };

    expect(parseOpenInferenceFields(input, undefined).tools).toEqual(tools);
  });

  it("keeps array order significant when comparing tools", () => {
    const tools = [{ name: "search", json_schema: { enum: ["a", "b"] } }];
    const input = {
      tools,
      "llm.tools.0.tool.name": "search",
      "llm.tools.0.tool.json_schema": '{"enum":["b","a"]}',
    };

    expect(parseOpenInferenceFields(input, undefined).tools).toEqual([
      ...tools,
      { name: "search", json_schema: { enum: ["b", "a"] } },
    ]);
  });

  it("matches JSON message content decoded by historical storage", () => {
    const message = {
      role: "tool",
      content: '{"city":"Paris","temperature":21}',
    };
    const input = {
      messages: [message],
      "llm.input_messages.10.message.role": "tool",
      "llm.input_messages.10.message.content": {
        temperature: 21,
        city: "Paris",
      },
    };

    expect(parseOpenInferenceFields(input, undefined).inputMessages).toEqual([
      message,
    ]);
  });
});

describe("isSafeOpenInferenceMediaUrl", () => {
  it.each(["image/svg+xml", "image/x-unknown", "text/html"])(
    "rejects inline %s images",
    (mimeType) => {
      expect(
        isSafeOpenInferenceMediaUrl(`data:${mimeType};base64,AA==`, "image"),
      ).toBe(false);
    },
  );

  it.each(["png", "jpeg", "gif", "webp", "avif", "bmp"])(
    "accepts inline %s images",
    (subtype) => {
      expect(
        isSafeOpenInferenceMediaUrl(
          `data:image/${subtype};base64,AA==`,
          "image",
        ),
      ).toBe(true);
    },
  );
});

describe("historical nested JSON values", () => {
  it.each(["text", "data", "signature", "encrypted_content"])(
    "matches decoded %s without duplicating repeated turns",
    (field) => {
      const message = {
        role: "user",
        content: "Summary",
        contents: [{ type: "text", [field]: '{"a":1}' }],
      };
      const input = {
        messages: [message, message],
        ...Object.fromEntries(
          [0, 1].flatMap((i) => [
            [`llm.input_messages.${i}.message.role`, "user"],
            [`llm.input_messages.${i}.message.content`, "Summary"],
            [
              `llm.input_messages.${i}.message.contents.0.message_content.type`,
              "text",
            ],
            [
              `llm.input_messages.${i}.message.contents.0.message_content.${field}`,
              { a: 1 },
            ],
          ]),
        ),
      };
      expect(parseOpenInferenceFields(input, undefined).inputMessages).toEqual([
        message,
        message,
      ]);
      expect(
        parseOpenInferenceFields({ ...input, messages: undefined }, undefined)
          .inputMessages,
      ).toHaveLength(2);
    },
  );
});
