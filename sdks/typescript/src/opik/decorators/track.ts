import "zone.js";
import { OpikClient } from "@/client/Client";

const client = new OpikClient();

function isPromise(obj: any): obj is Promise<any> {
  return (
    !!obj &&
    (typeof obj === "object" || typeof obj === "function") &&
    typeof obj.then === "function"
  );
}

export function track<T extends (...args: any[]) => any>(originalFn: T): T {
  console.log("track", originalFn.name);
  const wrappedFn = function (...args: any[]): ReturnType<T> {
    return trackContext.runInContext(() => {
      const parentSpan = trackContext.getActiveSpan();
      const span = trackContext.startSpan(originalFn.name, parentSpan);
      trackContext.pushSpan(span);

      try {
        const result = originalFn(...args);

        // Asynchronous functions
        if (isPromise(result)) {
          return result
            .then((awaitedResult: any) => {
              span.finish();
              trackContext.popSpan();
              return awaitedResult;
            })
            .catch((error: any) => {
              span.error(error);
              trackContext.popSpan();
              throw error;
            });
        }

        // Synchronous functions
        span.finish();
        trackContext.popSpan();
        return result;
      } catch (error) {
        span.error(error);
        trackContext.popSpan();
        throw error;
      }
    });
  };

  return wrappedFn as T;
}

export interface Span {
  name: string;
  finish: () => void;
  error: (error: any) => void;
}

export class OpikTrackContext {
  runInContext<T>(fn: () => T): T {
    // If the zone already has a span stack, don't create a new one
    if (Zone.current.get("spanStack")) {
      return fn();
    }

    return Zone.current
      .fork({
        name: "opikTrackZone",
        properties: { spanStack: [] as Span[] },
      })
      .run(fn);
  }

  getActiveSpan(): Span | undefined {
    const stack = Zone.current.get("spanStack") as Span[] | undefined;

    return stack?.at(-1);
  }

  pushSpan(span: Span) {
    const stack = Zone.current.get("spanStack") as Span[] | undefined;

    if (stack) {
      stack.push(span);
    } else {
      throw new Error("No zone context available. Did you call runInContext?");
    }
  }

  popSpan() {
    const stack = Zone.current.get("spanStack") as Span[] | undefined;

    if (stack) {
      stack.pop();
    }
  }

  startSpan(name: string, parent?: Span): Span {
    console.log(
      `Starting span: ${name} ${parent ? "(child)" : "(root)"} -> ${parent?.name} (parent)`
    );
    const span: Span = {
      name,
      finish: () => console.log(`Finishing span: ${name}`),
      error: (err: any) => console.error(`Error in span: ${name}`, err),
    };
    return span;
  }
}

const trackContext = new OpikTrackContext();
