import { WithRawResponse } from "../src/opik/rest_api/core/fetcher/RawResponse";
import { HttpResponsePromise } from "../src/opik/rest_api/core/fetcher/HttpResponsePromise";
import { Readable } from "readable-stream";

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

export function createMockStream(data: string): Readable {
  const stream = new Readable({
    read() {
      this.push(data);
      this.push(null);
    },
  });
  return stream;
}

export function mockAPIFunctionWithStream<T>(
  data: string
): HttpResponsePromise<T> {
  const stream = createMockStream(data);
  const withRawResponse: WithRawResponse<T> = {
    data: stream as unknown as T,
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
