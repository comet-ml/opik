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
