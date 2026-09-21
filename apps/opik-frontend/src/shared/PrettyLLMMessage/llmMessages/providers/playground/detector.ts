import { FormatDetector } from "../../types";

/**
 * The Opik playground logs its runs from the frontend and records the completion as one plain
 * string under `output`, at both trace and span level. The input side is plain OpenAI `messages`,
 * so only the output half is claimed here.
 */
export const getPlaygroundOutputText = (data: unknown): string | undefined => {
  if (!data || typeof data !== "object") return undefined;

  const output = (data as Record<string, unknown>).output;
  return typeof output === "string" ? output : undefined;
};

export const detectPlaygroundFormat: FormatDetector = (
  data,
  prettifyConfig,
) => {
  if (prettifyConfig?.fieldType !== "output") return false;

  return getPlaygroundOutputText(data) !== undefined;
};
