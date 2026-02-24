import { LLMMessageFormatImplementation } from "../../types";
import { detectOpenAIFormat } from "./detector";
import { mapOpenAIMessages } from "./mapper";

export const openaiFormat: LLMMessageFormatImplementation = {
  name: "openai",
  detector: detectOpenAIFormat,
  mapper: mapOpenAIMessages,
};

export { detectOpenAIFormat, mapOpenAIMessages };
