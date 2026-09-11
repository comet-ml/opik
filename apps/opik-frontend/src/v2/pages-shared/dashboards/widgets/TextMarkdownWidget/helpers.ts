const DEFAULT_TITLE = "Text";

const calculateTextMarkdownTitle = (): string => {
  return DEFAULT_TITLE;
};

export const widgetHelpers = {
  getDefaultConfig: () => ({}),
  calculateTitle: calculateTextMarkdownTitle,
};
