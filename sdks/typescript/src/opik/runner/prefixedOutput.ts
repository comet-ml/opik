import type { LocalRunnerLogEntry } from "@/rest_api/api/types/LocalRunnerLogEntry";
import { runnerJobStorage } from "./context";

type WriteFn = (
  chunk: Uint8Array | string,
  encoding?: BufferEncoding | ((err?: Error) => void),
  cb?: (err?: Error) => void
) => boolean;

const GRADIENT_START = [245, 166, 35]; // #F5A623
const GRADIENT_END = [224, 62, 45]; // #E03E2D
const CYCLE_LENGTH = 20;

let originalStdoutWrite: WriteFn | null = null;
let originalStderrWrite: WriteFn | null = null;
let lineCounter = 0;
let isTTY = false;

const jobLogs = new Map<string, LocalRunnerLogEntry[]>();

function lerp(a: number, b: number, t: number): number {
  return Math.round(a + (b - a) * t);
}

function prefixForLine(): string {
  const t = (lineCounter % CYCLE_LENGTH) / (CYCLE_LENGTH - 1);
  const r = lerp(GRADIENT_START[0], GRADIENT_END[0], t);
  const g = lerp(GRADIENT_START[1], GRADIENT_END[1], t);
  const b = lerp(GRADIENT_START[2], GRADIENT_END[2], t);
  lineCounter++;
  return `\x1b[38;2;${r};${g};${b}m \u2503\x1b[0m `;
}

function makeInterceptor(
  original: WriteFn,
  stream: "stdout" | "stderr"
): WriteFn {
  return function interceptedWrite(
    chunk: Uint8Array | string,
    encoding?: BufferEncoding | ((err?: Error) => void),
    cb?: (err?: Error) => void
  ): boolean {
    const text =
      typeof chunk === "string" ? chunk : Buffer.from(chunk).toString("utf-8");

    const ctx = runnerJobStorage.getStore();
    if (ctx) {
      let entries = jobLogs.get(ctx.jobId);
      if (!entries) {
        entries = [];
        jobLogs.set(ctx.jobId, entries);
      }
      entries.push({ stream, text });
    }

    if (isTTY) {
      const lines = text.split("\n");
      let prefixed = "";
      for (let i = 0; i < lines.length; i++) {
        if (i > 0) prefixed += "\n";
        if (lines[i].length > 0 || i < lines.length - 1) {
          prefixed += prefixForLine() + lines[i];
        }
      }
      return original.call(
        stream === "stdout" ? process.stdout : process.stderr,
        prefixed,
        encoding as BufferEncoding,
        cb
      );
    }

    return original.call(
      stream === "stdout" ? process.stdout : process.stderr,
      chunk,
      encoding as BufferEncoding,
      cb
    );
  };
}

export function installPrefixedOutput(): void {
  if (originalStdoutWrite) return;

  isTTY =
    typeof process.stdout?.isTTY === "boolean" && process.stdout.isTTY;

  originalStdoutWrite = process.stdout.write.bind(
    process.stdout
  ) as WriteFn;
  originalStderrWrite = process.stderr.write.bind(
    process.stderr
  ) as WriteFn;

  process.stdout.write = makeInterceptor(
    originalStdoutWrite,
    "stdout"
  ) as typeof process.stdout.write;
  process.stderr.write = makeInterceptor(
    originalStderrWrite,
    "stderr"
  ) as typeof process.stderr.write;
}

export function uninstallPrefixedOutput(): void {
  if (!originalStdoutWrite || !originalStderrWrite) return;

  process.stdout.write = originalStdoutWrite as typeof process.stdout.write;
  process.stderr.write = originalStderrWrite as typeof process.stderr.write;

  originalStdoutWrite = null;
  originalStderrWrite = null;
}

export function getAndClearJobLogs(jobId: string): LocalRunnerLogEntry[] {
  const entries = jobLogs.get(jobId) ?? [];
  jobLogs.delete(jobId);
  return entries;
}
