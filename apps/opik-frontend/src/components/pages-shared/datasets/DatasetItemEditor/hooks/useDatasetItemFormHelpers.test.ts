import { describe, expect, it } from "vitest";
import { prepareFormDataForSave } from "./useDatasetItemFormHelpers";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { DatasetItemColumn } from "@/types/datasets";

const col = (
  name: string,
  ...types: DYNAMIC_COLUMN_TYPE[]
): DatasetItemColumn => ({
  name,
  types,
});

describe("prepareFormDataForSave", () => {
  describe("JSON object/array always wins", () => {
    it("parses a JSON object even if column says string", () => {
      const result = prepareFormDataForSave({ input: '{"key": "value"}' }, [
        col("input", DYNAMIC_COLUMN_TYPE.string),
      ]);
      expect(result.input).toEqual({ key: "value" });
    });

    it("parses a JSON array even if column says number", () => {
      const result = prepareFormDataForSave({ data: "[1, 2, 3]" }, [
        col("data", DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.data).toEqual([1, 2, 3]);
    });

    it("parses nested JSON objects", () => {
      const result = prepareFormDataForSave({ input: '{"a": {"b": [1, 2]}}' }, [
        col("input", DYNAMIC_COLUMN_TYPE.object),
      ]);
      expect(result.input).toEqual({ a: { b: [1, 2] } });
    });
  });

  describe("number coercion", () => {
    it("coerces string to number when column type is number", () => {
      const result = prepareFormDataForSave({ score: "42" }, [
        col("score", DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.score).toBe(42);
    });

    it("coerces float strings", () => {
      const result = prepareFormDataForSave({ score: "3.14" }, [
        col("score", DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.score).toBe(3.14);
    });

    it("coerces negative numbers", () => {
      const result = prepareFormDataForSave({ score: "-7" }, [
        col("score", DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.score).toBe(-7);
    });

    it("falls back to string if not a valid number", () => {
      const result = prepareFormDataForSave({ score: "foobar" }, [
        col("score", DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.score).toBe("foobar");
    });

    it("does not coerce empty string to number", () => {
      const result = prepareFormDataForSave({ score: "" }, [
        col("score", DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.score).toBe("");
    });
  });

  describe("boolean coercion", () => {
    it("coerces 'true' to boolean", () => {
      const result = prepareFormDataForSave({ flag: "true" }, [
        col("flag", DYNAMIC_COLUMN_TYPE.boolean),
      ]);
      expect(result.flag).toBe(true);
    });

    it("coerces 'false' to boolean", () => {
      const result = prepareFormDataForSave({ flag: "false" }, [
        col("flag", DYNAMIC_COLUMN_TYPE.boolean),
      ]);
      expect(result.flag).toBe(false);
    });

    it("is case-insensitive", () => {
      const result = prepareFormDataForSave(
        { a: "True", b: "FALSE", c: "tRuE" },
        [
          col("a", DYNAMIC_COLUMN_TYPE.boolean),
          col("b", DYNAMIC_COLUMN_TYPE.boolean),
          col("c", DYNAMIC_COLUMN_TYPE.boolean),
        ],
      );
      expect(result.a).toBe(true);
      expect(result.b).toBe(false);
      expect(result.c).toBe(true);
    });

    it("falls back to string for non-boolean values", () => {
      const result = prepareFormDataForSave({ flag: "yes" }, [
        col("flag", DYNAMIC_COLUMN_TYPE.boolean),
      ]);
      expect(result.flag).toBe("yes");
    });
  });

  describe("object/array column coercion", () => {
    it("parses JSON when column type is object", () => {
      const result = prepareFormDataForSave({ config: '{"a": 1}' }, [
        col("config", DYNAMIC_COLUMN_TYPE.object),
      ]);
      expect(result.config).toEqual({ a: 1 });
    });

    it("parses JSON when column type is array", () => {
      const result = prepareFormDataForSave({ items: '["a", "b"]' }, [
        col("items", DYNAMIC_COLUMN_TYPE.array),
      ]);
      expect(result.items).toEqual(["a", "b"]);
    });

    it("falls back to string for invalid JSON in object column", () => {
      const result = prepareFormDataForSave({ config: "not json" }, [
        col("config", DYNAMIC_COLUMN_TYPE.object),
      ]);
      expect(result.config).toBe("not json");
    });
  });

  describe("multi-type columns", () => {
    it("tries non-string types first", () => {
      const result = prepareFormDataForSave({ value: "42" }, [
        col("value", DYNAMIC_COLUMN_TYPE.string, DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.value).toBe(42);
    });

    it("falls back to string when non-string coercion fails", () => {
      const result = prepareFormDataForSave({ value: "hello" }, [
        col("value", DYNAMIC_COLUMN_TYPE.number, DYNAMIC_COLUMN_TYPE.string),
      ]);
      expect(result.value).toBe("hello");
    });
  });

  describe("string fallback", () => {
    it("keeps plain strings as-is for string columns", () => {
      const result = prepareFormDataForSave({ name: "hello world" }, [
        col("name", DYNAMIC_COLUMN_TYPE.string),
      ]);
      expect(result.name).toBe("hello world");
    });

    it("keeps value as-is when no column metadata matches", () => {
      const result = prepareFormDataForSave({ unknown_field: "some value" }, [
        col("other_field", DYNAMIC_COLUMN_TYPE.string),
      ]);
      expect(result.unknown_field).toBe("some value");
    });
  });

  describe("non-string values pass through", () => {
    it("does not re-coerce numbers", () => {
      const result = prepareFormDataForSave({ score: 42 }, [
        col("score", DYNAMIC_COLUMN_TYPE.number),
      ]);
      expect(result.score).toBe(42);
    });

    it("does not re-coerce booleans", () => {
      const result = prepareFormDataForSave({ flag: true }, [
        col("flag", DYNAMIC_COLUMN_TYPE.boolean),
      ]);
      expect(result.flag).toBe(true);
    });
  });
});
