/* eslint-disable @typescript-eslint/no-explicit-any */
import { OpikClient } from "@/client/Client";
import { logger } from "@/utils/logger";
import { SpanType } from "@/rest_api/api/types/SpanType";
import { Span } from "@/tracer/Span";
import { Trace } from "@/tracer/Trace";
import { AsyncLocalStorage } from "node:async_hooks";

type TrackContext =
  | {
      span?: Span;
      trace?: Trace;
    }
  | { span: Span; trace: Trace };

const DEFAULT_TRACK_NAME = "track.decorator";

const trackStorage = new AsyncLocalStorage<TrackContext>();

export const getTrackContext = (): Required<TrackContext> | undefined => {
  const { span, trace } = trackStorage.getStore() || {};

  if (!span || !trace) {
    return undefined;
  }

  return { span, trace };
};

function isPromise(obj: any): obj is Promise<any> {
  return (
    !!obj &&
    (typeof obj === "object" || typeof obj === "function") &&
    typeof obj.then === "function"
  );
}

function logSpan({
  name,
  parentSpan,
  projectName,
  trace,
  type = "llm",
}: {
  name: string;
  parentSpan?: Span;
  projectName?: string;
  trace?: Trace;
  type?: SpanType;
}) {
  logger.debug("Creating new span:", {
    name,
    parentSpan: parentSpan?.data.id,
    projectName,
    type,
  });
  let spanTrace = trace;

  if (!spanTrace) {
    spanTrace = trackOpikClient.trace({
      name,
      projectName,
    });
  }

  const span = spanTrace.span({
    name,
    parentSpanId: parentSpan?.data.id,
    projectName,
    type,
  });

  logger.debug("Span created with ID:", span.data.id);
  return { span, trace: spanTrace };
}

function logStart({
  args,
  span,
  trace,
}: {
  args: any[];
  span: Span;
  trace?: Trace;
}) {
  logger.debug("Starting span execution:", {
    spanId: span.data.id,
    traceId: trace?.data.id,
  });
  if (args.length === 0) {
    return;
  }

  const input = { arguments: args };
  logger.debug("Recording span input");
  span.update({ input });

  if (trace) {
    logger.debug("Recording trace input");
    trace.update({ input });
  }
}

function logSuccess({
  result,
  span,
  trace,
}: {
  result: any;
  span: Span;
  trace?: Trace;
}) {
  logger.debug("Recording successful execution:", {
    spanId: span.data.id,
    traceId: trace?.data.id,
  });
  const output = typeof result === "object" ? result : { result };
  const endTime = new Date();

  span.update({ endTime, output });

  if (trace) {
    trace.update({ endTime, output });
  }
}

function logError({
  span,
  error,
  trace,
}: {
  span: Span;
  error: any;
  trace?: Trace;
}) {
  logger.error("Recording execution error:", {
    spanId: span.data.id,
    traceId: trace?.data.id,
    error:
      error instanceof Error
        ? {
            name: error.name,
            message: error.message,
            stack: error.stack,
          }
        : error,
  });

  if (error instanceof Error) {
    span.update({
      errorInfo: {
        message: error.message,
        exceptionType: error.name,
        traceback: error.stack ?? "",
      },
    });
  }
  span.end();

  if (trace) {
    trace.update({
      errorInfo: {
        message: error.message,
        exceptionType: error.name,
        traceback: error.stack ?? "",
      },
    });
    trace.end();
  }
}

function executeTrack<T extends (...args: any[]) => any>(
  {
    name,
    projectName,
    type,
  }: {
    name?: string;
    projectName?: string;
    type?: SpanType;
  } = {},
  originalFn: T
): T {
  const wrappedFn = function (this: any, ...args: any[]): ReturnType<T> {
    const context = trackStorage.getStore();
    const { span, trace } = logSpan({
      name: name ?? (originalFn.name || DEFAULT_TRACK_NAME),
      parentSpan: context?.span,
      projectName,
      trace: context?.trace,
      type,
    });
    const isRootSpan = !context;
    const fnThis = this as any;

    return trackStorage.run({ span, trace }, () => {
      try {
        logStart({ args, span, trace: isRootSpan ? trace : undefined });

        const result = originalFn.apply(fnThis, args);

        if (isPromise(result)) {
          return result.then(
            (res: any) => {
              logSuccess({
                span,
                result: res,
                trace: isRootSpan ? trace : undefined,
              });
              return res;
            },
            (err) => {
              logError({
                span,
                error: err,
                trace: isRootSpan ? trace : undefined,
              });

              throw err;
            }
          ) as ReturnType<T>;
        }

        logSuccess({
          span,
          result,
          trace: isRootSpan ? trace : undefined,
        });

        return result;
      } catch (error) {
        logError({
          span,
          error,
          trace: isRootSpan ? trace : undefined,
        });
        throw error;
      }
    });
  };

  return wrappedFn as T;
}

type TrackOptions = {
  name?: string;
  projectName?: string;
  type?: SpanType;
};

type OriginalFunction = (...args: any[]) => any;

export function track(
  optionsOrOriginalFunction: TrackOptions | OriginalFunction,
  originalFunction?: OriginalFunction
) {
  if (typeof optionsOrOriginalFunction === "function") {
    return executeTrack({}, optionsOrOriginalFunction);
  }

  const options = optionsOrOriginalFunction;

  if (originalFunction) {
    return executeTrack(options, originalFunction);
  }

  return function (...args: any[]): any {
    // New decorator API: (value, context)
    if (
      args.length === 2 &&
      typeof args[1] === "object" &&
      args[1] !== null &&
      "kind" in args[1]
    ) {
      const [originalMethod, context] = args as [
        (...args: any[]) => any,
        { kind: string; name: string | symbol },
      ];

      if (context.kind !== "method") {
        throw new Error("track decorator is only applicable to methods");
      }

      return executeTrack(options, originalMethod);
    }

    // Legacy decorator API: (target, propertyKey, descriptor)
    const [, , descriptor] = args as [
      object,
      string | symbol,
      PropertyDescriptor,
    ];

    if (!descriptor || typeof descriptor.value !== "function") {
      throw new Error("track decorator can only be applied to methods");
    }

    const originalMethod = descriptor.value;
    descriptor.value = executeTrack(options, originalMethod);
    return descriptor;
  };
}

export const trackOpikClient = new OpikClient();
