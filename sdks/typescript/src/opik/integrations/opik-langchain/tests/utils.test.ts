import { describe, it, expect } from "vitest";
import {
  cleanObject,
  safeParseSerializedJson,
  pick,
  outputFromChainValues,
  inputFromChainValues,
  extractCallArgs,
} from "../src/utils";
import { Serialized } from "@langchain/core/load/serializable";

describe("Utils", () => {
  describe("cleanObject", () => {
    it("should remove undefined and null values", () => {
      const input = {
        a: "value",
        b: undefined,
        c: null,
        d: 42,
        e: "",
        f: 0,
        g: false,
      };

      const result = cleanObject(input);

      expect(result).toEqual({
        a: "value",
        d: 42,
        e: "",
        f: 0,
        g: false,
      });
    });

    it("should remove empty arrays", () => {
      const input = {
        a: "value",
        b: [],
        c: [1, 2, 3],
      };

      const result = cleanObject(input);

      expect(result).toEqual({
        a: "value",
        c: [1, 2, 3],
      });
    });

    it("should remove empty objects", () => {
      const input = {
        a: "value",
        b: {},
        c: { key: "value" },
      };

      const result = cleanObject(input);

      expect(result).toEqual({
        a: "value",
        c: { key: "value" },
      });
    });
  });

  describe("safeParseSerializedJson", () => {
    it("should parse valid JSON", () => {
      const input = '{"key": "value", "number": 42}';
      const result = safeParseSerializedJson(input);

      expect(result).toEqual({
        key: "value",
        number: 42,
      });
    });

    it("should return wrapped value for invalid JSON", () => {
      const input = "not valid json";
      const result = safeParseSerializedJson(input);

      expect(result).toEqual({
        value: "not valid json",
      });
    });

    it("should handle empty string", () => {
      const input = "";
      const result = safeParseSerializedJson(input);

      expect(result).toEqual({
        value: "",
      });
    });
  });

  describe("pick", () => {
    it("should return first non-null, non-undefined value", () => {
      expect(pick(undefined, null, "valid")).toBe("valid");
      expect(pick(null, "first", "second")).toBe("first");
      expect(pick("immediate")).toBe("immediate");
    });

    it("should return undefined if all values are null/undefined", () => {
      expect(pick(undefined, null, undefined)).toBe(undefined);
    });

    it("should return falsy but defined values", () => {
      expect(pick(undefined, 0)).toBe(0);
      expect(pick(undefined, "")).toBe("");
      expect(pick(undefined, false)).toBe(false);
    });
  });

  describe("outputFromChainValues", () => {
    it("should handle simple string values", () => {
      const result = outputFromChainValues("simple text");
      expect(result).toEqual({ value: "simple text" });
    });

    it("should handle simple object values", () => {
      const input = { key: "value", number: 42 };
      const result = outputFromChainValues(input);
      expect(result).toEqual({
        key: { value: "value" },
        number: { value: 42 },
      });
    });

    it("should handle arrays", () => {
      const input = ["item1", "item2"];
      const result = outputFromChainValues(input);
      expect(result).toEqual({
        values: [{ value: "item1" }, { value: "item2" }],
      });
    });

    it("should handle null and undefined", () => {
      expect(outputFromChainValues(null)).toEqual({});
      expect(outputFromChainValues(undefined)).toEqual({});
    });
  });

  describe("inputFromChainValues", () => {
    it("should handle simple values", () => {
      const result = inputFromChainValues({ query: "test question" });
      expect(result).toEqual({
        query: { value: "test question" },
      });
    });

    it("should handle arrays", () => {
      const input = [{ a: 1 }, { b: 2 }];
      const result = inputFromChainValues(input);
      expect(result).toEqual({
        values: [{ a: { value: 1 } }, { b: { value: 2 } }],
      });
    });
  });

  describe("extractCallArgs", () => {
    it("should extract call arguments with preference order", () => {
      const llm: Serialized = {
        lc: 1,
        type: "not_implemented",
        id: ["test", "TestLLM"],
        name: "TestLLM",
      };

      const invocationParams = {
        model: "gpt-4",
        temperature: 0.7,
        max_tokens: 100,
      };

      const metadata = {
        ls_model_name: "metadata-model",
        ls_temperature: 0.5,
      };

      const result = extractCallArgs(llm, invocationParams, metadata);

      expect(result).toEqual({
        model: "gpt-4", // invocationParams takes precedence
        temperature: 0.7, // invocationParams takes precedence
        max_tokens: 100,
      });
    });

    it("should fall back to metadata when invocation params are missing", () => {
      const llm: Serialized = {
        lc: 1,
        type: "not_implemented",
        id: ["test", "TestLLM"],
        name: "FallbackLLM",
      };

      const invocationParams = {};
      const metadata = {
        ls_model_name: "metadata-model",
        ls_temperature: 0.8,
      };

      const result = extractCallArgs(llm, invocationParams, metadata);

      expect(result).toEqual({
        model: "metadata-model",
        temperature: 0.8,
      });
    });

    it("should return invocation params when no extracted args", () => {
      const llm: Serialized = {
        lc: 1,
        type: "not_implemented",
        id: ["test", "TestLLM"],
      };

      const invocationParams = {
        custom_param: "custom_value",
        another_param: 42,
      };

      const result = extractCallArgs(llm, invocationParams);

      expect(result).toEqual(invocationParams);
    });
  });
});
