import type { Readable, Writable } from "readable-stream";

import { EventCallback, StreamWrapper } from "./chooseStreamWrapper";

export class NodePre18StreamWrapper implements StreamWrapper<Writable, Buffer> {
    private readableStream: Readable;
    private encoding: string | undefined;

    constructor(readableStream: Readable) {
        this.readableStream = readableStream;
    }

    public on(event: string, callback: EventCallback): void {
        this.readableStream.on(event, callback);
    }

    public off(event: string, callback: EventCallback): void {
        this.readableStream.off(event, callback);
    }

    public pipe(dest: Writable): Writable {
        this.readableStream.pipe(dest);
        return dest;
    }

    public pipeTo(dest: Writable): Writable {
        return this.pipe(dest);
    }

    public unpipe(dest?: Writable): void {
        if (dest) {
            this.readableStream.unpipe(dest);
        } else {
            this.readableStream.unpipe();
        }
    }

    public destroy(error?: Error): void {
        this.readableStream.destroy(error);
    }

    public pause(): void {
        this.readableStream.pause();
    }

    public resume(): void {
        this.readableStream.resume();
    }

    public get isPaused(): boolean {
        return this.readableStream.isPaused();
    }

    public async read(): Promise<Buffer | undefined> {
        return new Promise((resolve, reject) => {
            const chunk = this.readableStream.read();
            if (chunk) {
                resolve(chunk);
            } else {
                this.readableStream.once("readable", () => {
                    const chunk = this.readableStream.read();
                    resolve(chunk);
                });
                this.readableStream.once("error", reject);
            }
        });
    }

    public setEncoding(encoding?: string): void {
        this.readableStream.setEncoding(encoding as BufferEncoding);
        this.encoding = encoding;
    }

    public async text(): Promise<string> {
        const chunks: Uint8Array[] = [];
        const encoder = new TextEncoder();
        this.readableStream.setEncoding((this.encoding || "utf-8") as BufferEncoding);

        for await (const chunk of this.readableStream) {
            chunks.push(encoder.encode(chunk));
        }

        const decoder = new TextDecoder(this.encoding || "utf-8");
        return decoder.decode(Buffer.concat(chunks));
    }

    public async json<T>(): Promise<T> {
        const text = await this.text();
        return JSON.parse(text);
    }

    public [Symbol.asyncIterator](): AsyncIterableIterator<Buffer> {
        const readableStream = this.readableStream;
        const iterator = readableStream[Symbol.asyncIterator]();

        // Create and return an async iterator that yields buffers
        return {
            async next(): Promise<IteratorResult<Buffer>> {
                const { value, done } = await iterator.next();
                return { value: value as Buffer, done };
            },
            [Symbol.asyncIterator]() {
                return this;
            },
        };
    }
}
