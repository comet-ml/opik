import { WithRawResponse } from "../src/opik/rest_api/core/fetcher/RawResponse";
import { HttpResponsePromise } from "../src/opik/rest_api/core/fetcher/HttpResponsePromise";

export function createMockHttpResponsePromise<T>(
  data?: T
): HttpResponsePromise<T> {
  const withRawResponse: WithRawResponse<T> = {
    data: data as T,
    rawResponse: {
      status: 200,
      headers: {} as Headers,
      statusText: "OK",
      url: "https://mock.test",
      redirected: false,
      type: "basic",
    },
  };

  return HttpResponsePromise.fromResult(withRawResponse);
}

export function mockAPIFunction<T>(): HttpResponsePromise<T> {
  return createMockHttpResponsePromise<T>();
}

export function mockAPIFunctionWithError<T>(
  errorMessage: string
): () => HttpResponsePromise<T> {
  return () => {
    const promise = Promise.reject(new Error(errorMessage));

    return HttpResponsePromise.fromPromise(
      promise as unknown as Promise<WithRawResponse<T>>
    );
  };
}

/**
 * Creates a Web ReadableStream from string data
 * @param data The string data to stream
 * @param options Additional options for the stream
 * @returns A Web ReadableStream<Uint8Array>
 */
export function createMockStream(
  data: string,
  options: {
    delay?: number;
    lineByLine?: boolean;
    emitError?: boolean | Error;
  } = {}
): ReadableStream<Uint8Array> {
  const { delay = 0, lineByLine = false, emitError = false } = options;

  return new ReadableStream<Uint8Array>({
    async start(controller) {
      const encoder = new TextEncoder();
      try {
        if (lineByLine && data.length > 0) {
          const lines = data.split("\n").filter(Boolean);
          for (const line of lines) {
            if (delay) {
              await new Promise((resolve) => setTimeout(resolve, delay));
            }

            if (emitError) {
              const error =
                emitError instanceof Error
                  ? emitError
                  : new Error("Mock stream error");
              controller.error(error);
              return;
            }

            controller.enqueue(encoder.encode(`${line}\n`));
          }
        } else {
          if (delay) {
            await new Promise((resolve) => setTimeout(resolve, delay));
          }

          if (emitError) {
            const error =
              emitError instanceof Error
                ? emitError
                : new Error("Mock stream error");
            controller.error(error);
            return;
          }

          if (data.length > 0) {
            controller.enqueue(encoder.encode(data));
          }
        }
        controller.close();
      } catch (error) {
        controller.error(error);
      }
    },
  });
}

/**
 * Creates a mock HTTP response with a stream for API testing
 * @param data The string data to stream
 * @param options Stream creation options
 * @returns A HttpResponsePromise with a properly structured stream response
 */
export function mockAPIFunctionWithStream<T>(
  data: string,
  options: Parameters<typeof createMockStream>[1] = {}
): HttpResponsePromise<T> {
  const readableStream = createMockStream(data, options);

  const withRawResponse: WithRawResponse<T> = {
    data: readableStream as T,
    rawResponse: {
      status: 200,
      headers: {} as Headers,
      statusText: "OK",
      url: "https://mock.test",
      redirected: false,
      type: "basic",
    },
  };

  return HttpResponsePromise.fromResult(withRawResponse);
}
