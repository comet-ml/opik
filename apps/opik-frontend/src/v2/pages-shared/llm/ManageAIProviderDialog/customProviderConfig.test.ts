import { describe, it, expect } from "vitest";

import {
  configStringToQueryParamsArray,
  convertHeadersForAPI,
  queryParamsArrayToConfigString,
} from "./customProviderConfig";

describe("customProviderConfig", () => {
  describe("queryParamsArrayToConfigString", () => {
    it("returns undefined when the array is undefined", () => {
      expect(queryParamsArrayToConfigString(undefined)).toBeUndefined();
    });

    it("returns undefined when the array is empty", () => {
      expect(queryParamsArrayToConfigString([])).toBeUndefined();
    });

    it("returns undefined when every key is blank", () => {
      expect(
        queryParamsArrayToConfigString([
          { key: "  ", value: "x" },
          { key: "", value: "y" },
        ]),
      ).toBeUndefined();
    });

    it("serializes single entry to a JSON map", () => {
      expect(
        queryParamsArrayToConfigString([
          { key: "api-version", value: "2024-08-01-preview" },
        ]),
      ).toBe('{"api-version":"2024-08-01-preview"}');
    });

    it("serializes multiple entries and skips blank keys", () => {
      const result = queryParamsArrayToConfigString([
        { key: "api-version", value: "2024-08-01-preview" },
        { key: "  ", value: "ignored" },
        { key: "other", value: "value" },
      ]);
      expect(result).toBeDefined();
      const parsed = JSON.parse(result as string);
      expect(parsed).toEqual({
        "api-version": "2024-08-01-preview",
        other: "value",
      });
    });

    it("trims whitespace in keys but preserves values verbatim", () => {
      const result = queryParamsArrayToConfigString([
        { key: "  api-version  ", value: "  with spaces  " },
      ]);
      expect(JSON.parse(result as string)).toEqual({
        "api-version": "  with spaces  ",
      });
    });
  });

  describe("configStringToQueryParamsArray", () => {
    it("returns an empty array when raw is undefined", () => {
      expect(configStringToQueryParamsArray(undefined)).toEqual([]);
    });

    it("returns an empty array when raw is empty", () => {
      expect(configStringToQueryParamsArray("")).toEqual([]);
    });

    it("returns an empty array when raw is not valid JSON", () => {
      expect(configStringToQueryParamsArray("{not json")).toEqual([]);
    });

    it("parses a well-formed JSON map and assigns a UUID id per entry", () => {
      const result = configStringToQueryParamsArray(
        '{"api-version":"2024-08-01-preview","other":"value"}',
      );
      expect(result).toHaveLength(2);
      expect(result.map(({ key, value }) => ({ key, value }))).toEqual([
        { key: "api-version", value: "2024-08-01-preview" },
        { key: "other", value: "value" },
      ]);
      // Each entry must have a non-empty id so React can key it.
      result.forEach((entry) => expect(entry.id.length).toBeGreaterThan(0));
      // IDs must be unique.
      expect(new Set(result.map((e) => e.id)).size).toBe(result.length);
    });

    it("round-trips cleanly with queryParamsArrayToConfigString", () => {
      const input = [
        { key: "api-version", value: "2024-08-01-preview", id: "a" },
        { key: "other", value: "value", id: "b" },
      ];
      const serialized = queryParamsArrayToConfigString(input);
      const restored = configStringToQueryParamsArray(serialized);
      expect(restored.map(({ key, value }) => ({ key, value }))).toEqual(
        input.map(({ key, value }) => ({ key, value })),
      );
    });
  });

  describe("convertHeadersForAPI", () => {
    it("returns undefined for undefined input", () => {
      expect(convertHeadersForAPI(undefined, false)).toBeUndefined();
      expect(convertHeadersForAPI(undefined, true)).toBeUndefined();
    });

    it("returns undefined for an empty array when creating", () => {
      expect(convertHeadersForAPI([], false)).toBeUndefined();
    });

    it("returns an empty object for an empty array when editing (clears the backend field)", () => {
      expect(convertHeadersForAPI([], true)).toEqual({});
    });

    it("converts a populated array to an object and skips blank keys", () => {
      expect(
        convertHeadersForAPI(
          [
            { key: "api-key", value: "secret" },
            { key: "   ", value: "ignored" },
            { key: "X-Other", value: "value" },
          ],
          false,
        ),
      ).toEqual({
        "api-key": "secret",
        "X-Other": "value",
      });
    });
  });
});
