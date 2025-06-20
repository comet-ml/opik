import { WithRawResponse } from "../src/opik/rest_api/core/fetcher/RawResponse";
import { HttpResponsePromise } from "../src/opik/rest_api/core/fetcher/HttpResponsePromise";
import { Readable } from "node:stream";

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
 * Creates a readable stream from string data
 * @param data The string data to stream
 * @param options Additional options for the stream
 * @returns A readable stream
 */
export function createMockStream(
  data: string,
  options: {
    delay?: number;
    lineByLine?: boolean;
    emitError?: boolean | Error;
  } = {}
): Readable {
  const { delay = 0, lineByLine = false, emitError = false } = options;

  if (lineByLine && data.length > 0) {
    const lines = data.split("\n").filter(Boolean);

    return Readable.from(
      (async function* () {
        for (const line of lines) {
          if (delay) {
            await new Promise((resolve) => setTimeout(resolve, delay));
          }

          if (emitError) {
            const error =
              emitError instanceof Error
                ? emitError
                : new Error("Mock stream error");
            throw error;
          }

          yield `${line}\n`;
        }
      })()
    );
  }

  return Readable.from(
    (async function* () {
      if (delay) {
        await new Promise((resolve) => setTimeout(resolve, delay));
      }

      if (emitError) {
        const error =
          emitError instanceof Error
            ? emitError
            : new Error("Mock stream error");
        throw error;
      }

      if (data.length > 0) {
        yield data;
      }
    })()
  );
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
  const stream = createMockStream(data, options);

  const asyncIterable: AsyncIterable<Uint8Array> = {
    [Symbol.asyncIterator]: async function* () {
      for await (const chunk of stream) {
        const encoder = new TextEncoder();
        yield encoder.encode(chunk as string);
      }
    },
  };

  const withRawResponse: WithRawResponse<T> = {
    data: asyncIterable as T,
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
