import mustache from "mustache";

export const getPromptMustacheTags = (template: string) => {
  const parsedTemplate = mustache.parse(template);

  return parsedTemplate
    .filter(([type]) => type === "name")
    .map(([, name]) => name);
};
