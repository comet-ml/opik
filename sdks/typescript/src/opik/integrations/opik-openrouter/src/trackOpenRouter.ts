import { trackOpenAI } from "opik-openai";

export const trackOpenRouter = <SDKType extends object>(
  sdk: SDKType,
  opikConfig?: Parameters<typeof trackOpenAI>[1]
): SDKType & { flush: () => Promise<void> } => {
  const explicitProviderProvided =
    opikConfig !== undefined && Object.prototype.hasOwnProperty.call(opikConfig, "provider");

  const resolvedConfig = {
    ...opikConfig,
    ...(explicitProviderProvided ? {} : { provider: "openrouter" }),
  } as Parameters<typeof trackOpenAI>[1] & { provider?: string };

  return trackOpenAI(sdk, resolvedConfig) as SDKType & {
    flush: () => Promise<void>;
  };
};
