import { APIResponse } from "../fetcher/APIResponse";
/**
 * APIPromise wraps a Promise that resolves with an APIResponse.
 * It provides convenient methods for handling both successful responses and errors.
 *
 * By default, when awaited, it will return just the response body data.
 * Use the `asRaw()` method to get access to both the response data and headers.
 *
 * @example
 * // Get just the response data
 * const data = await apiPromise;
 *
 * // Get response with headers
 * const { data, headers } = await apiPromise.asRaw();
 *
 * @template T The type of the successful response body
 */
export declare class APIPromise<T> extends Promise<T> {
    private readonly responsePromise;
    constructor(responsePromise: Promise<APIResponse<T, unknown>>, executor: (resolve: (value: T | PromiseLike<T>) => void, reject: (reason?: any) => void) => void);
    asRaw(): Promise<{
        data: T;
        headers?: Record<string, any>;
    }>;
    static from<T>(responsePromise: Promise<APIResponse<T, unknown>>): APIPromise<T>;
}
