import { LLMProviderImplementation } from "../../types";
import { detectOpenAIFormat } from "./detector";
import { mapOpenAIMessages } from "./mapper";

export const openaiProvider: LLMProviderImplementation = {
  name: "openai",
  detector: detectOpenAIFormat,
  mapper: mapOpenAIMessages,
};

export { detectOpenAIFormat, mapOpenAIMessages };
