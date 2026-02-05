import { describe, it, expect } from "vitest";
import {
  getByPath,
  setByPath,
  hasPath,
  isPathRef,
  resolveDynamicValue,
  resolveProps,
  analyzeSourceData,
  extractAllPaths,
} from "./path";
import type { SourceData } from "./types";

describe("path utilities", () => {
  const sampleData: SourceData = {
    model: "gpt-4",
    input: "Hello world",
    output: "Hi there!",
    tools: [
      { name: "search", input: { query: "test" }, output: { results: [] } },
      { name: "calc", input: { expr: "2+2" }, output: { value: 4 } },
    ],
    metadata: {
      tokens: { input: 10, output: 20 },
      latency_ms: 150,
    },
    emptyArray: [],
    emptyString: "",
    nullValue: null,
  };

  describe("isPathRef", () => {
    it("returns true for valid path ref", () => {
      expect(isPathRef({ path: "/model" })).toBe(true);
    });

    it("returns false for literal value", () => {
      expect(isPathRef("literal")).toBe(false);
      expect(isPathRef(42)).toBe(false);
      expect(isPathRef(null)).toBe(false);
    });

    it("returns false for old-style path ref with $path", () => {
      expect(isPathRef({ $path: "/model" })).toBe(false);
    });

    it("returns false for object without path key", () => {
      expect(isPathRef({ value: "test" })).toBe(false);
    });
  });

  describe("getByPath", () => {
    it("gets top-level value", () => {
      const result = getByPath(sampleData, "/model");
      expect(result).toBe("gpt-4");
    });

    it("gets nested value", () => {
      const result = getByPath(sampleData, "/metadata/tokens/input");
      expect(result).toBe(10);
    });

    it("gets array element", () => {
      const result = getByPath(sampleData, "/tools/0/name");
      expect(result).toBe("search");
    });

    it("returns undefined for missing path", () => {
      const result = getByPath(sampleData, "/nonexistent");
      expect(result).toBeUndefined();
    });

    it("returns the whole object for root path", () => {
      const result = getByPath(sampleData, "/");
      expect(result).toBe(sampleData);
    });

    it("returns the whole object for empty path", () => {
      const result = getByPath(sampleData, "");
      expect(result).toBe(sampleData);
    });
  });

  describe("hasPath", () => {
    it("returns true for existing path", () => {
      expect(hasPath(sampleData, "/model")).toBe(true);
    });

    it("returns true for nested path", () => {
      expect(hasPath(sampleData, "/metadata/tokens/input")).toBe(true);
    });

    it("returns false for missing path", () => {
      expect(hasPath(sampleData, "/nonexistent")).toBe(false);
    });

    it("returns true for null value path", () => {
      expect(hasPath(sampleData, "/nullValue")).toBe(true);
    });

    it("returns true for array index", () => {
      expect(hasPath(sampleData, "/tools/0")).toBe(true);
    });

    it("returns false for out of bounds array index", () => {
      expect(hasPath(sampleData, "/tools/99")).toBe(false);
    });
  });

  describe("setByPath", () => {
    it("sets top-level value", () => {
      const obj: Record<string, unknown> = {};
      setByPath(obj, "/foo", "bar");
      expect(obj.foo).toBe("bar");
    });

    it("sets nested value", () => {
      const obj: Record<string, unknown> = {};
      setByPath(obj, "/a/b/c", 42);
      expect(getByPath(obj, "/a/b/c")).toBe(42);
    });

    it("creates intermediate objects", () => {
      const obj: Record<string, unknown> = {};
      setByPath(obj, "/deep/nested/path", "value");
      expect(getByPath(obj, "/deep/nested/path")).toBe("value");
    });
  });

  describe("resolveDynamicValue", () => {
    it("resolves a literal value", () => {
      const result = resolveDynamicValue("literal", sampleData);
      expect(result).toBe("literal");
    });

    it("resolves a path reference", () => {
      const result = resolveDynamicValue({ path: "/model" }, sampleData);
      expect(result).toBe("gpt-4");
    });

    it("resolves nested path", () => {
      const result = resolveDynamicValue(
        { path: "/metadata/tokens/input" },
        sampleData,
      );
      expect(result).toBe(10);
    });

    it("resolves array path", () => {
      const result = resolveDynamicValue({ path: "/tools/0/name" }, sampleData);
      expect(result).toBe("search");
    });

    it("returns undefined for missing path", () => {
      const result = resolveDynamicValue({ path: "/nonexistent" }, sampleData);
      expect(result).toBeUndefined();
    });
  });

  describe("resolveProps", () => {
    it("resolves all bindings in props object", () => {
      const props = {
        title: "Static title",
        modelName: { path: "/model" },
        tokenCount: { path: "/metadata/tokens/input" },
      };

      const result = resolveProps(props, sampleData);

      expect(result).toEqual({
        title: "Static title",
        modelName: "gpt-4",
        tokenCount: 10,
      });
    });

    it("resolves nested bindings in arrays", () => {
      const props = {
        items: [{ path: "/tools/0/name" }, { path: "/tools/1/name" }],
      };

      const result = resolveProps(props, sampleData);

      expect(result.items).toEqual(["search", "calc"]);
    });

    it("resolves nested bindings in objects", () => {
      const props = {
        nested: {
          value: { path: "/model" },
          static: "text",
        },
      };

      const result = resolveProps(props, sampleData);

      expect(result.nested).toEqual({
        value: "gpt-4",
        static: "text",
      });
    });
  });

  describe("extractAllPaths", () => {
    it("extracts paths from nested object", () => {
      const nodes = {
        node1: {
          props: {
            title: "Static",
            value: { path: "/model" },
            nested: {
              data: { path: "/metadata/tokens" },
            },
          },
        },
      };

      const paths = extractAllPaths(nodes);

      expect(paths).toContain("/model");
      expect(paths).toContain("/metadata/tokens");
    });
  });

  describe("analyzeSourceData", () => {
    it("extracts path info from data", () => {
      const paths = analyzeSourceData(sampleData);

      const modelPath = paths.find((p) => p.path === "/model");
      expect(modelPath).toBeDefined();
      expect(modelPath?.type).toBe("string");
      expect(modelPath?.sample).toBe("gpt-4");
    });

    it("identifies array paths with length", () => {
      const paths = analyzeSourceData(sampleData);

      const toolsPath = paths.find((p) => p.path === "/tools");
      expect(toolsPath).toBeDefined();
      expect(toolsPath?.type).toBe("array");
      expect(toolsPath?.arrayLength).toBe(2);
    });

    it("respects maxDepth", () => {
      const paths = analyzeSourceData(sampleData, 1);

      // Should have top-level paths
      expect(paths.some((p) => p.path === "/model")).toBe(true);

      // Should not have deeply nested paths
      expect(paths.some((p) => p.path === "/metadata/tokens/input")).toBe(
        false,
      );
    });

    it("truncates long string samples", () => {
      const dataWithLongString = {
        longText: "a".repeat(200),
      };

      const paths = analyzeSourceData(dataWithLongString);
      const longTextPath = paths.find((p) => p.path === "/longText");

      expect(longTextPath?.sample).toHaveLength(103); // 100 + "..."
    });
  });
});
