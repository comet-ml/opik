import { withTracing } from "./decorators";
import { OpikSingleton } from "./singleton";
import { OpikExtension, TrackOpikConfig } from "./types";

const getStringValue = (value: unknown): string | undefined => {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : undefined;
};

const getOpenAIClientBaseUrl = (sdk: object): string | undefined => {
  const maybeClient = sdk as Record<string, unknown>;
  const baseURL =
    getStringValue(maybeClient.baseURL) ??
    getStringValue(maybeClient.baseUrl) ??
    getStringValue((maybeClient.config as Record<string, unknown>)?.baseURL) ??
    getStringValue((maybeClient.config as Record<string, unknown>)?.baseUrl);

  if (baseURL) {
    return baseURL;
  }

  return undefined;
};

const getOpenAIProviderFromBaseUrl = (sdk: object): string | undefined => {
  const baseURL = getOpenAIClientBaseUrl(sdk);

  if (!baseURL) {
    return undefined;
  }

  try {
    const parsed = new URL(baseURL);
    const host = parsed.hostname.toLowerCase();

    if (host === "api.openai.com") {
      return "openai";
    }

    if (host.includes("openrouter.ai")) {
      return "openrouter";
    }

    return host;
  } catch {
    return getStringValue(baseURL)?.toLowerCase();
  }
};

export const trackOpenAI = <SDKType extends object>(
  sdk: SDKType,
  opikConfig?: TrackOpikConfig
): SDKType & OpikExtension => {
  const provider = getOpenAIProviderFromBaseUrl(sdk);

  return new Proxy(sdk, {
    get(wrappedSdk, propKey, proxy) {
      const originalProperty = wrappedSdk[propKey as keyof SDKType];

      const generationName =
        opikConfig?.generationName ??
        `${sdk.constructor?.name || "OpenAI"}.${propKey.toString()}`;

      const config = {
        ...opikConfig,
        provider,
        generationName,
        client: opikConfig?.client ?? OpikSingleton.getInstance(),
      };

      if (propKey === "flush") {
        return config.client.flush.bind(config.client);
      }

      if (typeof originalProperty === "function") {
        return withTracing(originalProperty.bind(wrappedSdk), config);
      }

      const isNestedOpenAIObject =
        originalProperty &&
        !Array.isArray(originalProperty) &&
        !(originalProperty instanceof Date) &&
        typeof originalProperty === "object";

      if (isNestedOpenAIObject) {
        return trackOpenAI(originalProperty, config);
      }

      return Reflect.get(wrappedSdk, propKey, proxy);
    },
  }) as SDKType & OpikExtension;
};
