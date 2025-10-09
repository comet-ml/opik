import mustache from "mustache";

export const getPromptMustacheTags = (template: string) => {
  const parsedTemplate = mustache.parse(template);

  return parsedTemplate
    .filter(([type]) => type === "name" || type === "&")
    .map(([, name]) => name);
};

export const safelyGetPromptMustacheTags = (template: string) => {
  try {
    const parsedTemplate = mustache.parse(template);

    return parsedTemplate
      .filter(
        ([type]) =>
          type === "name" || type === "&" || type === "#" || type === "^",
      )
      .map(([, name]) => name);
  } catch (error) {
    return false;
  }
};
