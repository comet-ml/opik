import { describe, it, expect } from "vitest";
import { convertOptimizationVariableFormat } from "./optimizations";

describe("convertOptimizationVariableFormat", () => {
  describe("string content", () => {
    it("should convert single variable from {var} to {{var}}", () => {
      const input = "Answer the {question}";
      const expected = "Answer the {{question}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should convert multiple variables in a string", () => {
      const input = "Use {context} to answer {question}";
      const expected = "Use {{context}} to answer {{question}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables at the start of string", () => {
      const input = "{question} is the question";
      const expected = "{{question}} is the question";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables at the end of string", () => {
      const input = "Answer this: {question}";
      const expected = "Answer this: {{question}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle multiple consecutive variables", () => {
      const input = "{var1}{var2}{var3}";
      const expected = "{{var1}}{{var2}}{{var3}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with underscores", () => {
      const input = "Use {user_input} and {system_context}";
      const expected = "Use {{user_input}} and {{system_context}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with numbers", () => {
      const input = "Item {item1} and {item2}";
      const expected = "Item {{item1}} and {{item2}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should not convert already-converted variables", () => {
      const input = "Already converted {{variable}}";
      const expected = "Already converted {{variable}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle mixed converted and unconverted variables", () => {
      const input = "Convert {this} but not {{that}}";
      const expected = "Convert {{this}} but not {{that}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle empty string", () => {
      const input = "";
      const expected = "";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle string with no variables", () => {
      const input = "No variables here";
      const expected = "No variables here";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle string with only curly braces (no content)", () => {
      const input = "Empty braces {} should not be converted";
      const expected = "Empty braces {} should not be converted";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with dots (object notation)", () => {
      const input = "Access {user.name} and {data.value}";
      const expected = "Access {{user.name}} and {{data.value}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with hyphens", () => {
      const input = "Use {user-input} here";
      const expected = "Use {{user-input}} here";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle complex real-world example", () => {
      const input =
        "Given the context: {context}\n\nAnswer the following question: {question}\n\nProvide a detailed response.";
      const expected =
        "Given the context: {{context}}\n\nAnswer the following question: {{question}}\n\nProvide a detailed response.";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });
  });

  describe("multimodal content (array)", () => {
    it("should convert text parts in multimodal content", () => {
      const input = [
        { type: "text", text: "Describe {image}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/img.jpg" },
        },
      ];
      const expected = [
        { type: "text", text: "Describe {{image}}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/img.jpg" },
        },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle multiple text parts with variables", () => {
      const input = [
        { type: "text", text: "First {var1}" },
        { type: "text", text: "Second {var2}" },
      ];
      const expected = [
        { type: "text", text: "First {{var1}}" },
        { type: "text", text: "Second {{var2}}" },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should preserve non-text parts unchanged", () => {
      const input = [
        {
          type: "image_url",
          image_url: { url: "https://example.com/img.jpg" },
        },
        {
          type: "video_url",
          video_url: { url: "https://example.com/vid.mp4" },
        },
        {
          type: "audio_url",
          audio_url: { url: "https://example.com/audio.mp3" },
        },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(input);
    });

    it("should handle mixed content with text and media", () => {
      const input = [
        { type: "text", text: "Analyze {data}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/chart.png" },
        },
        { type: "text", text: "Provide {analysis}" },
      ];
      const expected = [
        { type: "text", text: "Analyze {{data}}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/chart.png" },
        },
        { type: "text", text: "Provide {{analysis}}" },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle empty array", () => {
      const input: unknown[] = [];
      expect(convertOptimizationVariableFormat(input)).toEqual([]);
    });

    it("should handle array with text part without variables", () => {
      const input = [{ type: "text", text: "No variables here" }];
      const expected = [{ type: "text", text: "No variables here" }];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle text part with already-converted variables", () => {
      const input = [{ type: "text", text: "Already {{converted}}" }];
      const result = convertOptimizationVariableFormat(input) as Array<{
        type: string;
        text: string;
      }>;
      expect(result).toHaveLength(1);
      expect(result[0].type).toBe("text");
      expect(result[0].text).toBe("Already {{converted}}");
    });

    it("should preserve additional properties on text parts", () => {
      const input = [
        {
          type: "text",
          text: "Convert {this}",
          customProp: "value",
          anotherProp: 123,
        },
      ];
      const expected = [
        {
          type: "text",
          text: "Convert {{this}}",
          customProp: "value",
          anotherProp: 123,
        },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle malformed parts gracefully", () => {
      const input = [
        { type: "text", text: "Valid {var}" },
        { type: "text" }, // Missing text property
        null,
        undefined,
        { type: "other", content: "something" },
      ];
      const result = convertOptimizationVariableFormat(input) as unknown[];
      expect(result[0]).toEqual({ type: "text", text: "Valid {{var}}" });
      expect(result[1]).toEqual({ type: "text" });
      expect(result[2]).toBeNull();
      expect(result[3]).toBeUndefined();
      expect(result[4]).toEqual({ type: "other", content: "something" });
    });
  });

  describe("other types", () => {
    it("should return numbers unchanged", () => {
      const input = 123;
      expect(convertOptimizationVariableFormat(input)).toBe(123);
    });

    it("should return booleans unchanged", () => {
      expect(convertOptimizationVariableFormat(true)).toBe(true);
      expect(convertOptimizationVariableFormat(false)).toBe(false);
    });

    it("should return null unchanged", () => {
      expect(convertOptimizationVariableFormat(null)).toBeNull();
    });

    it("should return undefined unchanged", () => {
      expect(convertOptimizationVariableFormat(undefined)).toBeUndefined();
    });

    it("should return objects (non-array) unchanged", () => {
      const input = { key: "value", nested: { prop: "test" } };
      expect(convertOptimizationVariableFormat(input)).toEqual(input);
    });
  });

  describe("edge cases", () => {
    it("should handle nested braces correctly", () => {
      const input = "Code: {{nested {var} inside}}";
      // Should only convert the inner {var}
      const expected = "Code: {{nested {{var}} inside}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle special characters inside variables", () => {
      const input = "Use {var_with-special.chars}";
      const expected = "Use {{var_with-special.chars}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle very long variable names", () => {
      const longVarName = "a".repeat(100);
      const input = `Use {${longVarName}}`;
      const expected = `Use {{${longVarName}}}`;
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle strings with only variables", () => {
      const input = "{var}";
      const expected = "{{var}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle unicode characters in text", () => {
      const input = "Translate {text} to 中文 and {emoji} 🎉";
      const expected = "Translate {{text}} to 中文 and {{emoji}} 🎉";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle newlines and tabs in text", () => {
      const input = "Line 1 {var1}\n\tLine 2 {var2}\r\nLine 3";
      const expected = "Line 1 {{var1}}\n\tLine 2 {{var2}}\r\nLine 3";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });
  });
});
