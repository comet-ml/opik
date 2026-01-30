import { execFileSync } from "child_process";
import { existsSync, mkdirSync, appendFileSync, readFileSync } from "fs";
import { join, relative } from "path";

export interface Range {
  start_line: number;
  end_line: number;
  content_hash?: string;
  contributor?: {
    type: "human" | "ai" | "mixed" | "unknown";
    model_id?: string;
  };
}

export interface Conversation {
  url?: string;
  contributor?: {
    type: "human" | "ai" | "mixed" | "unknown";
    model_id?: string;
  };
  ranges: Range[];
  related?: { type: string; url: string }[];
}

export interface FileEntry {
  path: string;
  conversations: Conversation[];
}

export type VcsType = "git" | "jj" | "hg" | "svn";

export interface Vcs {
  type: VcsType;
  revision: string;
}

export interface TraceRecord {
  version: string;
  id: string;
  timestamp: string;
  vcs?: Vcs;
  tool?: { name: string; version?: string };
  files: FileEntry[];
  metadata?: Record<string, unknown>;
}

export interface FileEdit {
  old_string: string;
  new_string: string;
  range?: { start_line_number: number; end_line_number: number; start_column: number; end_column: number };
}

const TRACE_PATH = ".agent-trace/traces.jsonl";

export function getWorkspaceRoot(): string {
  return process.env.CURSOR_PROJECT_DIR
    ?? process.env.CLAUDE_PROJECT_DIR
    ?? execFileSync("git", ["rev-parse", "--show-toplevel"], { encoding: "utf-8" }).trim()
    ?? process.cwd();
}

export function getToolInfo(): { name: string; version?: string } {
  if (process.env.CURSOR_VERSION) return { name: "cursor", version: process.env.CURSOR_VERSION };
  if (process.env.CLAUDE_PROJECT_DIR) return { name: "claude-code" };
  return { name: "unknown" };
}

export function getVcsInfo(cwd: string): Vcs | undefined {
  try {
    const revision = execFileSync("git", ["rev-parse", "HEAD"], { cwd, encoding: "utf-8" }).trim();
    return { type: "git", revision };
  } catch {
    return undefined;
  }
}

export function toRelativePath(absolutePath: string, root: string): string {
  return absolutePath.startsWith(root) ? relative(root, absolutePath) : absolutePath;
}

export function normalizeModelId(model?: string): string | undefined {
  if (!model) return undefined;
  if (model.includes("/")) return model;
  const prefixes: Record<string, string> = {
    "claude-": "anthropic",
    "gpt-": "openai",
    "o1": "openai",
    "o3": "openai",
    "gemini-": "google",
  };
  for (const [prefix, provider] of Object.entries(prefixes)) {
    if (model.startsWith(prefix)) return `${provider}/${model}`;
  }
  return model;
}

export interface RangePosition {
  start_line: number;
  end_line: number;
}

export function computeRangePositions(edits: FileEdit[], fileContent?: string): RangePosition[] {
  return edits
    .filter((e) => e.new_string)
    .map((edit) => {
      if (edit.range) {
        return {
          start_line: edit.range.start_line_number,
          end_line: edit.range.end_line_number,
        };
      }
      const lineCount = edit.new_string.split("\n").length;
      if (fileContent) {
        const idx = fileContent.indexOf(edit.new_string);
        if (idx !== -1) {
          const startLine = fileContent.substring(0, idx).split("\n").length;
          return { start_line: startLine, end_line: startLine + lineCount - 1 };
        }
      }
      return { start_line: 1, end_line: lineCount };
    });
}

export type ContributorType = "human" | "ai" | "mixed" | "unknown";

export function createTrace(
  type: ContributorType,
  filePath: string,
  opts: {
    model?: string;
    rangePositions?: RangePosition[];
    transcript?: string | null;
    metadata?: Record<string, unknown>;
  } = {}
): TraceRecord {
  const root = getWorkspaceRoot();
  const modelId = normalizeModelId(opts.model);
  const conversationUrl = opts.transcript ? `file://${opts.transcript}` : undefined;

  const ranges: Range[] = opts.rangePositions?.length
    ? opts.rangePositions.map((pos) => ({ ...pos }))
    : [{ start_line: 1, end_line: 1 }];

  const conversation: Conversation = {
    url: conversationUrl,
    contributor: { type, model_id: modelId },
    ranges,
  };

  return {
    version: "1.0",
    id: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
    vcs: getVcsInfo(root),
    tool: getToolInfo(),
    files: [
      {
        path: toRelativePath(filePath, root),
        conversations: [conversation],
      },
    ],
    metadata: opts.metadata,
  };
}

export function appendTrace(trace: TraceRecord): void {
  const root = getWorkspaceRoot();
  const filePath = join(root, TRACE_PATH);
  const dir = join(root, ".agent-trace");
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  appendFileSync(filePath, JSON.stringify(trace) + "\n", "utf-8");
}

export function tryReadFile(path: string): string | undefined {
  try {
    return readFileSync(path, "utf-8");
  } catch {
    return undefined;
  }
}
