import { withTracing } from "./decorators";
import { OpikSingleton } from "./singleton";
import { OpikExtension, TrackOpikConfig } from "./types";

export const trackOpenAI = <SDKType extends object>(
  sdk: SDKType,
  opikConfig?: TrackOpikConfig
): SDKType & OpikExtension => {
  return new Proxy(sdk, {
    get(wrappedSdk, propKey, proxy) {
      const originalProperty = wrappedSdk[propKey as keyof SDKType];

      const generationName =
        opikConfig?.generationName ??
        `${sdk.constructor?.name || "OpenAI"}.${propKey.toString()}`;

      const config = {
        ...opikConfig,
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
