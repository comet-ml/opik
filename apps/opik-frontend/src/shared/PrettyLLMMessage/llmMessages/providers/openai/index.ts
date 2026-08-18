import { LLMMessageFormatImplementation } from "../../types";
import { detectOpenAIFormat } from "./detector";
import { combineOpenAIMessages, mapOpenAIMessages } from "./mapper";

export const openaiFormat: LLMMessageFormatImplementation = {
  name: "openai",
  detector: detectOpenAIFormat,
  mapper: mapOpenAIMessages,
  combiner: combineOpenAIMessages,
};

export { combineOpenAIMessages, detectOpenAIFormat, mapOpenAIMessages };
