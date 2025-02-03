import { StreamWrapper } from "./chooseStreamWrapper";

type EventCallback = (data?: any) => void;

export class UndiciStreamWrapper<ReadFormat extends Uint8Array | Uint16Array | Uint32Array>
    implements StreamWrapper<UndiciStreamWrapper<ReadFormat> | WritableStream<ReadFormat>, ReadFormat>
{
    private readableStream: ReadableStream<ReadFormat>;
    private reader: ReadableStreamDefaultReader<ReadFormat>;
    private events: Record<string, EventCallback[] | undefined>;
    private paused: boolean;
    private resumeCallback: ((value?: unknown) => void) | null;
    private encoding: string | null;

    constructor(readableStream: ReadableStream<ReadFormat>) {
        this.readableStream = readableStream;
        this.reader = this.readableStream.getReader();
        this.events = {
            data: [],
            end: [],
            error: [],
            readable: [],
            close: [],
            pause: [],
            resume: [],
        };
        this.paused = false;
        this.resumeCallback = null;
        this.encoding = null;
    }

    public on(event: string, callback: EventCallback): void {
        this.events[event]?.push(callback);
    }

    public off(event: string, callback: EventCallback): void {
        this.events[event] = this.events[event]?.filter((cb) => cb !== callback);
    }

    public pipe(
        dest: UndiciStreamWrapper<ReadFormat> | WritableStream<ReadFormat>,
    ): UndiciStreamWrapper<ReadFormat> | WritableStream<ReadFormat> {
        this.on("data", (chunk) => {
            if (dest instanceof UndiciStreamWrapper) {
                dest._write(chunk);
            } else {
                const writer = dest.getWriter();
                writer.write(chunk).then(() => writer.releaseLock());
            }
        });

        this.on("end", () => {
            if (dest instanceof UndiciStreamWrapper) {
                dest._end();
            } else {
                const writer = dest.getWriter();
                writer.close();
            }
        });

        this.on("error", (error) => {
            if (dest instanceof UndiciStreamWrapper) {
                dest._error(error);
            } else {
                const writer = dest.getWriter();
                writer.abort(error);
            }
        });

        this._startReading();

        return dest;
    }

    public pipeTo(
        dest: UndiciStreamWrapper<ReadFormat> | WritableStream<ReadFormat>,
    ): UndiciStreamWrapper<ReadFormat> | WritableStream<ReadFormat> {
        return this.pipe(dest);
    }

    public unpipe(dest: UndiciStreamWrapper<ReadFormat> | WritableStream): void {
        this.off("data", (chunk) => {
            if (dest instanceof UndiciStreamWrapper) {
                dest._write(chunk);
            } else {
                const writer = dest.getWriter();
                writer.write(chunk).then(() => writer.releaseLock());
            }
        });

        this.off("end", () => {
            if (dest instanceof UndiciStreamWrapper) {
                dest._end();
            } else {
                const writer = dest.getWriter();
                writer.close();
            }
        });

        this.off("error", (error) => {
            if (dest instanceof UndiciStreamWrapper) {
                dest._error(error);
            } else {
                const writer = dest.getWriter();
                writer.abort(error);
            }
        });
    }

    public destroy(error?: Error): void {
        this.reader
            .cancel(error)
            .then(() => {
                this._emit("close");
            })
            .catch((err) => {
                this._emit("error", err);
            });
    }

    public pause(): void {
        this.paused = true;
        this._emit("pause");
    }

    public resume(): void {
        if (this.paused) {
            this.paused = false;
            this._emit("resume");
            if (this.resumeCallback) {
                this.resumeCallback();
                this.resumeCallback = null;
            }
        }
    }

    public get isPaused(): boolean {
        return this.paused;
    }

    public async read(): Promise<ReadFormat | undefined> {
        if (this.paused) {
            await new Promise((resolve) => {
                this.resumeCallback = resolve;
            });
        }
        const { done, value } = await this.reader.read();
        if (done) {
            return undefined;
        }
        return value;
    }

    public setEncoding(encoding: string): void {
        this.encoding = encoding;
    }

    public async text(): Promise<string> {
        const chunks: BlobPart[] = [];

        while (true) {
            const { done, value } = await this.reader.read();
            if (done) {
                break;
            }
            if (value) {
                chunks.push(value);
            }
        }

        const decoder = new TextDecoder(this.encoding || "utf-8");
        return decoder.decode(await new Blob(chunks).arrayBuffer());
    }

    public async json<T>(): Promise<T> {
        const text = await this.text();
        return JSON.parse(text);
    }

    private _write(chunk: ReadFormat): void {
        this._emit("data", chunk);
    }

    private _end(): void {
        this._emit("end");
    }

    private _error(error: any): void {
        this._emit("error", error);
    }

    private _emit(event: string, data?: any): void {
        if (this.events[event]) {
            for (const callback of this.events[event] || []) {
                callback(data);
            }
        }
    }

    private async _startReading(): Promise<void> {
        try {
            this._emit("readable");
            while (true) {
                if (this.paused) {
                    await new Promise((resolve) => {
                        this.resumeCallback = resolve;
                    });
                }
                const { done, value } = await this.reader.read();
                if (done) {
                    this._emit("end");
                    this._emit("close");
                    break;
                }
                if (value) {
                    this._emit("data", value);
                }
            }
        } catch (error) {
            this._emit("error", error);
        }
    }

    [Symbol.asyncIterator](): AsyncIterableIterator<ReadFormat> {
        return {
            next: async () => {
                if (this.paused) {
                    await new Promise((resolve) => {
                        this.resumeCallback = resolve;
                    });
                }
                const { done, value } = await this.reader.read();
                if (done) {
                    return { done: true, value: undefined };
                }
                return { done: false, value };
            },
            [Symbol.asyncIterator]() {
                return this;
            },
        };
    }
}
