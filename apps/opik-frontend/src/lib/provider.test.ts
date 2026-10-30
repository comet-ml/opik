import { describe, expect, it } from "vitest";
import { bedrockUrlForApi } from "@/lib/provider";
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
