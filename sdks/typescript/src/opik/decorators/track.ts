import { OpikClient } from "@/client/Client";
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

const trackStorage = new AsyncLocalStorage<TrackContext>();

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

  return { span, trace: spanTrace };
}

export function withTrack({
  name,
  projectName,
  type,
}: {
  name?: string;
  projectName?: string;
  type?: SpanType;
} = {}) {
  return function trackDecorator<T extends (...args: any[]) => any>(
    originalFn: T
  ): T {
    const wrappedFn = function (...args: any[]): ReturnType<T> {
      const context = trackStorage.getStore();
      const { span, trace } = logSpan({
        name: name ?? originalFn.name,
        parentSpan: context?.span,
        projectName,
        trace: context?.trace,
        type,
      });
      const isRootSpan = !context;
      // @ts-ignore
      const fnThis = this;

      return trackStorage.run({ span, trace }, () => {
        try {
          const result = originalFn.apply(fnThis, args);

          if (isPromise(result)) {
            return result.then(
              (res: any) => {
                span.end();
                if (isRootSpan) {
                  trace.end();
                }
                return res;
              },
              (err: any) => {
                span.end();
                if (isRootSpan) {
                  trace.end();
                }
                throw err;
              }
            ) as ReturnType<T>;
          }

          span.end();
          if (isRootSpan) {
            trace.end();
          }
          return result;
        } catch (e) {
          if (isRootSpan) {
            trace.end();
          }
          span.end();
          throw e;
        }
      });
    };

    return wrappedFn as T;
  };
}

export function track(
  options: {
    name?: string;
    projectName?: string;
    type?: SpanType;
  } = {}
) {
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

      return withTrack(options)(originalMethod);
    }

    // Legacy decorator API: (target, propertyKey, descriptor)
    const [target, propertyKey, descriptor] = args as [
      object,
      string | symbol,
      PropertyDescriptor,
    ];

    if (!descriptor || typeof descriptor.value !== "function") {
      throw new Error("track decorator can only be applied to methods");
    }

    const originalMethod = descriptor.value;
    descriptor.value = withTrack(options)(originalMethod);
    return descriptor;
  };
}

export const trackOpikClient = new OpikClient();
