import { describe, it, expect } from "vitest";
import {
  extractMustachePlaceholders,
  validateTemplateVariables,
} from "@/prompt/validation";
import { PromptValidationError } from "@/prompt/errors";

describe("extractMustachePlaceholders", () => {
  it("extracts single placeholder", () => {
    const template = "Hello {{name}}!";
    const placeholders = extractMustachePlaceholders(template);

    expect(placeholders).toEqual(new Set(["name"]));
  });

  it("extracts multiple placeholders", () => {
    const template = "Hello {{name}}, your score is {{score}} out of {{total}}";
    const placeholders = extractMustachePlaceholders(template);

    expect(placeholders).toEqual(new Set(["name", "score", "total"]));
  });

  it("extracts placeholders with whitespace", () => {
    const template = "Hello {{ name }}, score: {{ score }}";
    const placeholders = extractMustachePlaceholders(template);

    expect(placeholders).toEqual(new Set(["name", "score"]));
  });

  it("handles duplicate placeholders", () => {
    const template = "{{name}} and {{name}} are the same";
    const placeholders = extractMustachePlaceholders(template);

    expect(placeholders).toEqual(new Set(["name"]));
  });

  it("returns empty set for template without placeholders", () => {
    const template = "Just plain text with no placeholders";
    const placeholders = extractMustachePlaceholders(template);

    expect(placeholders).toEqual(new Set());
  });

  it("ignores invalid placeholder syntax", () => {
    const template = "{{valid}} but {invalid} and {also-invalid}";
    const placeholders = extractMustachePlaceholders(template);

    expect(placeholders).toEqual(new Set(["valid"]));
  });

  it("extracts unescaped variables (triple mustache)", () => {
    const template = "{{outer}} and {{{triple}}}";
    const placeholders = extractMustachePlaceholders(template);

    expect(placeholders).toEqual(new Set(["outer", "triple"]));
  });

  it("extracts top-level section markers only", () => {
    const template = "{{#items}}{{name}}{{/items}}";
    const placeholders = extractMustachePlaceholders(template);

    // Only top-level sections extracted; nested variables ignored
    expect(placeholders).toEqual(new Set(["items"]));
  });

  it("extracts inverted sections (control structures need variables)", () => {
    const template = "{{^empty}}Not empty{{/empty}}";
    const placeholders = extractMustachePlaceholders(template);

    // Inverted sections still need the variable to check if it's falsy
    expect(placeholders).toEqual(new Set(["empty"]));
  });

  it("filters out comments", () => {
    const template = "{{!This is a comment}} Hello {{name}}";
    const placeholders = extractMustachePlaceholders(template);

    // Should only extract "name", not the comment
    expect(placeholders).toEqual(new Set(["name"]));
  });

  it("extracts unescaped syntax with ampersand", () => {
    const template = "{{&html}} {{name}}";
    const placeholders = extractMustachePlaceholders(template);

    // Unescaped variables ({{&html}}) need data, so both are extracted
    expect(placeholders).toEqual(new Set(["html", "name"]));
  });

  it("handles complex template with mixed control structures", () => {
    const template =
      "{{#users}}{{name}} {{^roles}}No roles{{/roles}}{{/users}}";
    const placeholders = extractMustachePlaceholders(template);

    // Only top-level sections extracted (users); nested variables (name, roles) ignored
    expect(placeholders).toEqual(new Set(["users"]));
  });
});

describe("validateTemplateVariables", () => {
  describe("mustache templates", () => {
    it("passes validation when variables match placeholders", () => {
      const template = "Hello {{name}}, score: {{score}}";
      const variables = { name: "Alice", score: 95 };

      expect(() =>
        validateTemplateVariables(template, variables, "mustache")
      ).not.toThrow();
    });

    it("throws error when missing required variables", () => {
      const template = "Hello {{name}}, score: {{score}}";
      const variables = { name: "Alice" };

      expect(() =>
        validateTemplateVariables(template, variables, "mustache")
      ).toThrow(PromptValidationError);

      try {
        validateTemplateVariables(template, variables, "mustache");
      } catch (error) {
        expect(error).toBeInstanceOf(PromptValidationError);
        if (error instanceof PromptValidationError) {
          expect(error.message).toContain("Missing required variables: score");
          expect(error.message).toContain(
            "Template placeholders: {name, score}"
          );
          expect(error.message).toContain("Provided variables: {name}");
        }
      }
    });

    it("allows extra variables (may be used in conditional sections)", () => {
      const template = "Hello {{name}}";
      const variables = { name: "Alice", extra: "unused" };

      // Extra variables are allowed as they may be used in conditional sections
      expect(() =>
        validateTemplateVariables(template, variables, "mustache")
      ).not.toThrow();
    });

    it("throws error for missing variables (extra variables allowed)", () => {
      const template = "Hello {{name}}, score: {{score}}";
      const variables = { name: "Alice", extra: "unused" };

      expect(() =>
        validateTemplateVariables(template, variables, "mustache")
      ).toThrow(PromptValidationError);

      try {
        validateTemplateVariables(template, variables, "mustache");
      } catch (error) {
        expect(error).toBeInstanceOf(PromptValidationError);
        if (error instanceof PromptValidationError) {
          expect(error.message).toContain("Missing required variables: score");
          // Extra variables should not be mentioned in error
          expect(error.message).not.toContain("Extra unused variables");
        }
      }
    });

    it("handles template with no placeholders", () => {
      const template = "Just plain text";
      const variables = {};

      expect(() =>
        validateTemplateVariables(template, variables, "mustache")
      ).not.toThrow();
    });
  });

  describe("jinja2 templates", () => {
    it("skips validation for jinja2 templates", () => {
      const template = "Hello {{ name }}, score: {{ score }}";
      const variables = { name: "Alice" };

      expect(() =>
        validateTemplateVariables(template, variables, "jinja2")
      ).not.toThrow();
    });
  });

  describe("edge cases", () => {
    it("handles empty template", () => {
      const template = "";
      const variables = {};

      expect(() =>
        validateTemplateVariables(template, variables, "mustache")
      ).not.toThrow();
    });

    it("allows variables for empty template (extra variables permitted)", () => {
      const template = "";
      const variables = { name: "Alice" };

      // Extra variables are allowed, even for empty templates
      expect(() =>
        validateTemplateVariables(template, variables, "mustache")
      ).not.toThrow();
    });
  });
});
