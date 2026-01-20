import { toJson } from "../json.js";
import { createLogger, type LogConfig, type Logger } from "../logging/logger.js";
import type { APIResponse } from "./APIResponse.js";
import { createRequestUrl } from "./createRequestUrl.js";
import type { EndpointMetadata } from "./EndpointMetadata.js";
import { EndpointSupplier } from "./EndpointSupplier.js";
import { getErrorResponseBody } from "./getErrorResponseBody.js";
import { getFetchFn } from "./getFetchFn.js";
import { getRequestBody } from "./getRequestBody.js";
import { getResponseBody } from "./getResponseBody.js";
import { Headers } from "./Headers.js";
import { makeRequest } from "./makeRequest.js";
import { abortRawResponse, toRawResponse, unknownRawResponse } from "./RawResponse.js";
import { requestWithRetries } from "./requestWithRetries.js";

export type FetchFunction = <R = unknown>(args: Fetcher.Args) => Promise<APIResponse<R, Fetcher.Error>>;

export declare namespace Fetcher {
    export interface Args {
        url: string;
        method: string;
        contentType?: string;
        headers?: Record<string, unknown>;
        queryParameters?: Record<string, unknown>;
        body?: unknown;
        timeoutMs?: number;
        maxRetries?: number;
        withCredentials?: boolean;
        abortSignal?: AbortSignal;
        requestType?: "json" | "file" | "bytes" | "form" | "other";
        responseType?: "json" | "blob" | "sse" | "streaming" | "text" | "arrayBuffer" | "binary-response";
        duplex?: "half";
        endpointMetadata?: EndpointMetadata;
        fetchFn?: typeof fetch;
        logging?: LogConfig | Logger;
    }

    export type Error = FailedStatusCodeError | NonJsonError | BodyIsNullError | TimeoutError | UnknownError;

    export interface FailedStatusCodeError {
        reason: "status-code";
        statusCode: number;
        body: unknown;
    }

    export interface NonJsonError {
        reason: "non-json";
        statusCode: number;
        rawBody: string;
    }

    export interface BodyIsNullError {
        reason: "body-is-null";
        statusCode: number;
    }

    export interface TimeoutError {
        reason: "timeout";
    }

    export interface UnknownError {
        reason: "unknown";
        errorMessage: string;
    }
}

const SENSITIVE_HEADERS = new Set([
    "authorization",
    "www-authenticate",
    "x-api-key",
    "api-key",
    "apikey",
    "x-api-token",
    "x-auth-token",
    "auth-token",
    "cookie",
    "set-cookie",
    "proxy-authorization",
    "proxy-authenticate",
    "x-csrf-token",
    "x-xsrf-token",
    "x-session-token",
    "x-access-token",
]);

function redactHeaders(headers: Headers | Record<string, string>): Record<string, string> {
    const filtered: Record<string, string> = {};
    for (const [key, value] of headers instanceof Headers ? headers.entries() : Object.entries(headers)) {
        if (SENSITIVE_HEADERS.has(key.toLowerCase())) {
            filtered[key] = "[REDACTED]";
        } else {
            filtered[key] = value;
        }
    }
    return filtered;
}

const SENSITIVE_QUERY_PARAMS = new Set([
    "api_key",
    "api-key",
    "apikey",
    "token",
    "access_token",
    "access-token",
    "auth_token",
    "auth-token",
    "password",
    "passwd",
    "secret",
    "api_secret",
    "api-secret",
    "apisecret",
    "key",
    "session",
    "session_id",
    "session-id",
]);

function redactQueryParameters(queryParameters?: Record<string, unknown>): Record<string, unknown> | undefined {
    if (queryParameters == null) {
        return queryParameters;
    }
    const redacted: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(queryParameters)) {
        if (SENSITIVE_QUERY_PARAMS.has(key.toLowerCase())) {
            redacted[key] = "[REDACTED]";
        } else {
            redacted[key] = value;
        }
    }
    return redacted;
}

function redactUrl(url: string): string {
    const protocolIndex = url.indexOf("://");
    if (protocolIndex === -1) return url;

    const afterProtocol = protocolIndex + 3;

    // Find the first delimiter that marks the end of the authority section
    const pathStart = url.indexOf("/", afterProtocol);
    let queryStart = url.indexOf("?", afterProtocol);
    let fragmentStart = url.indexOf("#", afterProtocol);

    const firstDelimiter = Math.min(
        pathStart === -1 ? url.length : pathStart,
        queryStart === -1 ? url.length : queryStart,
        fragmentStart === -1 ? url.length : fragmentStart,
    );

    // Find the LAST @ before the delimiter (handles multiple @ in credentials)
    let atIndex = -1;
    for (let i = afterProtocol; i < firstDelimiter; i++) {
        if (url[i] === "@") {
            atIndex = i;
        }
    }

    if (atIndex !== -1) {
        url = `${url.slice(0, afterProtocol)}[REDACTED]@${url.slice(atIndex + 1)}`;
    }

    // Recalculate queryStart since url might have changed
    queryStart = url.indexOf("?");
    if (queryStart === -1) return url;

    fragmentStart = url.indexOf("#", queryStart);
    const queryEnd = fragmentStart !== -1 ? fragmentStart : url.length;
    const queryString = url.slice(queryStart + 1, queryEnd);

    if (queryString.length === 0) return url;

    // FAST PATH: Quick check if any sensitive keywords present
    // Using indexOf is faster than regex for simple substring matching
    const lower = queryString.toLowerCase();
    const hasSensitive =
        lower.includes("token") ||
        lower.includes("key") ||
        lower.includes("password") ||
        lower.includes("passwd") ||
        lower.includes("secret") ||
        lower.includes("session") ||
        lower.includes("auth");

    if (!hasSensitive) {
        return url;
    }

    // SLOW PATH: Parse and redact
    const redactedParams: string[] = [];
    const params = queryString.split("&");

    for (const param of params) {
        const equalIndex = param.indexOf("=");
        if (equalIndex === -1) {
            redactedParams.push(param);
            continue;
        }

        const key = param.slice(0, equalIndex);
        let shouldRedact = SENSITIVE_QUERY_PARAMS.has(key.toLowerCase());

        if (!shouldRedact && key.includes("%")) {
            try {
                const decodedKey = decodeURIComponent(key);
                shouldRedact = SENSITIVE_QUERY_PARAMS.has(decodedKey.toLowerCase());
            } catch {}
        }

        redactedParams.push(shouldRedact ? `${key}=[REDACTED]` : param);
    }

    return url.slice(0, queryStart + 1) + redactedParams.join("&") + url.slice(queryEnd);
}

async function getHeaders(args: Fetcher.Args): Promise<Headers> {
    const newHeaders: Headers = new Headers();

    newHeaders.set(
        "Accept",
        args.responseType === "json" ? "application/json" : args.responseType === "text" ? "text/plain" : "*/*",
    );
    if (args.body !== undefined && args.contentType != null) {
        newHeaders.set("Content-Type", args.contentType);
    }

    if (args.headers == null) {
        return newHeaders;
    }

    for (const [key, value] of Object.entries(args.headers)) {
        const result = await EndpointSupplier.get(value, { endpointMetadata: args.endpointMetadata ?? {} });
        if (typeof result === "string") {
            newHeaders.set(key, result);
            continue;
        }
        if (result == null) {
            continue;
        }
        newHeaders.set(key, `${result}`);
    }
    return newHeaders;
}

export async function fetcherImpl<R = unknown>(args: Fetcher.Args): Promise<APIResponse<R, Fetcher.Error>> {
    const url = createRequestUrl(args.url, args.queryParameters);
    const requestBody: BodyInit | undefined = await getRequestBody({
        body: args.body,
        type: args.requestType ?? "other",
    });
    const fetchFn = args.fetchFn ?? (await getFetchFn());
    const headers = await getHeaders(args);
    const logger = createLogger(args.logging);

    if (logger.isDebug()) {
        const metadata = {
            method: args.method,
            url: redactUrl(url),
            headers: redactHeaders(headers),
            queryParameters: redactQueryParameters(args.queryParameters),
            hasBody: requestBody != null,
        };
        logger.debug("Making HTTP request", metadata);
    }

    try {
        const response = await requestWithRetries(
            async () =>
                makeRequest(
                    fetchFn,
                    url,
                    args.method,
                    headers,
                    requestBody,
                    args.timeoutMs,
                    args.abortSignal,
                    args.withCredentials,
                    args.duplex,
                ),
            args.maxRetries,
        );

        if (response.status >= 200 && response.status < 400) {
            if (logger.isDebug()) {
                const metadata = {
                    method: args.method,
                    url: redactUrl(url),
                    statusCode: response.status,
                    responseHeaders: redactHeaders(response.headers),
                };
                logger.debug("HTTP request succeeded", metadata);
            }
            const body = await getResponseBody(response, args.responseType);
            return {
                ok: true,
                body: body as R,
                headers: response.headers,
                rawResponse: toRawResponse(response),
            };
        } else {
            if (logger.isError()) {
                const metadata = {
                    method: args.method,
                    url: redactUrl(url),
                    statusCode: response.status,
                    responseHeaders: redactHeaders(Object.fromEntries(response.headers.entries())),
                };
                logger.error("HTTP request failed with error status", metadata);
            }
            return {
                ok: false,
                error: {
                    reason: "status-code",
                    statusCode: response.status,
                    body: await getErrorResponseBody(response),
                },
                rawResponse: toRawResponse(response),
            };
        }
    } catch (error) {
        if (args.abortSignal?.aborted) {
            if (logger.isError()) {
                const metadata = {
                    method: args.method,
                    url: redactUrl(url),
                };
                logger.error("HTTP request was aborted", metadata);
            }
            return {
                ok: false,
                error: {
                    reason: "unknown",
                    errorMessage: "The user aborted a request",
                },
                rawResponse: abortRawResponse,
            };
        } else if (error instanceof Error && error.name === "AbortError") {
            if (logger.isError()) {
                const metadata = {
                    method: args.method,
                    url: redactUrl(url),
                    timeoutMs: args.timeoutMs,
                };
                logger.error("HTTP request timed out", metadata);
            }
            return {
                ok: false,
                error: {
                    reason: "timeout",
                },
                rawResponse: abortRawResponse,
            };
        } else if (error instanceof Error) {
            if (logger.isError()) {
                const metadata = {
                    method: args.method,
                    url: redactUrl(url),
                    errorMessage: error.message,
                };
                logger.error("HTTP request failed with error", metadata);
            }
            return {
                ok: false,
                error: {
                    reason: "unknown",
                    errorMessage: error.message,
                },
                rawResponse: unknownRawResponse,
            };
        }

        if (logger.isError()) {
            const metadata = {
                method: args.method,
                url: redactUrl(url),
                error: toJson(error),
            };
            logger.error("HTTP request failed with unknown error", metadata);
        }
        return {
            ok: false,
            error: {
                reason: "unknown",
                errorMessage: toJson(error),
            },
            rawResponse: unknownRawResponse,
        };
    }
}

export const fetcher: FetchFunction = fetcherImpl;
