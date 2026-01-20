import { describe, it, expect } from "vitest";
import {
  parseEntityReferences,
  parseEntityReferencesToParts,
} from "./entityReferences";

describe("parseEntityReferences", () => {
  it("should replace span reference with entity name", () => {
    const entityMap = new Map([
      ["01978716-1435-7749-b575-ff4982e17264", "answer"],
    ]);
    const input =
      "Fetching span details for {span:01978716-1435-7749-b575-ff4982e17264}";
    const expected = "Fetching span details for answer";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle multiple references in one string", () => {
    const entityMap = new Map([
      ["span-1", "first_span"],
      ["span-2", "second_span"],
      ["span-3", "third_span"],
    ]);
    const input = "Processing {span:span-1}, {span:span-2}, and {span:span-3}";
    const expected = "Processing first_span, second_span, and third_span";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should fall back to ID when entity not found", () => {
    const entityMap = new Map([["known-id", "known_name"]]);
    const input = "Fetching {span:unknown-id}";
    const expected = "Fetching unknown-id";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should keep original text for unknown entity types", () => {
    const entityMap = new Map([["some-id", "some_name"]]);
    const input = "Fetching {unknown:some-id}";
    const expected = "Fetching {unknown:some-id}";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle mixed known and unknown references", () => {
    const entityMap = new Map([
      ["span-1", "my_span"],
      ["span-2", "another_span"],
    ]);
    const input = "Found {span:span-1} and {unknown:some-id} and {span:span-2}";
    const expected = "Found my_span and {unknown:some-id} and another_span";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle empty string", () => {
    const entityMap = new Map([["id", "name"]]);
    const input = "";
    const expected = "";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle text with no references", () => {
    const entityMap = new Map([["id", "name"]]);
    const input = "This is plain text without any references";
    const expected = "This is plain text without any references";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle malformed references gracefully", () => {
    const entityMap = new Map([["valid-id", "name"]]);
    const input = "Text with {incomplete and {span:valid-id} reference";
    const expected = "Text with {incomplete and name reference";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle empty entity map", () => {
    const entityMap = new Map();
    const input = "Fetching {span:some-id}";
    const expected = "Fetching some-id";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle references with special characters in ID", () => {
    const entityMap = new Map([
      ["01978716-1435-7749-b575-ff4982e17264", "complex_span_name"],
    ]);
    const input = "Processing {span:01978716-1435-7749-b575-ff4982e17264}";
    const expected = "Processing complex_span_name";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle consecutive references", () => {
    const entityMap = new Map([
      ["id1", "name1"],
      ["id2", "name2"],
    ]);
    const input = "{span:id1}{span:id2}";
    const expected = "name1name2";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should preserve whitespace around references", () => {
    const entityMap = new Map([["id", "name"]]);
    const input = "Before   {span:id}   after";
    const expected = "Before   name   after";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should handle references at start and end of string", () => {
    const entityMap = new Map([
      ["start-id", "start_name"],
      ["end-id", "end_name"],
    ]);
    const input = "{span:start-id} middle text {span:end-id}";
    const expected = "start_name middle text end_name";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });
});

describe("parseEntityReferencesToParts", () => {
  it("should parse text with span reference into parts", () => {
    const entityMap = new Map([
      ["01978716-1435-7749-b575-ff4982e17264", "answer"],
    ]);
    const input =
      "Fetching span details for {span:01978716-1435-7749-b575-ff4982e17264}";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "text", content: "Fetching span details for " },
      {
        type: "entity",
        entity: {
          type: "span",
          id: "01978716-1435-7749-b575-ff4982e17264",
          name: "answer",
        },
      },
    ]);
  });

  it("should handle multiple references", () => {
    const entityMap = new Map([
      ["span-1", "first"],
      ["span-2", "second"],
    ]);
    const input = "Processing {span:span-1} and {span:span-2}";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "text", content: "Processing " },
      { type: "entity", entity: { type: "span", id: "span-1", name: "first" } },
      { type: "text", content: " and " },
      {
        type: "entity",
        entity: { type: "span", id: "span-2", name: "second" },
      },
    ]);
  });

  it("should fall back to ID when entity not found", () => {
    const entityMap = new Map();
    const input = "Fetching {span:unknown-id}";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "text", content: "Fetching " },
      {
        type: "entity",
        entity: { type: "span", id: "unknown-id", name: "unknown-id" },
      },
    ]);
  });

  it("should keep unknown entity types as text", () => {
    const entityMap = new Map([["some-id", "some_name"]]);
    const input = "Fetching {unknown:some-id}";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "text", content: "Fetching " },
      { type: "text", content: "{unknown:some-id}" },
    ]);
  });

  it("should handle plain text without references", () => {
    const entityMap = new Map();
    const input = "Plain text without references";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "text", content: "Plain text without references" },
    ]);
  });

  it("should handle consecutive references", () => {
    const entityMap = new Map([
      ["id1", "name1"],
      ["id2", "name2"],
    ]);
    const input = "{span:id1}{span:id2}";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "entity", entity: { type: "span", id: "id1", name: "name1" } },
      { type: "entity", entity: { type: "span", id: "id2", name: "name2" } },
    ]);
  });

  it("should handle reference at start of string", () => {
    const entityMap = new Map([["id", "name"]]);
    const input = "{span:id} followed by text";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "entity", entity: { type: "span", id: "id", name: "name" } },
      { type: "text", content: " followed by text" },
    ]);
  });

  it("should handle reference at end of string", () => {
    const entityMap = new Map([["id", "name"]]);
    const input = "Text before {span:id}";

    const result = parseEntityReferencesToParts(input, entityMap);

    expect(result).toEqual([
      { type: "text", content: "Text before " },
      { type: "entity", entity: { type: "span", id: "id", name: "name" } },
    ]);
  });
});
