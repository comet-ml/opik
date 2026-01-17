export { detectLLMMessages } from "./detectLLMMessages";

export {
  default as LLMMessagesHighlighter,
  type LLMMessagesHighlighterProps,
} from "./LLMMessagesHighlighter";

export type {
  LLMProvider,
  LLMProviderDetectionResult,
  LLMBlockDescriptor,
  LLMMessageDescriptor,
  ProviderDetector,
  ProviderMapper,
  LLMProviderImplementation,
} from "./types";

export { MediaProvider, useMediaContext } from "./MediaContext";

export {
  isPlaceholder,
  extractPlaceholderIndex,
  resolvePlaceholderURL,
  resolveMediaItems,
} from "./utils";

export { getProvider, getAllProviders } from "./providers";
