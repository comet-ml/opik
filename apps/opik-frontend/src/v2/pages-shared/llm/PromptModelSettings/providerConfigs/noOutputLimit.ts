export const NO_OUTPUT_LIMIT_TOOLTIP =
  "Set to 0 for no limit (the provider's own default applies).";

export const getNoOutputLimitHint = (maxTokens?: number | null) =>
  maxTokens != null && Math.floor(maxTokens) === 0
    ? "No limit: the provider's own default applies."
    : undefined;
