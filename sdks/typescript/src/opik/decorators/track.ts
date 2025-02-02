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

export function wrapTrack({
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
          const result = originalFn.call(fnThis, args);

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

export function track({
  name,
  projectName,
  type,
}: {
  name?: string;
  projectName?: string;
  type?: SpanType;
} = {}) {
  return function (value: any): any {
    return wrapTrack({ name, projectName, type })(value);
  };
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

export const trackOpikClient = new OpikClient();
