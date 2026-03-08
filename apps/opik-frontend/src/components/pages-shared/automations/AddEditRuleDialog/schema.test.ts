import { describe, expect, it } from "vitest";
import {
  convertLLMJudgeObjectToLLMJudgeData,
  convertLLMJudgeDataToLLMJudgeObject,
} from "./schema";
import { LLMJudgeObject } from "@/types/automations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { LLM_JUDGE, LLM_MESSAGE_ROLE, LLM_SCHEMA_TYPE } from "@/types/llm";

const createBaseLLMJudgeObject = (
  overrides: Partial<LLMJudgeObject["model"]> = {},
): LLMJudgeObject => ({
  model: {
    name: "openai/gpt-4o" as PROVIDER_MODEL_TYPE,
    temperature: 0.5,
    seed: 42,
    custom_parameters: null,
    ...overrides,
  },
  messages: [
    {
      role: "USER" as string,
      content: "Test message",
    },
  ],
  variables: { input: "input" },
  schema: [
    {
      name: "score",
      type: LLM_SCHEMA_TYPE.integer,
      description: "Test score",
    },
  ],
});

describe("convertLLMJudgeObjectToLLMJudgeData", () => {
  it("should convert throttling and maxConcurrentRequests from backend format", () => {
    const data = createBaseLLMJudgeObject({
      throttling: 2.5,
      max_concurrent_requests: 3,
    });

    const result = convertLLMJudgeObjectToLLMJudgeData(data);

    expect(result.config.throttling).toBe(2.5);
    expect(result.config.maxConcurrentRequests).toBe(3);
  });

  it("should default throttling and maxConcurrentRequests to null when not provided", () => {
    const data = createBaseLLMJudgeObject();

    const result = convertLLMJudgeObjectToLLMJudgeData(data);

    expect(result.config.throttling).toBeNull();
    expect(result.config.maxConcurrentRequests).toBeNull();
  });

  it("should preserve other model parameters alongside throttling", () => {
    const data = createBaseLLMJudgeObject({
      throttling: 1.0,
      max_concurrent_requests: 5,
    });

    const result = convertLLMJudgeObjectToLLMJudgeData(data);

    expect(result.model).toBe("openai/gpt-4o");
    expect(result.config.temperature).toBe(0.5);
    expect(result.config.seed).toBe(42);
    expect(result.config.throttling).toBe(1.0);
    expect(result.config.maxConcurrentRequests).toBe(5);
  });
});

describe("convertLLMJudgeDataToLLMJudgeObject", () => {
  const createBaseFormData = (configOverrides = {}) => ({
    model: "openai/gpt-4o" as string,
    config: {
      temperature: 0.5,
      seed: 42 as number | null,
      custom_parameters: null as Record<string, unknown> | null,
      throttling: null as number | null,
      maxConcurrentRequests: null as number | null,
      ...configOverrides,
    },
    template: LLM_JUDGE.custom,
    messages: [
      {
        id: "msg-1",
        content: "Test message",
        role: LLM_MESSAGE_ROLE.user,
      },
    ],
    variables: { input: "input" },
    parsingVariablesError: false,
    schema: [
      {
        name: "score",
        type: LLM_SCHEMA_TYPE.integer,
        description: "Test score",
      },
    ],
  });

  it("should include throttling and max_concurrent_requests when set", () => {
    const formData = createBaseFormData({
      throttling: 2.5,
      maxConcurrentRequests: 3,
    });

    const result = convertLLMJudgeDataToLLMJudgeObject(formData);

    expect(result.model.throttling).toBe(2.5);
    expect(result.model.max_concurrent_requests).toBe(3);
  });

  it("should omit throttling and max_concurrent_requests when null", () => {
    const formData = createBaseFormData({
      throttling: null,
      maxConcurrentRequests: null,
    });

    const result = convertLLMJudgeDataToLLMJudgeObject(formData);

    expect(result.model).not.toHaveProperty("throttling");
    expect(result.model).not.toHaveProperty("max_concurrent_requests");
  });

  it("should round-trip throttling and maxConcurrentRequests", () => {
    const originalBackendData = createBaseLLMJudgeObject({
      throttling: 5.0,
      max_concurrent_requests: 10,
    });

    // Backend -> Frontend form
    const formData = convertLLMJudgeObjectToLLMJudgeData(originalBackendData);
    expect(formData.config.throttling).toBe(5.0);
    expect(formData.config.maxConcurrentRequests).toBe(10);

    // Frontend form -> Backend
    const backendData = convertLLMJudgeDataToLLMJudgeObject(formData);
    expect(backendData.model.throttling).toBe(5.0);
    expect(backendData.model.max_concurrent_requests).toBe(10);
  });

  it("should handle zero throttling correctly", () => {
    const formData = createBaseFormData({
      throttling: 0,
      maxConcurrentRequests: 1,
    });

    const result = convertLLMJudgeDataToLLMJudgeObject(formData);

    // 0 is falsy but not null, so it should still be included
    // Note: the current implementation uses `!= null` check which handles 0 correctly
    expect(result.model.throttling).toBe(0);
    expect(result.model.max_concurrent_requests).toBe(1);
  });
});
