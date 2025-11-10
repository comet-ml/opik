/**
 * Simple mock that returns a promise matching HttpResponsePromise interface
 */
export function mockAPIFunction<T = unknown>(): Promise<T> {
  return Promise.resolve({} as T);
}
