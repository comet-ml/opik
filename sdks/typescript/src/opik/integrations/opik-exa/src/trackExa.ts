import { OpikSingleton } from "./singleton";
import { GenericMethod, OpikExtension, TrackExaConfig } from "./types";
import { OpikSpanType } from "opik";

const TRACEABLE_METHODS = new Set([
  "search",
  "search_and_contents",
  "searchAndContents",
  "find_similar",
  "findSimilar",
  "get_contents",
  "getContents",
  "answer",
]);

const normalizeOperation = (operation: string): string => {
  return operation.replace(/[A-Z]/g, (match) => `_${match.toLowerCase()}`);
};

const isTrackableMethod = (methodName: string): boolean => {
  return TRACEABLE_METHODS.has(methodName);
};

const getInput = (args: unknown[]): Record<string, unknown> => {
  if (args.length === 1 && typeof args[0] === "object" && args[0] !== null) {
    return args[0] as Record<string, unknown>;
  }

  return { arguments: args };
};

const getResultCount = (result: unknown): number | undefined => {
  if (typeof result !== "object" || result === null) {
    return undefined;
  }

  const objectResult = result as Record<string, unknown>;
  if (Array.isArray(objectResult.results)) {
    return objectResult.results.length;
  }

  if (Array.isArray(objectResult.data)) {
    return objectResult.data.length;
  }

  return undefined;
};

const normalizeOutput = (output: unknown): Record<string, unknown> => {
  if (typeof output === "object" && output !== null) {
    return output as Record<string, unknown>;
  }

  return { result: output as string | number | boolean | null | undefined };
};

const buildMetadata = (
  operation: string,
  config: TrackExaConfig
): {
  metadata: Record<string, unknown>;
  tags: string[];
} => {
  const traceMetadata = config.traceMetadata ?? {};
  const tags = Array.isArray(traceMetadata.tags)
    ? (traceMetadata.tags as string[])
    : [];

  const { tags: _tags, ...metadataWithoutTags } = traceMetadata;
  return {
    metadata: {
      ...metadataWithoutTags,
      created_from: "exa",
      "opik.kind": "search",
      "opik.provider": "exa",
      "opik.operation": operation,
    },
    tags,
  };
};

const withTracing = <T extends GenericMethod>(
  method: T,
  methodName: string,
  config: TrackExaConfig
): ((...args: Parameters<T>) => ReturnType<T>) => {
  const operation = normalizeOperation(methodName);
  const spanName = config.generationName ?? `exa.${operation}`;

  return ((...args: Parameters<T>) => {
    const input = getInput(args);
    const { metadata, tags } = buildMetadata(operation, config);
    const client =
      config.client ?? OpikSingleton.getInstance(config.clientConfig ?? {});

    const parent = config.parent;
    const rootTrace = parent
      ? undefined
      : client.trace({
          name: spanName,
          input,
          metadata,
          tags,
          projectName: config.projectName,
        });
    const spanParent = parent ?? rootTrace;

    if (!spanParent) {
      return method(...args) as ReturnType<T>;
    }

    const span = spanParent.span({
      type: OpikSpanType.Tool,
      name: spanName,
      input,
      metadata,
      tags,
      projectName: config.projectName,
    });

    const finish = (
      output: unknown,
      error?: Error
    ): Record<string, unknown> | void => {
      const endTime = new Date();
      const resultCount = getResultCount(output);
      const updatedMetadata =
        resultCount === undefined
          ? metadata
          : { ...metadata, "opik.result_count": resultCount };

      if (error) {
        const errorInfo = {
          message: error.message,
          exceptionType: error.name,
          traceback: error.stack ?? "",
        };
        span.update({ endTime, errorInfo, metadata: updatedMetadata });
        span.end();

        if (rootTrace) {
          rootTrace.update({ endTime, errorInfo, metadata: updatedMetadata });
          rootTrace.end();
        }
        return;
      }

      const normalizedOutput = normalizeOutput(output);
      span.update({ endTime, output: normalizedOutput, metadata: updatedMetadata });
      span.end();

      if (rootTrace) {
        rootTrace.update({
          endTime,
          output: normalizedOutput,
          metadata: updatedMetadata,
        });
        rootTrace.end();
      }
      return updatedMetadata;
    };

    try {
      const result = method(...args);
      if (result instanceof Promise) {
        return result
          .then((resolved) => {
            finish(resolved);
            return resolved;
          })
          .catch((error: Error) => {
            finish(undefined, error);
            throw error;
          }) as ReturnType<T>;
      }

      finish(result);
      return result as ReturnType<T>;
    } catch (error) {
      finish(undefined, error as Error);
      throw error;
    }
  }) as (...args: Parameters<T>) => ReturnType<T>;
};

const createTrackedProxy = <SDKType extends object>(
  sdk: SDKType,
  config: TrackExaConfig
): SDKType & OpikExtension => {
  return new Proxy(sdk, {
    get(target, propKey, receiver) {
      if (propKey === "flush") {
        const client =
          config.client ?? OpikSingleton.getInstance(config.clientConfig ?? {});
        return client.flush.bind(client);
      }

      const value = Reflect.get(target, propKey, receiver);

      if (typeof value === "function") {
        const methodName = propKey.toString();
        if (isTrackableMethod(methodName)) {
          return withTracing(value.bind(target), methodName, config);
        }
        return value.bind(target);
      }

      const isNestedObject =
        value &&
        typeof value === "object" &&
        !Array.isArray(value) &&
        !(value instanceof Date);
      if (isNestedObject) {
        return createTrackedProxy(value as object, config);
      }

      return value;
    },
  }) as SDKType & OpikExtension;
};

export const trackExa = <SDKType extends object>(
  sdk: SDKType,
  config: TrackExaConfig = {}
): SDKType & OpikExtension => {
  return createTrackedProxy(sdk, config);
};
