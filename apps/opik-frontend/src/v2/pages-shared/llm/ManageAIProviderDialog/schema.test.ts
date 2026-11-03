import { describe, it, expect } from "vitest";

import { PROVIDER_TYPE } from "@/types/providers";
import {
  CloudAIProviderDetailsFormSchema,
  createAIProviderFormSchema,
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

  it("rejects a value without a name and names that differ only in case", () => {
    const result = CloudAIProviderDetailsFormSchema.safeParse(
      cloudForm(PROVIDER_TYPE.OPEN_ROUTER, [
        header("  ", "orphan value"),
        header("X-Team", "a"),
        header(" x-team ", "b"),
      ]),
    );

    expect(result.success).toBe(false);
    expect(result.error?.issues).toEqual([
      expect.objectContaining({
        message: "Header key is required",
        path: ["headers", 0, "key"],
      }),
      expect.objectContaining({
        message: "Header key must be unique, ignoring case",
        path: ["headers", 2, "key"],
      }),
    ]);
  });

  it("accepts empty values and skips empty rows, as the backend does", () => {
    const result = CloudAIProviderDetailsFormSchema.safeParse(
      cloudForm(PROVIDER_TYPE.OPEN_ROUTER, [
        header("X-Empty", ""),
        header("X-Blank", "  "),
        header("", ""),
        header("X-OpenRouter-Title", "My App"),
      ]),
    );

    expect(result.success).toBe(true);
  });

  it("surfaces the reserved-header issue through the provider form union", () => {
    const result = createAIProviderFormSchema().safeParse(
      cloudForm(PROVIDER_TYPE.OPEN_ROUTER, [header("Authorization", "x")]),
    );

    expect(result.success).toBe(false);
    expect(result.error?.issues).toEqual([
      expect.objectContaining({
        message: "Use the API key field instead of this header",
        path: ["headers", 0, "key"],
      }),
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

  it("rejects a value without a name and names that differ only in case", () => {
    const result = createCustomProviderDetailsFormSchema().safeParse({
      provider: PROVIDER_TYPE.CUSTOM,
      composedProviderType: "custom-llm:my-llm",
      providerName: "my-llm",
      apiKey: "",
      url: "https://llm.example.com/v1",
      models: "model-a",
      headers: [
        header("", "orphan value"),
        header("X-Team", "a"),
        header("x-team", "b"),
      ],
    });

    expect(issueMessages(result)).toEqual([
      "Header key is required",
      "Header key must be unique, ignoring case",
    ]);
  });
});

describe("createCustomProviderDetailsFormSchema on a URL change", () => {
  const STORED_URL =
    "https://bedrock-runtime.us-east-1.amazonaws.com/anthropic";
  const NEW_URL = "https://bedrock-runtime.us-east-1.amazonaws.com";

  const bedrockEdit = (overrides: Record<string, unknown>) => ({
    provider: PROVIDER_TYPE.BEDROCK,
    composedProviderType: "bedrock:my-bedrock",
    id: "provider-id",
    apiKey: "",
    url: NEW_URL,
    models: "us.amazon.nova-pro-v1:0",
    headers: [],
    ...overrides,
  });

  it("asks for the API key again when a key is stored", () => {
    const result = createCustomProviderDetailsFormSchema([], {
      base_url: STORED_URL,
      api_key: "*****",
    }).safeParse(bedrockEdit({}));

    expect(issueMessages(result)).toEqual([
      "Enter the API key again to change the URL",
    ]);
  });

  it("accepts the change with a new API key", () => {
    const result = createCustomProviderDetailsFormSchema([], {
      base_url: STORED_URL,
      api_key: "*****",
    }).safeParse(bedrockEdit({ apiKey: "new-key" }));

    expect(result.success).toBe(true);
  });

  it("accepts an edit that keeps the URL without the key", () => {
    const result = createCustomProviderDetailsFormSchema([], {
      base_url: STORED_URL,
      api_key: "*****",
    }).safeParse(bedrockEdit({ url: STORED_URL }));

    expect(result.success).toBe(true);
  });

  it("accepts the change when no key is stored", () => {
    const result = createCustomProviderDetailsFormSchema([], {
      base_url: STORED_URL,
    }).safeParse(bedrockEdit({}));

    expect(result.success).toBe(true);
  });

  it("asks for each stored secret again in token mode", () => {
    const credential = (key: string, value: string, secret: boolean) => ({
      key,
      value,
      secret,
      saved: true,
      id: key,
    });
    const result = createCustomProviderDetailsFormSchema([], {
      base_url: STORED_URL,
    }).safeParse(
      bedrockEdit({
        authMode: "token",
        authTokenUrl: "https://auth.example.com/oauth/token",
        authCredentials: [
          credential("client_id", "my-client", false),
          credential("client_secret", "__SECRET__", true),
        ],
      }),
    );

    expect(result.success).toBe(false);
    expect(
      (
        result as { error: { issues: Array<{ path: unknown[] }> } }
      ).error.issues.map((issue) => issue.path),
    ).toEqual([["authCredentials", 1, "value"]]);
  });
});
