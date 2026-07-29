import { LLMMessageFormat, LLMMessageFormatImplementation } from "../types";
import { openaiFormat } from "./openai";
import { langchainFormat } from "./langchain";

const FORMAT_REGISTRY: Record<
  LLMMessageFormat,
  LLMMessageFormatImplementation | null
> = {
  openai: openaiFormat,
  langchain: langchainFormat,
  anthropic: null,
  google: null,
};

export const getFormat = (
  format: string,
): LLMMessageFormatImplementation | null => {
  if (!Object.prototype.hasOwnProperty.call(FORMAT_REGISTRY, format)) {
    return null;
  }

  return FORMAT_REGISTRY[format as LLMMessageFormat];
};

export const getAllFormats = (): LLMMessageFormatImplementation[] => {
  return Object.values(FORMAT_REGISTRY).filter(
    (p): p is LLMMessageFormatImplementation => p !== null,
  );
};
