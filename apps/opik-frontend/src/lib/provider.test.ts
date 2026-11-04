import { describe, expect, it } from "vitest";
import {
  bedrockUrlForApi,
  getOpenRouterBaseUrl,
  normalizeBedrockApi,
  openRouterRegionFromBaseUrl,
} from "@/lib/provider";
import { BedrockApi } from "@/types/providers";

describe("bedrockUrlForApi", () => {
  it.each<[string, BedrockApi, string]>([
    [
      "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1",
      "anthropic_messages",
      "https://bedrock-runtime.eu-west-1.amazonaws.com/anthropic",
    ],
    [
      "https://bedrock-runtime.eu-west-1.amazonaws.com/anthropic/",
      "converse",
      "https://bedrock-runtime.eu-west-1.amazonaws.com",
    ],
    [
      "https://bedrock-runtime.eu-west-1.amazonaws.com",
      "chat_completions",
      "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1",
    ],
    [
      "https://bedrock-runtime-fips.us-east-1.amazonaws.com/openai/v1",
      "anthropic_messages",
      "https://bedrock-runtime-fips.us-east-1.amazonaws.com/anthropic",
    ],
    [
      "https://vpce-0abc.bedrock-runtime.eu-west-1.vpce.amazonaws.com/openai/v1",
      "converse",
      "https://vpce-0abc.bedrock-runtime.eu-west-1.vpce.amazonaws.com",
    ],
    [
      "https://bedrock-runtime.cn-north-1.amazonaws.com.cn/openai/v1",
      "anthropic_messages",
      "https://bedrock-runtime.cn-north-1.amazonaws.com.cn/anthropic",
    ],
    [
      "https://bedrock-runtime.eu-west-1.amazonaws.com/anthropic?team=evals",
      "chat_completions",
      "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1?team=evals",
    ],
  ])("moves %s to the path of %s", (url, api, expected) => {
    expect(bedrockUrlForApi(url, api)).toBe(expected);
  });

  it.each([
    "https://bedrock-mantle.eu-west-1.api.aws/anthropic/v1/messages",
    "https://llm-gateway.example.com/bedrock/openai/v1",
    "https://bedrock-runtime.proxy.example.com/tenant/openai/v1",
    "not a url",
  ])("keeps %s as typed", (url) => {
    expect(bedrockUrlForApi(url, "converse")).toBe(url);
  });
});

describe("normalizeBedrockApi", () => {
  it.each([
    ["converse", "converse"],
    [" Converse ", "converse"],
    ["ANTHROPIC_MESSAGES", "anthropic_messages"],
    ["unknown", "chat_completions"],
    ["", "chat_completions"],
    [undefined, "chat_completions"],
    [null, "chat_completions"],
  ])("reads %j as %s, as the backend does", (stored, expected) => {
    expect(normalizeBedrockApi(stored)).toBe(expected);
  });
});

describe("openRouterRegionFromBaseUrl", () => {
  it.each([
    [undefined, "global"],
    [null, "global"],
    ["", "global"],
    ["  ", "global"],
    ["https://us.openrouter.ai/api/v1", "us"],
    ["https://eu.openrouter.ai/api/v1/", "eu"],
    ["https://openrouter.ai/api/v1", "custom"],
    ["https://openrouter-proxy.example.com/api/v1", "custom"],
  ])("maps %s to %s", (baseUrl, region) => {
    expect(openRouterRegionFromBaseUrl(baseUrl)).toBe(region);
  });
});

describe("getOpenRouterBaseUrl", () => {
  it.each([
    ["global", "https://ignored.example.com/api/v1", ""],
    ["us", undefined, "https://us.openrouter.ai/api/v1"],
    ["eu", undefined, "https://eu.openrouter.ai/api/v1"],
    [
      "custom",
      " https://openrouter-proxy.example.com/api/v1 ",
      "https://openrouter-proxy.example.com/api/v1",
    ],
  ] as const)("maps %s to its base URL", (region, customBaseUrl, expected) => {
    expect(getOpenRouterBaseUrl(region, customBaseUrl)).toBe(expected);
  });

  it("round-trips every region through the stored base URL", () => {
    (["global", "us", "eu"] as const).forEach((region) => {
      expect(
        openRouterRegionFromBaseUrl(getOpenRouterBaseUrl(region, undefined)),
      ).toBe(region);
    });
  });
});
