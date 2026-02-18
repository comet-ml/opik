import { FormatDetector } from "../../types";

const LANGCHAIN_MESSAGE_TYPES = [
  "human",
  "ai",
  "system",
  "tool",
  "function",
  "chat",
];

const isLangChainMessage = (msg: unknown): boolean => {
  if (!msg || typeof msg !== "object") return false;
  const m = msg as Record<string, unknown>;

  if (!m.type || typeof m.type !== "string") return false;
  if (!LANGCHAIN_MESSAGE_TYPES.includes(m.type)) return false;

  return true;
};

const hasLangChainFlatMessages = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  if (!Array.isArray(d.messages)) return false;
  if (d.messages.length === 0) return false;

  // Flat format: first item is NOT an array
  if (Array.isArray(d.messages[0])) return false;

  return d.messages.every(isLangChainMessage);
};

const hasLangChainBatchedMessages = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  if (!Array.isArray(d.messages)) return false;
  if (d.messages.length === 0) return false;

  // Batched format: first item IS an array
  const firstBatch = d.messages[0];
  if (!Array.isArray(firstBatch)) return false;
  if (firstBatch.length === 0) return false;

  return firstBatch.every(isLangChainMessage);
};

const hasGenerationsOutput = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  if (!Array.isArray(d.generations)) return false;
  if (d.generations.length !== 1) return false;

  const firstBatch = d.generations[0];
  if (!Array.isArray(firstBatch)) return false;
  if (firstBatch.length === 0) return false;

  return firstBatch.every((gen: unknown) => {
    if (!gen || typeof gen !== "object") return false;
    const g = gen as Record<string, unknown>;
    return typeof g.text === "string";
  });
};

export const detectLangChainFormat: FormatDetector = (data, prettifyConfig) => {
  if (!data) return false;

  const isInput = prettifyConfig?.fieldType === "input";
  const isOutput = prettifyConfig?.fieldType === "output";

  if (!isInput && !isOutput) return false;

  if (isInput) {
    if (hasLangChainFlatMessages(data)) return true;
    if (hasLangChainBatchedMessages(data)) return true;
  }

  if (isOutput) {
    if (hasLangChainFlatMessages(data)) return true;
    if (hasGenerationsOutput(data)) return true;
  }

  return false;
};
