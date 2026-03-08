import { withTracing } from "./decorators";
import { OpikSingleton } from "./singleton";
import {
  OpikExtension,
  TRACKED_METHOD_NAMES,
  TrackOpikConfig,
  TracingConfig,
} from "./types";

const isTrackableObject = (value: unknown): value is object => {
  if (value === null || value === undefined) {
    return false;
  }
  if (Array.isArray(value) || value instanceof Date) {
    return false;
  }
  if (typeof value !== "object") {
    return false;
  }

  const record = value as Record<string, unknown>;
  return Array.from(TRACKED_METHOD_NAMES).some(
    (methodName) => typeof record[methodName] === "function"
  );
};

const createGenerationName = (
  sdk: object,
  methodName: string,
  explicitName?: string
): string => {
  if (explicitName) {
    return explicitName;
  }

  const className = sdk.constructor?.name || "Agentica";
  return `${className}.${methodName}`;
};

const wrapResultIfNeeded = <T>(
  value: T,
  config: TrackOpikConfig,
  cache: WeakMap<object, object>
): T => {
  if (!isTrackableObject(value)) {
    return value;
  }

  return trackAgentica(value, config, cache) as T;
};

export const trackAgentica = <SDKType extends object>(
  sdk: SDKType,
  opikConfig?: TrackOpikConfig,
  cache: WeakMap<object, object> = new WeakMap()
): SDKType & OpikExtension => {
  if (cache.has(sdk)) {
    return cache.get(sdk) as SDKType & OpikExtension;
  }

  const proxy = new Proxy(sdk, {
    get(wrappedSdk, propKey, proxyRef) {
      const originalProperty = wrappedSdk[propKey as keyof SDKType];
      const methodName = propKey.toString();

      const config: TracingConfig = {
        ...opikConfig,
        generationName: createGenerationName(
          sdk,
          methodName,
          opikConfig?.generationName
        ),
        methodName,
        client: opikConfig?.client ?? OpikSingleton.getInstance(),
      };

      if (propKey === "flush") {
        return config.client.flush.bind(config.client);
      }

      if (
        typeof originalProperty === "function" &&
        TRACKED_METHOD_NAMES.has(methodName)
      ) {
        const tracedMethod = withTracing(
          originalProperty.bind(wrappedSdk),
          config,
          wrappedSdk
        );

        return (...args: unknown[]) => {
          const result = tracedMethod(...(args as never[]));
          if (result instanceof Promise) {
            return result.then((resolved) =>
              wrapResultIfNeeded(resolved, config, cache)
            );
          }

          return wrapResultIfNeeded(result, config, cache);
        };
      }

      if (isTrackableObject(originalProperty)) {
        return trackAgentica(originalProperty, config, cache);
      }

      return Reflect.get(wrappedSdk, propKey, proxyRef);
    },
  }) as SDKType & OpikExtension;

  cache.set(sdk, proxy);
  return proxy;
};

