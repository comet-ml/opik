import { trackOpenAI } from "opik-openai";

export const trackOpenRouter = <SDKType extends object>(
  sdk: SDKType,
  opikConfig?: Parameters<typeof trackOpenAI>[1]
): SDKType & { flush: () => Promise<void> } => {
  return trackOpenAI(sdk, {
    ...opikConfig,
    provider: "openrouter",
  }) as SDKType & { flush: () => Promise<void> };
};
