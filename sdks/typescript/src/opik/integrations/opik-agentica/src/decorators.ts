import { OpikSpanType } from "opik";

import {
  parseInputArgs,
  parseOutput,
  parseUsage,
  parseUsageFromAgent,
} from "./parsers";
import {
  GenericMethod,
  LLM_METHOD_NAMES,
  ObservationData,
  OpikParent,
  TracingConfig,
} from "./types";

const parseMethodMetadata = (
  config: TracingConfig
): Record<string, unknown> => {
  const metadata = { ...(config.traceMetadata ?? {}) };
  delete metadata.tags;
  return {
    created_from: "agentica",
    method: config.methodName,
    ...metadata,
  };
};

const attachUsageCollectorToArgs = (
  methodName: string,
  args: unknown[],
  onUsage: (usage: Record<string, number> | undefined) => void
): unknown[] => {
  if (methodName !== "agentic" && methodName !== "agenticTransformation") {
    return args;
  }

  const nextArgs = [...args];
  const configIndex = typeof nextArgs[1] === "object" && nextArgs[1] !== null ? 2 : 1;
  const currentConfig = nextArgs[configIndex];

  if (typeof currentConfig === "object" && currentConfig !== null) {
    const configObject = currentConfig as Record<string, unknown>;
    const previousOnUsage = configObject.onUsage;

    nextArgs[configIndex] = {
      ...configObject,
      onUsage: (usage: unknown) => {
        onUsage(parseUsage(usage));
        if (typeof previousOnUsage === "function") {
          (previousOnUsage as (value: unknown) => void)(usage);
        }
      },
    };
    return nextArgs;
  }

  nextArgs[configIndex] = {
    onUsage: (usage: unknown) => onUsage(parseUsage(usage)),
  };
  return nextArgs;
};

const resolveType = (methodName: string): OpikSpanType =>
  LLM_METHOD_NAMES.has(methodName) ? OpikSpanType.Llm : OpikSpanType.General;

const handleError = (
  error: Error,
  rootTracer: OpikParent,
  observationData: ObservationData,
  hasUserProvidedParent: boolean
): void => {
  rootTracer.span({
    ...observationData,
    endTime: new Date(),
    type: OpikSpanType.General,
    errorInfo: {
      message: error.message,
      exceptionType: error.name,
      traceback: error.stack ?? "",
    },
  });

  if (!hasUserProvidedParent) {
    rootTracer.end();
  }
};

export const withTracing = <T extends GenericMethod>(
  tracedMethod: T,
  config: TracingConfig,
  methodOwner: unknown
): ((...args: Parameters<T>) => ReturnType<T>) => {
  return (...args) => wrapMethod(tracedMethod, config, methodOwner, ...args);
};

const wrapMethod = <T extends GenericMethod>(
  tracedMethod: T,
  config: TracingConfig,
  methodOwner: unknown,
  ...args: Parameters<T>
): ReturnType<T> => {
  const metadata = parseMethodMetadata(config);
  const tags = [
    "agentica",
    "symbolica",
    ...((config.traceMetadata?.tags as string[] | undefined) ?? []),
  ];

  const observationData: ObservationData = {
    provider: "symbolica",
    name: config.generationName,
    startTime: new Date(),
    input: parseInputArgs(config.methodName, args),
    metadata,
    tags,
  };

  let capturedUsage: Record<string, number> | undefined;
  const effectiveArgs = attachUsageCollectorToArgs(
    config.methodName,
    args,
    (usage) => {
      capturedUsage = usage;
    }
  ) as Parameters<T>;

  const hasUserProvidedParent = Boolean(config.parent);
  const rootTracer: OpikParent =
    config.parent ?? config.client.trace(observationData);

  const complete = (result: unknown): unknown => {
    const output = parseOutput(result);
    const usage = capturedUsage ?? parseUsageFromAgent(methodOwner);

    rootTracer.span({
      ...observationData,
      endTime: new Date(),
      output,
      usage,
      type: resolveType(config.methodName),
      metadata: {
        ...observationData.metadata,
        usage,
      },
    });

    if (!hasUserProvidedParent) {
      rootTracer.update({ output, endTime: new Date() });
    }

    return result;
  };

  try {
    const result = tracedMethod(...effectiveArgs);

    if (result instanceof Promise) {
      return result
        .then((value) => complete(value))
        .catch((error: Error) => {
          handleError(error, rootTracer, observationData, hasUserProvidedParent);
          throw error;
        }) as ReturnType<T>;
    }

    return complete(result) as ReturnType<T>;
  } catch (error) {
    handleError(
      error as Error,
      rootTracer,
      observationData,
      hasUserProvidedParent
    );
    throw error;
  }
};
