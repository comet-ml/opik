import { FormatDetector } from "../../types";

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
