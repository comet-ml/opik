import { describe, expect, it } from "vitest";
import {
  parseSearchQuery,
  isArrayAccessMode,
  computePathsToExpand,
  filterVisiblePaths,
  findFirstChildPath,
  getVisiblePaths,
  getValuePreview,
  extractTopLevelKey,
  computeVisibleTopLevelKeys,
  VisiblePathItem,
} from "./jsonTreeUtils";

describe("parseSearchQuery", () => {
  it("should return empty result for empty query", () => {
    expect(parseSearchQuery("")).toEqual({
      pathToExpand: null,
      searchTerm: "",
    });
  });

  it("should handle query ending with '.' (expand children)", () => {
    expect(parseSearchQuery("user.")).toEqual({
      pathToExpand: "user",
      searchTerm: "",
    });

    expect(parseSearchQuery("user.profile.")).toEqual({
      pathToExpand: "user.profile",
      searchTerm: "",
    });
  });

  it("should handle query ending with '[' (array access)", () => {
    expect(parseSearchQuery("tags[")).toEqual({
      pathToExpand: "tags",
      searchTerm: "",
    });

    expect(parseSearchQuery("user.items[")).toEqual({
      pathToExpand: "user.items",
      searchTerm: "",
    });
  });

  it("should handle dot notation with search term", () => {
    expect(parseSearchQuery("user.name")).toEqual({
      pathToExpand: "user",
      searchTerm: "name",
    });

    expect(parseSearchQuery("user.profile.email")).toEqual({
      pathToExpand: "user.profile",
      searchTerm: "email",
    });
  });

  it("should handle array notation with index search", () => {
    expect(parseSearchQuery("tags[0")).toEqual({
      pathToExpand: "tags",
      searchTerm: "0",
    });

    expect(parseSearchQuery("user.items[12")).toEqual({
      pathToExpand: "user.items",
      searchTerm: "12",
    });
  });

  it("should handle root level search (no separator)", () => {
    expect(parseSearchQuery("user")).toEqual({
      pathToExpand: null,
      searchTerm: "user",
    });

    expect(parseSearchQuery("name")).toEqual({
      pathToExpand: null,
      searchTerm: "name",
    });
  });

  it("should handle complex nested paths", () => {
    expect(parseSearchQuery("user.tags[0].name")).toEqual({
      pathToExpand: "user.tags[0]",
      searchTerm: "name",
    });
  });
});

describe("isArrayAccessMode", () => {
  it("should return false for empty query", () => {
    expect(isArrayAccessMode("")).toBe(false);
  });

  it("should return true when '[' is the last separator", () => {
    expect(isArrayAccessMode("tags[")).toBe(true);
    expect(isArrayAccessMode("tags[0")).toBe(true);
    expect(isArrayAccessMode("user.items[")).toBe(true);
    expect(isArrayAccessMode("user.items[1")).toBe(true);
  });

  it("should return false when query ends with ']'", () => {
    expect(isArrayAccessMode("tags[0]")).toBe(false);
    expect(isArrayAccessMode("user.items[1]")).toBe(false);
  });

  it("should return false for dot notation", () => {
    expect(isArrayAccessMode("user.name")).toBe(false);
    expect(isArrayAccessMode("user.")).toBe(false);
  });

  it("should return false when '.' comes after '['", () => {
    expect(isArrayAccessMode("tags[0].name")).toBe(false);
  });
});

describe("computePathsToExpand", () => {
  it("should handle single path segment", () => {
    const result = computePathsToExpand("user");
    expect(result).toEqual(new Set(["user"]));
  });

  it("should handle dot notation", () => {
    const result = computePathsToExpand("user.profile");
    expect(result).toEqual(new Set(["user", "user.profile"]));
  });

  it("should handle deeply nested dot notation", () => {
    const result = computePathsToExpand("user.profile.settings");
    expect(result).toEqual(
      new Set(["user", "user.profile", "user.profile.settings"]),
    );
  });

  it("should handle array notation", () => {
    const result = computePathsToExpand("tags[0]");
    expect(result).toEqual(new Set(["tags", "tags[0]"]));
  });

  it("should handle mixed dot and array notation", () => {
    const result = computePathsToExpand("user.tags[0]");
    expect(result).toEqual(new Set(["user", "user.tags", "user.tags[0]"]));
  });

  it("should handle complex nested paths with arrays", () => {
    const result = computePathsToExpand("user.tags[0].name");
    expect(result).toEqual(
      new Set(["user", "user.tags", "user.tags[0]", "user.tags[0].name"]),
    );
  });
});

describe("filterVisiblePaths", () => {
  const mockVisiblePaths: VisiblePathItem[] = [
    { path: "user", value: {} },
    { path: "user.name", value: "John" },
    { path: "user.email", value: "john@example.com" },
    { path: "user.profile", value: {} },
    { path: "user.profile.age", value: 30 },
    { path: "tags", value: [] },
    { path: "tags[0]", value: "tag1" },
    { path: "tags[1]", value: "tag2" },
    { path: "tags[10]", value: "tag10" },
    { path: "settings", value: {} },
    { path: "settings.theme", value: "dark" },
  ];

  it("should return all paths when search query is empty", () => {
    const result = filterVisiblePaths(mockVisiblePaths, "", null, "", false);
    expect(result).toEqual(mockVisiblePaths);
  });

  it("should return all paths when search query is whitespace", () => {
    const result = filterVisiblePaths(mockVisiblePaths, "   ", null, "", false);
    expect(result).toEqual(mockVisiblePaths);
  });

  it("should filter root level by search term (includes nested paths)", () => {
    const result = filterVisiblePaths(
      mockVisiblePaths,
      "user",
      null,
      "user",
      false,
    );
    // Filters by root key, but includes all visible paths under that root
    expect(result.map((p) => p.path)).toEqual([
      "user",
      "user.name",
      "user.email",
      "user.profile",
      "user.profile.age",
    ]);
  });

  it("should filter children when path is expanded with dot (includes nested)", () => {
    const result = filterVisiblePaths(
      mockVisiblePaths,
      "user.",
      "user",
      "",
      false,
    );
    // Shows all children that start with the prefix, including nested
    expect(result.map((p) => p.path)).toEqual([
      "user.name",
      "user.email",
      "user.profile",
      "user.profile.age",
    ]);
  });

  it("should filter children with search term", () => {
    const result = filterVisiblePaths(
      mockVisiblePaths,
      "user.na",
      "user",
      "na",
      false,
    );
    expect(result.map((p) => p.path)).toEqual(["user.name"]);
  });

  it("should filter array children in array access mode", () => {
    const result = filterVisiblePaths(
      mockVisiblePaths,
      "tags[",
      "tags",
      "",
      true,
    );
    expect(result.map((p) => p.path)).toEqual([
      "tags[0]",
      "tags[1]",
      "tags[10]",
    ]);
  });

  it("should filter array children by index in array access mode", () => {
    const result = filterVisiblePaths(
      mockVisiblePaths,
      "tags[1",
      "tags",
      "1",
      true,
    );
    expect(result.map((p) => p.path)).toEqual(["tags[1]", "tags[10]"]);
  });

  it("should fall back to global search when no children found", () => {
    const result = filterVisiblePaths(
      mockVisiblePaths,
      "nonexistent.",
      "nonexistent",
      "",
      false,
    );
    expect(result).toEqual([]);
  });

  it("should be case insensitive", () => {
    const result = filterVisiblePaths(
      mockVisiblePaths,
      "USER",
      null,
      "USER",
      false,
    );
    // Case insensitive match on root key, includes all nested paths
    expect(result.map((p) => p.path)).toEqual([
      "user",
      "user.name",
      "user.email",
      "user.profile",
      "user.profile.age",
    ]);
  });
});

describe("findFirstChildPath", () => {
  const mockPaths: VisiblePathItem[] = [
    { path: "user", value: {} },
    { path: "user.name", value: "John" },
    { path: "user.email", value: "john@example.com" },
    { path: "tags", value: [] },
    { path: "tags[0]", value: "tag1" },
    { path: "tags[1]", value: "tag2" },
  ];

  it("should find first child with dot notation", () => {
    const result = findFirstChildPath(mockPaths, "user");
    expect(result).toBe("user.name");
  });

  it("should find first child with array notation", () => {
    const result = findFirstChildPath(mockPaths, "tags");
    expect(result).toBe("tags[0]");
  });

  it("should return null when no children found", () => {
    const result = findFirstChildPath(mockPaths, "nonexistent");
    expect(result).toBeNull();
  });

  it("should return null for empty paths array", () => {
    const result = findFirstChildPath([], "user");
    expect(result).toBeNull();
  });
});

describe("getVisiblePaths", () => {
  it("should return root level entries for object", () => {
    const data = { name: "John", age: 30 };
    const result = getVisiblePaths(data, new Set());
    expect(result).toEqual([
      { path: "name", value: "John" },
      { path: "age", value: 30 },
    ]);
  });

  it("should return root level entries for array", () => {
    const data = ["a", "b", "c"];
    const result = getVisiblePaths(data, new Set());
    expect(result).toEqual([
      { path: "[0]", value: "a" },
      { path: "[1]", value: "b" },
      { path: "[2]", value: "c" },
    ]);
  });

  it("should expand nested objects when path is in expandedPaths", () => {
    const data = { user: { name: "John", age: 30 } };
    const result = getVisiblePaths(data, new Set(["user"]));
    expect(result).toEqual([
      { path: "user", value: { name: "John", age: 30 } },
      { path: "user.name", value: "John" },
      { path: "user.age", value: 30 },
    ]);
  });

  it("should expand nested arrays when path is in expandedPaths", () => {
    const data = { tags: ["a", "b"] };
    const result = getVisiblePaths(data, new Set(["tags"]));
    expect(result).toEqual([
      { path: "tags", value: ["a", "b"] },
      { path: "tags[0]", value: "a" },
      { path: "tags[1]", value: "b" },
    ]);
  });

  it("should handle deeply nested expansion", () => {
    const data = { user: { profile: { name: "John" } } };
    const result = getVisiblePaths(data, new Set(["user", "user.profile"]));
    expect(result).toEqual([
      { path: "user", value: { profile: { name: "John" } } },
      { path: "user.profile", value: { name: "John" } },
      { path: "user.profile.name", value: "John" },
    ]);
  });

  it("should not expand paths not in expandedPaths", () => {
    const data = { user: { name: "John" }, settings: { theme: "dark" } };
    const result = getVisiblePaths(data, new Set(["user"]));
    expect(result).toEqual([
      { path: "user", value: { name: "John" } },
      { path: "user.name", value: "John" },
      { path: "settings", value: { theme: "dark" } },
    ]);
  });
});

describe("extractTopLevelKey", () => {
  it("should return the whole path when no separator exists", () => {
    expect(extractTopLevelKey("user")).toBe("user");
    expect(extractTopLevelKey("settings")).toBe("settings");
  });

  it("should extract key before dot separator", () => {
    expect(extractTopLevelKey("user.name")).toBe("user");
    expect(extractTopLevelKey("user.profile.email")).toBe("user");
  });

  it("should extract key before bracket separator", () => {
    expect(extractTopLevelKey("user[0]")).toBe("user");
    expect(extractTopLevelKey("tags[0].name")).toBe("tags");
  });

  it("should handle root array elements", () => {
    expect(extractTopLevelKey("[0]")).toBe("[0]");
    expect(extractTopLevelKey("[0].name")).toBe("[0]");
    expect(extractTopLevelKey("[10]")).toBe("[10]");
    expect(extractTopLevelKey("[123].value")).toBe("[123]");
  });

  it("should take earlier separator when both exist", () => {
    expect(extractTopLevelKey("user.tags[0]")).toBe("user");
    expect(extractTopLevelKey("items[0].name")).toBe("items");
  });
});

describe("computeVisibleTopLevelKeys", () => {
  it("should return empty set for empty paths", () => {
    const result = computeVisibleTopLevelKeys([]);
    expect(result).toEqual(new Set());
  });

  it("should extract unique top-level keys from paths", () => {
    const paths: VisiblePathItem[] = [
      { path: "user", value: {} },
      { path: "user.name", value: "John" },
      { path: "user.email", value: "john@example.com" },
      { path: "settings", value: {} },
    ];
    const result = computeVisibleTopLevelKeys(paths);
    expect(result).toEqual(new Set(["user", "settings"]));
  });

  it("should handle array paths", () => {
    const paths: VisiblePathItem[] = [
      { path: "tags", value: [] },
      { path: "tags[0]", value: "tag1" },
      { path: "tags[1]", value: "tag2" },
    ];
    const result = computeVisibleTopLevelKeys(paths);
    expect(result).toEqual(new Set(["tags"]));
  });

  it("should handle root array elements", () => {
    const paths: VisiblePathItem[] = [
      { path: "[0]", value: "a" },
      { path: "[0].name", value: "John" },
      { path: "[1]", value: "b" },
      { path: "[2].value", value: 42 },
    ];
    const result = computeVisibleTopLevelKeys(paths);
    expect(result).toEqual(new Set(["[0]", "[1]", "[2]"]));
  });

  it("should handle mixed object and nested paths", () => {
    const paths: VisiblePathItem[] = [
      { path: "user.profile.name", value: "John" },
      { path: "settings.theme", value: "dark" },
      { path: "tags[0]", value: "tag1" },
    ];
    const result = computeVisibleTopLevelKeys(paths);
    expect(result).toEqual(new Set(["user", "settings", "tags"]));
  });
});

describe("getValuePreview", () => {
  it("should return 'null' for null values", () => {
    expect(getValuePreview(null)).toBe("null");
  });

  it("should return array length for arrays", () => {
    expect(getValuePreview([])).toBe("Array[0]");
    expect(getValuePreview([1, 2, 3])).toBe("Array[3]");
  });

  it("should return object key count for objects", () => {
    expect(getValuePreview({})).toBe("Object{0}");
    expect(getValuePreview({ a: 1, b: 2 })).toBe("Object{2}");
  });

  it("should truncate long strings", () => {
    const longString = "a".repeat(50);
    expect(getValuePreview(longString)).toBe(`"${"a".repeat(30)}..."`);
  });

  it("should not truncate short strings", () => {
    expect(getValuePreview("hello")).toBe('"hello"');
  });

  it("should stringify numbers", () => {
    expect(getValuePreview(42)).toBe("42");
    expect(getValuePreview(3.14)).toBe("3.14");
  });

  it("should stringify booleans", () => {
    expect(getValuePreview(true)).toBe("true");
    expect(getValuePreview(false)).toBe("false");
  });
});
