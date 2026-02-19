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
    // Arrange
    const query = "";

    // Act
    const result = parseSearchQuery(query);

    // Assert
    expect(result).toEqual({
      pathToExpand: null,
      searchTerm: "",
    });
  });

  it("should handle query ending with '.' (expand children)", () => {
    // Arrange
    const simpleQuery = "user.";
    const nestedQuery = "user.profile.";

    // Act
    const simpleResult = parseSearchQuery(simpleQuery);
    const nestedResult = parseSearchQuery(nestedQuery);

    // Assert
    expect(simpleResult).toEqual({
      pathToExpand: "user",
      searchTerm: "",
    });
    expect(nestedResult).toEqual({
      pathToExpand: "user.profile",
      searchTerm: "",
    });
  });

  it("should handle query ending with '[' (array access)", () => {
    // Arrange
    const simpleQuery = "tags[";
    const nestedQuery = "user.items[";

    // Act
    const simpleResult = parseSearchQuery(simpleQuery);
    const nestedResult = parseSearchQuery(nestedQuery);

    // Assert
    expect(simpleResult).toEqual({
      pathToExpand: "tags",
      searchTerm: "",
    });
    expect(nestedResult).toEqual({
      pathToExpand: "user.items",
      searchTerm: "",
    });
  });

  it("should handle dot notation with search term", () => {
    // Arrange
    const simpleQuery = "user.name";
    const nestedQuery = "user.profile.email";

    // Act
    const simpleResult = parseSearchQuery(simpleQuery);
    const nestedResult = parseSearchQuery(nestedQuery);

    // Assert
    expect(simpleResult).toEqual({
      pathToExpand: "user",
      searchTerm: "name",
    });
    expect(nestedResult).toEqual({
      pathToExpand: "user.profile",
      searchTerm: "email",
    });
  });

  it("should handle array notation with index search", () => {
    // Arrange
    const simpleQuery = "tags[0";
    const nestedQuery = "user.items[12";

    // Act
    const simpleResult = parseSearchQuery(simpleQuery);
    const nestedResult = parseSearchQuery(nestedQuery);

    // Assert
    expect(simpleResult).toEqual({
      pathToExpand: "tags",
      searchTerm: "0",
    });
    expect(nestedResult).toEqual({
      pathToExpand: "user.items",
      searchTerm: "12",
    });
  });

  it("should handle root level search (no separator)", () => {
    // Arrange
    const userQuery = "user";
    const nameQuery = "name";

    // Act
    const userResult = parseSearchQuery(userQuery);
    const nameResult = parseSearchQuery(nameQuery);

    // Assert
    expect(userResult).toEqual({
      pathToExpand: null,
      searchTerm: "user",
    });
    expect(nameResult).toEqual({
      pathToExpand: null,
      searchTerm: "name",
    });
  });

  it("should handle complex nested paths", () => {
    // Arrange
    const query = "user.tags[0].name";

    // Act
    const result = parseSearchQuery(query);

    // Assert
    expect(result).toEqual({
      pathToExpand: "user.tags[0]",
      searchTerm: "name",
    });
  });
});

describe("isArrayAccessMode", () => {
  it("should return false for empty query", () => {
    // Arrange
    const query = "";

    // Act
    const result = isArrayAccessMode(query);

    // Assert
    expect(result).toBe(false);
  });

  it("should return true when '[' is the last separator", () => {
    // Arrange
    const queries = ["tags[", "tags[0", "user.items[", "user.items[1"];

    // Act & Assert
    queries.forEach((query) => {
      expect(isArrayAccessMode(query)).toBe(true);
    });
  });

  it("should return false when query ends with ']'", () => {
    // Arrange
    const queries = ["tags[0]", "user.items[1]"];

    // Act & Assert
    queries.forEach((query) => {
      expect(isArrayAccessMode(query)).toBe(false);
    });
  });

  it("should return false for dot notation", () => {
    // Arrange
    const queries = ["user.name", "user."];

    // Act & Assert
    queries.forEach((query) => {
      expect(isArrayAccessMode(query)).toBe(false);
    });
  });

  it("should return false when '.' comes after '['", () => {
    // Arrange
    const query = "tags[0].name";

    // Act
    const result = isArrayAccessMode(query);

    // Assert
    expect(result).toBe(false);
  });
});

describe("computePathsToExpand", () => {
  it("should handle single path segment", () => {
    // Arrange
    const path = "user";

    // Act
    const result = computePathsToExpand(path);

    // Assert
    expect(result).toEqual(new Set(["user"]));
  });

  it("should handle dot notation", () => {
    // Arrange
    const path = "user.profile";

    // Act
    const result = computePathsToExpand(path);

    // Assert
    expect(result).toEqual(new Set(["user", "user.profile"]));
  });

  it("should handle deeply nested dot notation", () => {
    // Arrange
    const path = "user.profile.settings";

    // Act
    const result = computePathsToExpand(path);

    // Assert
    expect(result).toEqual(
      new Set(["user", "user.profile", "user.profile.settings"]),
    );
  });

  it("should handle array notation", () => {
    // Arrange
    const path = "tags[0]";

    // Act
    const result = computePathsToExpand(path);

    // Assert
    expect(result).toEqual(new Set(["tags", "tags[0]"]));
  });

  it("should handle mixed dot and array notation", () => {
    // Arrange
    const path = "user.tags[0]";

    // Act
    const result = computePathsToExpand(path);

    // Assert
    expect(result).toEqual(new Set(["user", "user.tags", "user.tags[0]"]));
  });

  it("should handle complex nested paths with arrays", () => {
    // Arrange
    const path = "user.tags[0].name";

    // Act
    const result = computePathsToExpand(path);

    // Assert
    expect(result).toEqual(
      new Set(["user", "user.tags", "user.tags[0]", "user.tags[0].name"]),
    );
  });
});

describe("filterVisiblePaths", () => {
  // Arrange - shared test data
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
    // Arrange
    const searchQuery = "";

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      null,
      "",
      false,
    );

    // Assert
    expect(result).toEqual(mockVisiblePaths);
  });

  it("should return all paths when search query is whitespace", () => {
    // Arrange
    const searchQuery = "   ";

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      null,
      "",
      false,
    );

    // Assert
    expect(result).toEqual(mockVisiblePaths);
  });

  it("should filter root level by search term (includes nested paths)", () => {
    // Arrange
    const searchQuery = "user";
    const searchTerm = "user";

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      null,
      searchTerm,
      false,
    );

    // Assert
    expect(result.map((p) => p.path)).toEqual([
      "user",
      "user.name",
      "user.email",
      "user.profile",
      "user.profile.age",
    ]);
  });

  it("should filter children when path is expanded with dot (includes nested)", () => {
    // Arrange
    const searchQuery = "user.";
    const pathToExpand = "user";

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      pathToExpand,
      "",
      false,
    );

    // Assert
    expect(result.map((p) => p.path)).toEqual([
      "user.name",
      "user.email",
      "user.profile",
      "user.profile.age",
    ]);
  });

  it("should filter children with search term", () => {
    // Arrange
    const searchQuery = "user.na";
    const pathToExpand = "user";
    const searchTerm = "na";

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      pathToExpand,
      searchTerm,
      false,
    );

    // Assert
    expect(result.map((p) => p.path)).toEqual(["user.name"]);
  });

  it("should filter array children in array access mode", () => {
    // Arrange
    const searchQuery = "tags[";
    const pathToExpand = "tags";
    const isArrayAccess = true;

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      pathToExpand,
      "",
      isArrayAccess,
    );

    // Assert
    expect(result.map((p) => p.path)).toEqual([
      "tags[0]",
      "tags[1]",
      "tags[10]",
    ]);
  });

  it("should filter array children by index in array access mode", () => {
    // Arrange
    const searchQuery = "tags[1";
    const pathToExpand = "tags";
    const searchTerm = "1";
    const isArrayAccess = true;

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      pathToExpand,
      searchTerm,
      isArrayAccess,
    );

    // Assert
    expect(result.map((p) => p.path)).toEqual(["tags[1]", "tags[10]"]);
  });

  it("should fall back to global search when no children found", () => {
    // Arrange
    const searchQuery = "nonexistent.";
    const pathToExpand = "nonexistent";

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      pathToExpand,
      "",
      false,
    );

    // Assert
    expect(result).toEqual([]);
  });

  it("should be case insensitive", () => {
    // Arrange
    const searchQuery = "USER";
    const searchTerm = "USER";

    // Act
    const result = filterVisiblePaths(
      mockVisiblePaths,
      searchQuery,
      null,
      searchTerm,
      false,
    );

    // Assert
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
  // Arrange - shared test data
  const mockPaths: VisiblePathItem[] = [
    { path: "user", value: {} },
    { path: "user.name", value: "John" },
    { path: "user.email", value: "john@example.com" },
    { path: "tags", value: [] },
    { path: "tags[0]", value: "tag1" },
    { path: "tags[1]", value: "tag2" },
  ];

  it("should find first child with dot notation", () => {
    // Arrange
    const pathToExpand = "user";

    // Act
    const result = findFirstChildPath(mockPaths, pathToExpand);

    // Assert
    expect(result).toBe("user.name");
  });

  it("should find first child with array notation", () => {
    // Arrange
    const pathToExpand = "tags";

    // Act
    const result = findFirstChildPath(mockPaths, pathToExpand);

    // Assert
    expect(result).toBe("tags[0]");
  });

  it("should return null when no children found", () => {
    // Arrange
    const pathToExpand = "nonexistent";

    // Act
    const result = findFirstChildPath(mockPaths, pathToExpand);

    // Assert
    expect(result).toBeNull();
  });

  it("should return null for empty paths array", () => {
    // Arrange
    const emptyPaths: VisiblePathItem[] = [];
    const pathToExpand = "user";

    // Act
    const result = findFirstChildPath(emptyPaths, pathToExpand);

    // Assert
    expect(result).toBeNull();
  });
});

describe("getVisiblePaths", () => {
  it("should return root level entries for object", () => {
    // Arrange
    const data = { name: "John", age: 30 };
    const expandedPaths = new Set<string>();

    // Act
    const result = getVisiblePaths(data, expandedPaths);

    // Assert
    expect(result).toEqual([
      { path: "name", value: "John" },
      { path: "age", value: 30 },
    ]);
  });

  it("should return root level entries for array", () => {
    // Arrange
    const data = ["a", "b", "c"];
    const expandedPaths = new Set<string>();

    // Act
    const result = getVisiblePaths(data, expandedPaths);

    // Assert
    expect(result).toEqual([
      { path: "[0]", value: "a" },
      { path: "[1]", value: "b" },
      { path: "[2]", value: "c" },
    ]);
  });

  it("should expand nested objects when path is in expandedPaths", () => {
    // Arrange
    const data = { user: { name: "John", age: 30 } };
    const expandedPaths = new Set(["user"]);

    // Act
    const result = getVisiblePaths(data, expandedPaths);

    // Assert
    expect(result).toEqual([
      { path: "user", value: { name: "John", age: 30 } },
      { path: "user.name", value: "John" },
      { path: "user.age", value: 30 },
    ]);
  });

  it("should expand nested arrays when path is in expandedPaths", () => {
    // Arrange
    const data = { tags: ["a", "b"] };
    const expandedPaths = new Set(["tags"]);

    // Act
    const result = getVisiblePaths(data, expandedPaths);

    // Assert
    expect(result).toEqual([
      { path: "tags", value: ["a", "b"] },
      { path: "tags[0]", value: "a" },
      { path: "tags[1]", value: "b" },
    ]);
  });

  it("should handle deeply nested expansion", () => {
    // Arrange
    const data = { user: { profile: { name: "John" } } };
    const expandedPaths = new Set(["user", "user.profile"]);

    // Act
    const result = getVisiblePaths(data, expandedPaths);

    // Assert
    expect(result).toEqual([
      { path: "user", value: { profile: { name: "John" } } },
      { path: "user.profile", value: { name: "John" } },
      { path: "user.profile.name", value: "John" },
    ]);
  });

  it("should not expand paths not in expandedPaths", () => {
    // Arrange
    const data = { user: { name: "John" }, settings: { theme: "dark" } };
    const expandedPaths = new Set(["user"]);

    // Act
    const result = getVisiblePaths(data, expandedPaths);

    // Assert
    expect(result).toEqual([
      { path: "user", value: { name: "John" } },
      { path: "user.name", value: "John" },
      { path: "settings", value: { theme: "dark" } },
    ]);
  });
});

describe("extractTopLevelKey", () => {
  it("should return the whole path when no separator exists", () => {
    // Arrange
    const paths = ["user", "settings"];

    // Act & Assert
    expect(extractTopLevelKey(paths[0])).toBe("user");
    expect(extractTopLevelKey(paths[1])).toBe("settings");
  });

  it("should extract key before dot separator", () => {
    // Arrange
    const simplePath = "user.name";
    const nestedPath = "user.profile.email";

    // Act
    const simpleResult = extractTopLevelKey(simplePath);
    const nestedResult = extractTopLevelKey(nestedPath);

    // Assert
    expect(simpleResult).toBe("user");
    expect(nestedResult).toBe("user");
  });

  it("should extract key before bracket separator", () => {
    // Arrange
    const arrayPath = "user[0]";
    const nestedArrayPath = "tags[0].name";

    // Act
    const arrayResult = extractTopLevelKey(arrayPath);
    const nestedArrayResult = extractTopLevelKey(nestedArrayPath);

    // Assert
    expect(arrayResult).toBe("user");
    expect(nestedArrayResult).toBe("tags");
  });

  it("should handle root array elements", () => {
    // Arrange
    const paths = ["[0]", "[0].name", "[10]", "[123].value"];

    // Act & Assert
    expect(extractTopLevelKey(paths[0])).toBe("[0]");
    expect(extractTopLevelKey(paths[1])).toBe("[0]");
    expect(extractTopLevelKey(paths[2])).toBe("[10]");
    expect(extractTopLevelKey(paths[3])).toBe("[123]");
  });

  it("should take earlier separator when both exist", () => {
    // Arrange
    const dotFirstPath = "user.tags[0]";
    const bracketFirstPath = "items[0].name";

    // Act
    const dotFirstResult = extractTopLevelKey(dotFirstPath);
    const bracketFirstResult = extractTopLevelKey(bracketFirstPath);

    // Assert
    expect(dotFirstResult).toBe("user");
    expect(bracketFirstResult).toBe("items");
  });
});

describe("computeVisibleTopLevelKeys", () => {
  it("should return empty set for empty paths", () => {
    // Arrange
    const paths: VisiblePathItem[] = [];

    // Act
    const result = computeVisibleTopLevelKeys(paths);

    // Assert
    expect(result).toEqual(new Set());
  });

  it("should extract unique top-level keys from paths", () => {
    // Arrange
    const paths: VisiblePathItem[] = [
      { path: "user", value: {} },
      { path: "user.name", value: "John" },
      { path: "user.email", value: "john@example.com" },
      { path: "settings", value: {} },
    ];

    // Act
    const result = computeVisibleTopLevelKeys(paths);

    // Assert
    expect(result).toEqual(new Set(["user", "settings"]));
  });

  it("should handle array paths", () => {
    // Arrange
    const paths: VisiblePathItem[] = [
      { path: "tags", value: [] },
      { path: "tags[0]", value: "tag1" },
      { path: "tags[1]", value: "tag2" },
    ];

    // Act
    const result = computeVisibleTopLevelKeys(paths);

    // Assert
    expect(result).toEqual(new Set(["tags"]));
  });

  it("should handle root array elements", () => {
    // Arrange
    const paths: VisiblePathItem[] = [
      { path: "[0]", value: "a" },
      { path: "[0].name", value: "John" },
      { path: "[1]", value: "b" },
      { path: "[2].value", value: 42 },
    ];

    // Act
    const result = computeVisibleTopLevelKeys(paths);

    // Assert
    expect(result).toEqual(new Set(["[0]", "[1]", "[2]"]));
  });

  it("should handle mixed object and nested paths", () => {
    // Arrange
    const paths: VisiblePathItem[] = [
      { path: "user.profile.name", value: "John" },
      { path: "settings.theme", value: "dark" },
      { path: "tags[0]", value: "tag1" },
    ];

    // Act
    const result = computeVisibleTopLevelKeys(paths);

    // Assert
    expect(result).toEqual(new Set(["user", "settings", "tags"]));
  });
});

describe("getValuePreview", () => {
  it("should return 'null' for null values", () => {
    // Arrange
    const value = null;

    // Act
    const result = getValuePreview(value);

    // Assert
    expect(result).toBe("null");
  });

  it("should return array length for arrays", () => {
    // Arrange
    const emptyArray: number[] = [];
    const filledArray = [1, 2, 3];

    // Act
    const emptyResult = getValuePreview(emptyArray);
    const filledResult = getValuePreview(filledArray);

    // Assert
    expect(emptyResult).toBe("Array[0]");
    expect(filledResult).toBe("Array[3]");
  });

  it("should return object key count for objects", () => {
    // Arrange
    const emptyObject = {};
    const filledObject = { a: 1, b: 2 };

    // Act
    const emptyResult = getValuePreview(emptyObject);
    const filledResult = getValuePreview(filledObject);

    // Assert
    expect(emptyResult).toBe("Object{0}");
    expect(filledResult).toBe("Object{2}");
  });

  it("should truncate long strings", () => {
    // Arrange
    const longString = "a".repeat(50);

    // Act
    const result = getValuePreview(longString);

    // Assert
    expect(result).toBe(`"${"a".repeat(30)}..."`);
  });

  it("should not truncate short strings", () => {
    // Arrange
    const shortString = "hello";

    // Act
    const result = getValuePreview(shortString);

    // Assert
    expect(result).toBe('"hello"');
  });

  it("should stringify numbers", () => {
    // Arrange
    const integer = 42;
    const decimal = 3.14;

    // Act
    const integerResult = getValuePreview(integer);
    const decimalResult = getValuePreview(decimal);

    // Assert
    expect(integerResult).toBe("42");
    expect(decimalResult).toBe("3.14");
  });

  it("should stringify booleans", () => {
    // Arrange
    const trueValue = true;
    const falseValue = false;

    // Act
    const trueResult = getValuePreview(trueValue);
    const falseResult = getValuePreview(falseValue);

    // Assert
    expect(trueResult).toBe("true");
    expect(falseResult).toBe("false");
  });
});
