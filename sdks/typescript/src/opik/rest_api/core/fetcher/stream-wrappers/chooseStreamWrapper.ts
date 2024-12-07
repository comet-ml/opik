import type { Readable } from "readable-stream";
import { RUNTIME } from "../../runtime";

export type EventCallback = (data?: any) => void;

export interface StreamWrapper<WritableStream, ReadFormat> {
    setEncoding(encoding?: string): void;
    on(event: string, callback: EventCallback): void;
    off(event: string, callback: EventCallback): void;
    pipe(dest: WritableStream): WritableStream;
    pipeTo(dest: WritableStream): WritableStream;
    unpipe(dest?: WritableStream): void;
    destroy(error?: Error): void;
    pause(): void;
    resume(): void;
    get isPaused(): boolean;
    read(): Promise<ReadFormat | undefined>;
    text(): Promise<string>;
    json<T>(): Promise<T>;
    [Symbol.asyncIterator](): AsyncIterableIterator<ReadFormat>;
}

export async function chooseStreamWrapper(responseBody: any): Promise<Promise<StreamWrapper<any, any>>> {
    if (RUNTIME.type === "node" && RUNTIME.parsedVersion != null && RUNTIME.parsedVersion >= 18) {
        return new (await import("./Node18UniversalStreamWrapper")).Node18UniversalStreamWrapper(
            responseBody as ReadableStream
        );
    } else if (RUNTIME.type !== "node" && typeof fetch === "function") {
        return new (await import("./UndiciStreamWrapper")).UndiciStreamWrapper(responseBody as ReadableStream);
    } else {
        return new (await import("./NodePre18StreamWrapper")).NodePre18StreamWrapper(responseBody as Readable);
    }
}
