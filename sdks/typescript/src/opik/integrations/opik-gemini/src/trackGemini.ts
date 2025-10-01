import { OpikSingleton } from "./singleton";
import type { OpikExtension, TrackOpikConfig, TracingConfig } from "./types";
import { withTracing } from "./decorators";

/**
 * Detect if the SDK instance is using Vertex AI
 * Based on Python SDK logic: provider = "google_vertexai" if client.vertexai else "google_ai"
 */
const detectProvider = (sdk: object): string => {
  // Check if SDK has a vertexai property (similar to Python SDK)
  if ("vertexai" in sdk && sdk.vertexai) {
    return "google_vertexai";
  }

  // Default to google_ai
  return "google_ai";
};

/**
 * Track Gemini client with Opik observability
 *
 * Wraps a Gemini SDK instance (GoogleGenerativeAI or any Gemini client) to automatically
 * create traces and spans for all generation calls.
 *
 * @param sdk - The Gemini SDK instance to track
 * @param opikConfig - Configuration for Opik tracking
 * @returns Proxied SDK instance with flush() method
 *
 */
export const trackGemini = <SDKType extends object>(
  sdk: SDKType,
  opikConfig?: TrackOpikConfig
): SDKType & OpikExtension => {
  // Detect provider once for the entire SDK instance
  const provider = detectProvider(sdk);

  return new Proxy(sdk, {
    get(wrappedSdk, propKey, proxy) {
      const originalProperty = wrappedSdk[propKey as keyof SDKType];

      // 1. Build generationName: "ClassName.methodName"
      const className = sdk.constructor?.name || "Gemini";
      const generationName =
        opikConfig?.generationName ?? `${className}.${propKey.toString()}`;

      // 2. Build config with client singleton and detected provider
      const config: TracingConfig = {
        generationName,
        client: opikConfig?.client ?? OpikSingleton.getInstance(),
        parent: opikConfig?.parent,
        traceMetadata: opikConfig?.traceMetadata,
        provider,
      };

      // 3. Handle flush method
      if (propKey === "flush") {
        return config.client.flush.bind(config.client);
      }

      // 4. Wrap functions with tracing
      if (typeof originalProperty === "function") {
        return withTracing(originalProperty.bind(wrappedSdk), config);
      }

      // 5. Recursively wrap nested objects
      const isNestedGeminiObject =
        originalProperty &&
        !Array.isArray(originalProperty) &&
        !(originalProperty instanceof Date) &&
        typeof originalProperty === "object";

      if (isNestedGeminiObject) {
        // For nested objects, we need to wrap them recursively but pass the provider through
        // Note: nested objects might have their own provider, but we'll use the parent's for consistency
        return new Proxy(originalProperty, {
          get(nestedSdk, nestedPropKey, nestedProxy) {
            const nestedProperty =
              nestedSdk[nestedPropKey as keyof typeof nestedSdk];

            if (typeof nestedProperty === "function") {
              return withTracing(nestedProperty.bind(nestedSdk), config);
            }

            return Reflect.get(nestedSdk, nestedPropKey, nestedProxy);
          },
        });
      }

      // 6. Return original property for primitives
      return Reflect.get(wrappedSdk, propKey, proxy);
    },
  }) as SDKType & OpikExtension;
};
