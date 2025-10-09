import { describe, it, expect } from "vitest";
import { getPromptMustacheTags, safelyGetPromptMustacheTags } from "./prompt";

describe("getPromptMustacheTags", () => {
  it("should extract escaped variables", () => {
    const template = "Hello {{name}}, welcome to {{app}}!";
    const result = getPromptMustacheTags(template);
    expect(result).toEqual(["name", "app"]);
  });

  it("should extract unescaped variables with triple braces", () => {
    const template = "Here is an image: {{{image_url}}}";
    const result = getPromptMustacheTags(template);
    expect(result).toEqual(["image_url"]);
  });

  it("should extract unescaped variables with ampersand", () => {
    const template = "Here is an image: {{&image_url}}";
    const result = getPromptMustacheTags(template);
    expect(result).toEqual(["image_url"]);
  });

  it("should extract mixed escaped and unescaped variables", () => {
    const template =
      "Hello {{name}}, here is an image: {{{image_url}}} and another: {{&ampersand_var}}";
    const result = getPromptMustacheTags(template);
    expect(result).toEqual(["name", "image_url", "ampersand_var"]);
  });

  it("should handle empty template", () => {
    const template = "";
    const result = getPromptMustacheTags(template);
    expect(result).toEqual([]);
  });

  it("should handle template with no variables", () => {
    const template = "Just plain text with no variables";
    const result = getPromptMustacheTags(template);
    expect(result).toEqual([]);
  });
});

describe("safelyGetPromptMustacheTags", () => {
  it("should extract variables from valid template", () => {
    const template = "Hello {{name}}, image: {{{image_url}}}";
    const result = safelyGetPromptMustacheTags(template);
    expect(result).toEqual(["name", "image_url"]);
  });

  it("should return false for invalid template", () => {
    const template = "Invalid {{unclosed template";
    const result = safelyGetPromptMustacheTags(template);
    expect(result).toBe(false);
  });

  it("should handle sections and inverted sections", () => {
    const template =
      "{{#section}}content{{/section}} and {{^inverted}}default{{/inverted}}";
    const result = safelyGetPromptMustacheTags(template);
    expect(result).toEqual(["section", "inverted"]);
  });
});
