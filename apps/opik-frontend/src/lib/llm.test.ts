import { describe, expect, it } from "vitest";

import {
  stringifyMessageContent,
  tryDeserializeMessageContent,
} from "@/lib/llm";
import { LLMMessageContentItem } from "@/types/llm";

describe("llm message serialization", () => {
  it("stringifies structured content with image placeholders", () => {
    const content: LLMMessageContentItem[] = [
      { type: "text", text: "Hello" },
      {
        type: "image_url",
        image_url: { url: "https://example.com/cat.png" },
      },
    ];

    const result = stringifyMessageContent(content, {
      includeImagePlaceholders: true,
    });

    expect(result).toBe(
      "Hello\n\n<<<image>>>https://example.com/cat.png<<</image>>>",
    );
  });

  it("omits image placeholders when disabled", () => {
    const content: LLMMessageContentItem[] = [
      { type: "text", text: "Hello" },
      {
        type: "image_url",
        image_url: { url: "https://example.com/cat.png" },
      },
    ];

    const result = stringifyMessageContent(content, {
      includeImagePlaceholders: false,
    });

    expect(result).toBe("Hello");
  });

  it("deserializes placeholder format into structured content", () => {
    const serialized =
      "Hello\n\n<<<image>>>https://example.com/cat.png<<</image>>>";

    const result = tryDeserializeMessageContent(serialized);

    expect(result).toEqual<LLMMessageContentItem[]>([
      { type: "text", text: "Hello" },
      {
        type: "image_url",
        image_url: { url: "https://example.com/cat.png" },
      },
    ]);
  });

  it("deserializes legacy markdown image placeholders", () => {
    const serialized = "Look\n\n![image](https://example.com/dog.png)";

    const result = tryDeserializeMessageContent(serialized);

    expect(result).toEqual<LLMMessageContentItem[]>([
      { type: "text", text: "Look" },
      {
        type: "image_url",
        image_url: { url: "https://example.com/dog.png" },
      },
    ]);
  });
});
