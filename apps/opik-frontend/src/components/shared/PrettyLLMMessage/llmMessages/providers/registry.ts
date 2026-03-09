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
  format: LLMMessageFormat,
): LLMMessageFormatImplementation | null => {
  return FORMAT_REGISTRY[format] || null;
};

export const getAllFormats = (): LLMMessageFormatImplementation[] => {
  return Object.values(FORMAT_REGISTRY).filter(
    (p): p is LLMMessageFormatImplementation => p !== null,
  );
};
