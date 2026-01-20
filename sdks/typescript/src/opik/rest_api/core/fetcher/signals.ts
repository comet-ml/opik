const TIMEOUT = "timeout";

export function getTimeoutSignal(timeoutMs: number): { signal: AbortSignal; abortId: ReturnType<typeof setTimeout> } {
    const controller = new AbortController();
    const abortId = setTimeout(() => controller.abort(TIMEOUT), timeoutMs);
    return { signal: controller.signal, abortId };
}

export function anySignal(...args: AbortSignal[] | [AbortSignal[]]): AbortSignal {
    const signals = (args.length === 1 && Array.isArray(args[0]) ? args[0] : args) as AbortSignal[];

    const controller = new AbortController();

    for (const signal of signals) {
        if (signal.aborted) {
            controller.abort((signal as any)?.reason);
            break;
        }

        signal.addEventListener("abort", () => controller.abort((signal as any)?.reason), {
            signal: controller.signal,
        });
    }

    return controller.signal;
}
