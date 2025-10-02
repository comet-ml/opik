import { describe, expect, it } from "vitest";

import {
  convertLLMJudgeDataToLLMJudgeObject,
  convertLLMJudgeObjectToLLMJudgeData,
} from "./schema";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMMessageContentItem,
} from "@/types/llm";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

const imageContent: LLMMessageContentItem[] = [
  { type: "text", text: "Hello" },
  {
    type: "image_url",
    image_url: { url: "https://example.com/cat.png" },
  },
];

describe("automation schema serialization", () => {
  it("serializes structured content when building the backend payload", () => {
    const backendPayload = convertLLMJudgeDataToLLMJudgeObject({
      model: PROVIDER_MODEL_TYPE.GPT_4,
      config: { temperature: 0, seed: null },
      template: LLM_JUDGE.custom,
      messages: [
        {
          id: "message-1",
          role: LLM_MESSAGE_ROLE.user,
          content: imageContent,
        },
      ],
      variables: { "input.question": "{{question}}" },
      parsingVariablesError: false,
      schema: [
        {
          name: "score",
          type: LLM_SCHEMA_TYPE.DOUBLE,
          description: "Overall score",
          unsaved: false,
        },
      ],
    });

    expect(backendPayload.messages).toHaveLength(1);
    expect(backendPayload.messages[0].content).toBe(
      "Hello\n\n<<<image>>>https://example.com/cat.png<<</image>>>",
    );
    expect(
      Object.prototype.hasOwnProperty.call(backendPayload.schema[0], "unsaved"),
    ).toBe(false);
  });

  it("deserializes legacy image placeholders into structured content", () => {
    const formData = convertLLMJudgeObjectToLLMJudgeData({
      model: {
        name: PROVIDER_MODEL_TYPE.GPT_4,
        temperature: 0,
      },
      messages: [
        {
          role: LLM_MESSAGE_ROLE.user,
          content:
            "Hello\n\n<<<image>>>https://example.com/cat.png<<</image>>>",
        },
      ],
      variables: {},
      schema: [
        {
          name: "score",
          type: LLM_SCHEMA_TYPE.DOUBLE,
          description: "Overall score",
          unsaved: false,
        },
      ],
    });

    expect(formData.messages).toHaveLength(1);
    expect(formData.messages[0].content).toEqual(imageContent);
  });
});
