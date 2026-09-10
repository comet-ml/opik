import startCase from "lodash/startCase";
import { UsageData } from "@/types/shared";

export type MessageUsage =
  | Partial<UsageData>
  | Record<string, number | undefined>;

export const numericUsage = (usage?: MessageUsage): Record<string, number> =>
  Object.fromEntries(
    Object.entries(usage ?? {}).filter(
      (entry): entry is [string, number] =>
        typeof entry[1] === "number" && Number.isFinite(entry[1]),
    ),
  );

const USAGE_LABELS: Record<string, string> = {
  prompt_tokens: "Input tokens",
  completion_tokens: "Output tokens",
  total_tokens: "Total tokens",
  cache_read_input_tokens: "Cache read tokens",
  cache_creation_input_tokens: "Cache write tokens",
  reasoning_tokens: "Reasoning tokens",
  "prompt_tokens_details.audio_tokens": "Input audio tokens",
  "completion_tokens_details.audio_tokens": "Output audio tokens",
};
const ORIGINAL_USAGE_ALIASES: Record<string, string> = {
  cache_read_input_tokens: "cache_read_input_tokens",
  cache_creation_input_tokens: "cache_creation_input_tokens",
  "prompt_tokens_details.cached_tokens": "cache_read_input_tokens",
  "input_tokens_details.cached_tokens": "cache_read_input_tokens",
  "completion_tokens_details.reasoning_tokens": "reasoning_tokens",
  "output_tokens_details.reasoning_tokens": "reasoning_tokens",
  "prompt_tokens_details.audio_tokens": "prompt_tokens_details.audio_tokens",
  "completion_tokens_details.audio_tokens":
    "completion_tokens_details.audio_tokens",
};

export const getDisplayUsage = (usage?: MessageUsage) => {
  const values = numericUsage(usage);
  const display = Object.fromEntries(
    Object.entries(values).filter(
      ([key]) => !key.startsWith("original_usage."),
    ),
  );
  for (const [original, canonical] of Object.entries(ORIGINAL_USAGE_ALIASES)) {
    const value = values[`original_usage.${original}`];
    if (display[canonical] === undefined && value !== undefined)
      display[canonical] = value;
  }
  return Object.entries(display).map(([key, value]) => ({
    key,
    value,
    label:
      USAGE_LABELS[key] ??
      startCase(key)
        .toLowerCase()
        .replace(/^./, (letter) => letter.toUpperCase()),
  }));
};
