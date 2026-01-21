import { anySignal, getTimeoutSignal } from "./signals.js";

export const makeRequest = async (
    fetchFn: (url: string, init: RequestInit) => Promise<Response>,
    url: string,
    method: string,
    headers: Headers | Record<string, string>,
    requestBody: BodyInit | undefined,
    timeoutMs?: number,
    abortSignal?: AbortSignal,
    withCredentials?: boolean,
    duplex?: "half",
): Promise<Response> => {
    const signals: AbortSignal[] = [];

    let timeoutAbortId: ReturnType<typeof setTimeout> | undefined;
    if (timeoutMs != null) {
        const { signal, abortId } = getTimeoutSignal(timeoutMs);
        timeoutAbortId = abortId;
        signals.push(signal);
    }

    if (abortSignal != null) {
        signals.push(abortSignal);
    }
    const newSignals = anySignal(signals);
    const response = await fetchFn(url, {
        method: method,
        headers,
        body: requestBody,
        signal: newSignals,
        credentials: withCredentials ? "include" : undefined,
        // @ts-ignore
        duplex,
    });

    if (timeoutAbortId != null) {
        clearTimeout(timeoutAbortId);
    }

    return response;
};
