import { describe, it, expect } from "vitest";
import { parseEntityReferences } from "./entityReferences";

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

  it("should fall back to original reference when entity not found", () => {
    const entityMap = new Map([["known-id", "known_name"]]);
    const input = "Fetching {span:unknown-id}";
    const expected = "Fetching {span:unknown-id}";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should work for any entity type if ID is in the map", () => {
    const entityMap = new Map([["some-id", "some_name"]]);
    const input = "Fetching {unknown:some-id}";
    const expected = "Fetching some_name";

    expect(parseEntityReferences(input, entityMap)).toBe(expected);
  });

  it("should keep original reference for unknown entity types when ID not in map", () => {
    const entityMap = new Map([["other-id", "other_name"]]);
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
    const expected = "Fetching {span:some-id}";

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
