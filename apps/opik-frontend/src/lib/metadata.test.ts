import { describe, it, expect } from "vitest";
import { getJSONPaths } from "./utils";
import {
  normalizeMetadataPaths,
  buildDynamicMetadataColumns,
} from "./metadata";

describe("normalizeMetadataPaths", () => {
  it("should filter out paths starting with underscore", () => {
    const paths = [
      "metadata.time_to_first_token",
      "metadata._internal_field",
      "metadata.public_field",
      "metadata._private",
    ];
    const result = normalizeMetadataPaths(paths);
    expect(result).toEqual([
      "metadata.public_field",
      "metadata.time_to_first_token",
    ]);
  });

  it("should filter out paths with array indices", () => {
    const paths = [
      "metadata.simple_field",
      "metadata.array_field[0]",
      "metadata.array_field[1].nested",
      "metadata.another_field",
    ];
    const result = normalizeMetadataPaths(paths);
    expect(result).toEqual([
      "metadata.another_field",
      "metadata.array_field",
      "metadata.simple_field",
    ]);
  });

  it("should extract base array paths from paths with indices", () => {
    const paths = [
      "metadata.items[0].name",
      "metadata.items[1].value",
      "metadata.items[2].id",
    ];
    const result = normalizeMetadataPaths(paths);
    expect(result).toEqual(["metadata.items"]);
  });

  it("should handle nested array paths", () => {
    const paths = [
      "metadata.data[0].field",
      "metadata.data[0].nested[1].value",
      "metadata.simple",
    ];
    const result = normalizeMetadataPaths(paths);
    expect(result).toEqual(["metadata.data", "metadata.simple"]);
  });

  it("should deduplicate paths", () => {
    const paths = [
      "metadata.field",
      "metadata.field",
      "metadata.another",
      "metadata.another",
    ];
    const result = normalizeMetadataPaths(paths);
    expect(result).toEqual(["metadata.another", "metadata.field"]);
  });

  it("should sort paths alphabetically", () => {
    const paths = ["metadata.zebra", "metadata.alpha", "metadata.beta"];
    const result = normalizeMetadataPaths(paths);
    expect(result).toEqual([
      "metadata.alpha",
      "metadata.beta",
      "metadata.zebra",
    ]);
  });

  it("should handle empty array", () => {
    const result = normalizeMetadataPaths([]);
    expect(result).toEqual([]);
  });

  it("should filter out private fields at any level", () => {
    const paths = [
      "metadata.public",
      "metadata._private",
      "metadata.nested._internal",
      "metadata.nested.public",
    ];
    const result = normalizeMetadataPaths(paths);
    // The function filters paths where ANY segment starts with underscore
    // Nested paths like "metadata.nested._internal" are filtered because
    // the "_internal" segment starts with "_"
    expect(result).toEqual(["metadata.nested.public", "metadata.public"]);
  });

  it("should handle array paths with private fields", () => {
    const paths = [
      "metadata.items[0].public",
      "metadata.items[0]._private",
      "metadata._items[0].field",
    ];
    const result = normalizeMetadataPaths(paths);
    expect(result).toEqual(["metadata.items"]);
  });
});

describe("buildDynamicMetadataColumns", () => {
  it("should build columns with metadata prefix stripped from labels", () => {
    const paths = ["metadata.time_to_first_token", "metadata.model_name"];
    const result = buildDynamicMetadataColumns(paths);
    expect(result).toEqual([
      {
        id: "metadata.time_to_first_token",
        label: "time_to_first_token",
        columnType: "string",
      },
      {
        id: "metadata.model_name",
        label: "model_name",
        columnType: "string",
      },
    ]);
  });

  it("should handle paths without metadata prefix", () => {
    const paths = ["custom.path", "another.field"];
    const result = buildDynamicMetadataColumns(paths);
    expect(result).toEqual([
      { id: "custom.path", label: "custom.path", columnType: "string" },
      { id: "another.field", label: "another.field", columnType: "string" },
    ]);
  });

  it("should handle empty array", () => {
    const result = buildDynamicMetadataColumns([]);
    expect(result).toEqual([]);
  });

  it("should handle array base paths", () => {
    const paths = ["metadata.items", "metadata.tags"];
    const result = buildDynamicMetadataColumns(paths);
    expect(result).toEqual([
      { id: "metadata.items", label: "items", columnType: "string" },
      { id: "metadata.tags", label: "tags", columnType: "string" },
    ]);
  });
});

describe("metadata path extraction integration", () => {
  it("should extract paths from simple metadata object", () => {
    const metadata = {
      time_to_first_token: 123,
      model_name: "gpt-4",
      temperature: 0.7,
    };
    const paths = getJSONPaths(metadata, "metadata", []);
    expect(paths).toContain("metadata.time_to_first_token");
    expect(paths).toContain("metadata.model_name");
    expect(paths).toContain("metadata.temperature");
  });

  it("should extract paths from nested metadata object", () => {
    const metadata = {
      performance: {
        latency: 100,
        throughput: 50,
      },
      config: {
        model: "gpt-4",
      },
    };
    const paths = getJSONPaths(metadata, "metadata", []);
    expect(paths).toContain("metadata.performance.latency");
    expect(paths).toContain("metadata.performance.throughput");
    expect(paths).toContain("metadata.config.model");
  });

  it("should extract array base paths from metadata with arrays", () => {
    const metadata = {
      items: [
        { name: "item1", value: 1 },
        { name: "item2", value: 2 },
      ],
      tags: ["tag1", "tag2"],
    };
    const paths = getJSONPaths(metadata, "metadata", []);
    expect(paths).toContain("metadata.items[0].name");
    expect(paths).toContain("metadata.items[0].value");
    expect(paths).toContain("metadata.tags[0]");
  });

  it("should handle empty metadata", () => {
    const paths = getJSONPaths({}, "metadata", []);
    expect(paths).toEqual([]);
  });

  it("should handle metadata with private fields", () => {
    const metadata = {
      public_field: "value",
      _private_field: "hidden",
      _internal: "secret",
    };
    const paths = getJSONPaths(metadata, "metadata", []);
    const normalized = normalizeMetadataPaths(paths);
    expect(normalized).toEqual(["metadata.public_field"]);
  });

  it("should handle complex metadata structure", () => {
    const metadata = {
      time_to_first_token: 123,
      model_name: "gpt-4",
      performance: {
        latency: 100,
        metrics: [10, 20, 30],
      },
      _internal: "hidden",
      items: [{ id: 1 }, { id: 2 }],
    };
    const paths = getJSONPaths(metadata, "metadata", []);
    const normalized = normalizeMetadataPaths(paths);
    const columns = buildDynamicMetadataColumns(normalized);

    expect(normalized).toContain("metadata.items");
    expect(normalized).toContain("metadata.model_name");
    expect(normalized).toContain("metadata.performance.latency");
    expect(normalized).toContain("metadata.time_to_first_token");
    expect(normalized).not.toContain("metadata._internal");

    expect(columns).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: "metadata.items",
          label: "items",
        }),
        expect.objectContaining({
          id: "metadata.model_name",
          label: "model_name",
        }),
      ]),
    );
  });
});
