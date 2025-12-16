import { describe, expect, it } from "vitest";
import { generateJsonSchemaFromData } from "./optimizations";
import { DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS } from "@/constants/optimizations";

describe("generateJsonSchemaFromData", () => {
  // Test null/undefined/invalid inputs
  describe("invalid inputs", () => {
    it("should return default schema for null input", () => {
      expect(generateJsonSchemaFromData(null)).toEqual(
        DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.SCHEMA,
      );
    });

    it("should return default schema for undefined input", () => {
      expect(generateJsonSchemaFromData(undefined)).toEqual(
        DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.SCHEMA,
      );
    });

    it("should return default schema for empty object", () => {
      expect(generateJsonSchemaFromData({})).toEqual({});
    });
  });

  // Test primitive values
  describe("primitive values", () => {
    it("should generate empty string for string values", () => {
      const input = { name: "John" };
      expect(generateJsonSchemaFromData(input)).toEqual({ name: "" });
    });

    it("should generate 0 for number values", () => {
      const input = { age: 30 };
      expect(generateJsonSchemaFromData(input)).toEqual({ age: 0 });
    });

    it("should generate false for boolean values", () => {
      const input = { isActive: true };
      expect(generateJsonSchemaFromData(input)).toEqual({ isActive: false });
    });

    it("should generate null for null values", () => {
      const input = { data: null };
      expect(generateJsonSchemaFromData(input)).toEqual({ data: null });
    });

    it("should handle multiple primitive types", () => {
      const input = {
        name: "Alice",
        age: 25,
        isAdmin: true,
        metadata: null,
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        name: "",
        age: 0,
        isAdmin: false,
        metadata: null,
      });
    });
  });

  // Test nested objects
  describe("nested objects", () => {
    it("should generate defaults for simple nested objects", () => {
      const input = {
        user: {
          name: "John",
          email: "john@example.com",
        },
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        user: {
          name: "",
          email: "",
        },
      });
    });

    it("should generate defaults for deeply nested objects", () => {
      const input = {
        level1: {
          level2: {
            level3: {
              value: "deep",
            },
          },
        },
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        level1: {
          level2: {
            level3: {
              value: "",
            },
          },
        },
      });
    });

    it("should handle mixed nested structure with primitives", () => {
      const input = {
        metadata: {
          category: "tech",
          difficulty: 5,
          published: true,
        },
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        metadata: {
          category: "",
          difficulty: 0,
          published: false,
        },
      });
    });
  });

  // Test arrays
  describe("arrays", () => {
    it("should generate empty array for empty arrays", () => {
      const input = { tags: [] };
      expect(generateJsonSchemaFromData(input)).toEqual({ tags: [] });
    });

    it("should generate array with default string for string arrays", () => {
      const input = { tags: ["ai", "ml", "deep-learning"] };
      expect(generateJsonSchemaFromData(input)).toEqual({ tags: [""] });
    });

    it("should generate array with default number for number arrays", () => {
      const input = { scores: [95, 87, 92] };
      expect(generateJsonSchemaFromData(input)).toEqual({ scores: [0] });
    });

    it("should generate array with default boolean for boolean arrays", () => {
      const input = { flags: [true, false, true] };
      expect(generateJsonSchemaFromData(input)).toEqual({ flags: [false] });
    });

    it("should generate array with default object for object arrays", () => {
      const input = {
        users: [
          { name: "John", age: 30 },
          { name: "Jane", age: 25 },
        ],
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        users: [{ name: "", age: 0 }],
      });
    });

    it("should handle nested arrays", () => {
      const input = {
        matrix: [
          [1, 2, 3],
          [4, 5, 6],
        ],
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        matrix: [[0]],
      });
    });
  });

  // Test complex mixed structures
  describe("complex structures", () => {
    it("should handle dataset-like structure with all types", () => {
      const input = {
        question: "What is machine learning?",
        answer: "A type of artificial intelligence",
        metadata: {
          category: "tech",
          difficulty: 3,
          tags: ["ai", "ml"],
          reviewed: true,
        },
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        question: "",
        answer: "",
        metadata: {
          category: "",
          difficulty: 0,
          tags: [""],
          reviewed: false,
        },
      });
    });

    it("should handle deeply nested arrays with objects", () => {
      const input = {
        data: {
          items: [
            {
              id: 1,
              details: {
                name: "Item 1",
                attributes: ["attr1", "attr2"],
              },
            },
          ],
        },
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        data: {
          items: [
            {
              id: 0,
              details: {
                name: "",
                attributes: [""],
              },
            },
          ],
        },
      });
    });

    it("should handle real-world LLM evaluation dataset structure", () => {
      const input = {
        prompt: "Summarize the following text",
        context: "Long article about climate change...",
        expected_output: "Summary of the article",
        metadata: {
          source: "news",
          timestamp: 1699999999,
          labels: ["summarization", "news"],
          config: {
            max_tokens: 100,
            temperature: 0.7,
          },
        },
      };
      expect(generateJsonSchemaFromData(input)).toEqual({
        prompt: "",
        context: "",
        expected_output: "",
        metadata: {
          source: "",
          timestamp: 0,
          labels: [""],
          config: {
            max_tokens: 0,
            temperature: 0,
          },
        },
      });
    });
  });
});
