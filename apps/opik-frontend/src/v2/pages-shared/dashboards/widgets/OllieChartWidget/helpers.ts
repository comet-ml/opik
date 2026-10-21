const DEFAULT_TITLE = "Ollie chart";

export const widgetHelpers = {
  getDefaultConfig: () => ({}),
  calculateTitle: (config: Record<string, unknown>) =>
    (config.description as string | undefined) || DEFAULT_TITLE,
};
