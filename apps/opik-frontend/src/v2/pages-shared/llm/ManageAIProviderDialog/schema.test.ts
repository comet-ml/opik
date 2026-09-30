import { describe, it, expect } from "vitest";

import { PROVIDER_TYPE } from "@/types/providers";
import {
  CloudAIProviderDetailsFormSchema,
  createCustomProviderDetailsFormSchema,
  supportsProviderHeaders,
} from "./schema";

const header = (key: string, value: string) => ({ key, value, id: key });

const cloudForm = (
  provider: PROVIDER_TYPE,
  headers?: Array<{ key: string; value: string; id: string }>,
) => ({
  provider,
  composedProviderType: provider,
  apiKey: "test-key",
  headers,
});

const issueMessages = (result: { success: boolean; error?: unknown }) =>
  result.success
    ? []
    : (result.error as { issues: Array<{ message: string }> }).issues.map(
        (issue) => issue.message,
      );

describe("supportsProviderHeaders", () => {
  it("is true only for OpenAI and OpenRouter", () => {
    expect(supportsProviderHeaders(PROVIDER_TYPE.OPEN_AI)).toBe(true);
    expect(supportsProviderHeaders(PROVIDER_TYPE.OPEN_ROUTER)).toBe(true);
    expect(supportsProviderHeaders(PROVIDER_TYPE.ANTHROPIC)).toBe(false);
    expect(supportsProviderHeaders(PROVIDER_TYPE.CUSTOM)).toBe(false);
    expect(supportsProviderHeaders(undefined)).toBe(false);
    expect(supportsProviderHeaders("")).toBe(false);
  });
});

describe("CloudAIProviderDetailsFormSchema headers", () => {
  it("accepts OpenRouter attribution headers", () => {
    const result = CloudAIProviderDetailsFormSchema.safeParse(
      cloudForm(PROVIDER_TYPE.OPEN_ROUTER, [
        header("HTTP-Referer", "https://example.com"),
        header("X-OpenRouter-Title", "My App"),
      ]),
    );

    expect(result.success).toBe(true);
  });

  it("accepts a form without headers", () => {
    expect(
      CloudAIProviderDetailsFormSchema.safeParse(
        cloudForm(PROVIDER_TYPE.OPEN_AI),
      ).success,
    ).toBe(true);
  });

  it.each(["Authorization", "authorization", " API-KEY "])(
    "rejects the reserved auth header %j",
    (key) => {
      const result = CloudAIProviderDetailsFormSchema.safeParse(
        cloudForm(PROVIDER_TYPE.OPEN_AI, [header(key, "Bearer x")]),
      );

      expect(issueMessages(result)).toEqual([
        "Use the API key field instead of this header",
      ]);
    },
  );

  it("requires both key and value and unique keys", () => {
    const result = CloudAIProviderDetailsFormSchema.safeParse(
      cloudForm(PROVIDER_TYPE.OPEN_ROUTER, [
        header("", "orphan value"),
        header("X-OpenRouter-Title", ""),
        header("X-Dup", "a"),
        header("X-Dup", "b"),
      ]),
    );

    expect(issueMessages(result)).toEqual([
      "Header key is required",
      "Header value is required",
      "Header key must be unique",
    ]);
  });

  it("does not validate headers for providers that ignore them", () => {
    expect(
      CloudAIProviderDetailsFormSchema.safeParse(
        cloudForm(PROVIDER_TYPE.ANTHROPIC, [header("Authorization", "x")]),
      ).success,
    ).toBe(true);
  });
});

describe("createCustomProviderDetailsFormSchema headers", () => {
  it("still allows an Authorization header on custom providers", () => {
    const result = createCustomProviderDetailsFormSchema().safeParse({
      provider: PROVIDER_TYPE.CUSTOM,
      composedProviderType: "custom-llm:my-llm",
      providerName: "my-llm",
      apiKey: "",
      url: "https://llm.example.com/v1",
      models: "model-a",
      headers: [header("Authorization", "Bearer x")],
    });

    expect(result.success).toBe(true);
  });
});
