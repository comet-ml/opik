import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { Opik } from "opik";
import { MockInstance } from "vitest";
import { SearchTimeoutError } from "@/errors";
import * as searchHelpers from "@/utils/searchHelpers";
import { createMockSpans, createMockSpan } from "../utils/searchHelpersMocks";

// Mock the search helpers module
vi.mock("@/utils/searchHelpers", async () => {
  const actual = await vi.importActual<typeof searchHelpers>(
    "@/utils/searchHelpers"
  );
  return {
    ...actual,
    searchSpansWithFilters: vi.fn(),
    searchAndWaitForDone: vi.fn(),
  };
});

describe("OpikClient searchSpans", () => {
  let client: Opik;
  let searchSpansWithFiltersSpy: MockInstance<
    typeof searchHelpers.searchSpansWithFilters
  >;
  let searchAndWaitForDoneSpy: MockInstance<
    typeof searchHelpers.searchAndWaitForDone
  >;

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });

    searchSpansWithFiltersSpy = vi
      .spyOn(searchHelpers, "searchSpansWithFilters")
      .mockResolvedValue([]);

    searchAndWaitForDoneSpy = vi
      .spyOn(searchHelpers, "searchAndWaitForDone")
      .mockImplementation(async (fn) => await fn());
  });

  afterEach(() => {
    searchSpansWithFiltersSpy.mockRestore();
    searchAndWaitForDoneSpy.mockRestore();
  });

  describe("basic search functionality", () => {
    it("should search spans with default config values", async () => {
      const mockSpans = createMockSpans(2);
      searchSpansWithFiltersSpy.mockResolvedValue(mockSpans);

      const result = await client.searchSpans();

      expect(result).toEqual(mockSpans);
      expect(searchSpansWithFiltersSpy).toHaveBeenCalledWith(
        client.api,
        "test-project",
        null,
        1000,
        true,
        undefined
      );
    });

    it("should override default values with provided options", async () => {
      searchSpansWithFiltersSpy.mockResolvedValue([]);

      await client.searchSpans({
        projectName: "custom-project",
        maxResults: 50,
        truncate: false,
        exclude: ["feedback_scores"],
      });

      const callArgs = searchSpansWithFiltersSpy.mock.calls[0];
      expect(callArgs[1]).toBe("custom-project");
      expect(callArgs[3]).toBe(50);
      expect(callArgs[4]).toBe(false);
      expect(callArgs[5]).toEqual(["feedback_scores"]);
    });

    it("should pass multiple exclude fields", async () => {
      searchSpansWithFiltersSpy.mockResolvedValue([]);

      await client.searchSpans({
        exclude: ["feedback_scores", "input", "output"],
      });

      const callArgs = searchSpansWithFiltersSpy.mock.calls[0];
      expect(callArgs[5]).toEqual(["feedback_scores", "input", "output"]);
    });
  });

  describe("OQL filter parsing", () => {
    it("should parse OQL filter string and pass to API", async () => {
      searchSpansWithFiltersSpy.mockResolvedValue([]);

      await client.searchSpans({
        filterString: 'name = "test" and duration > 100',
      });

      const callArgs = searchSpansWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters).toBeDefined();
      expect(filters).toHaveLength(2);
      expect(filters![0]).toMatchObject({ field: "name", operator: "=" });
      expect(filters![1]).toMatchObject({ field: "duration", operator: ">" });
    });

    it("should handle filters with metadata and feedback_scores keys", async () => {
      searchSpansWithFiltersSpy.mockResolvedValue([]);

      await client.searchSpans({
        filterString:
          'metadata.version = "1.0" and feedback_scores."quality" > 0.8',
      });

      const callArgs = searchSpansWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters![0]).toMatchObject({
        field: "metadata",
        key: "version",
      });
      expect(filters![1]).toMatchObject({
        field: "feedback_scores",
        key: "quality",
      });
    });

    it("should propagate OQL parsing errors", async () => {
      await expect(
        client.searchSpans({ filterString: "invalid ===" })
      ).rejects.toThrow();
    });
  });

  describe("polling with waitForAtLeast", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      searchAndWaitForDoneSpy.mockImplementation(async (fn) => await fn());
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should not use polling when waitForAtLeast is not provided", async () => {
      const mockSpans = createMockSpans(1);
      searchSpansWithFiltersSpy.mockResolvedValue(mockSpans);

      const result = await client.searchSpans();

      expect(result).toEqual(mockSpans);
      expect(searchAndWaitForDoneSpy).not.toHaveBeenCalled();
    });

    it("should use polling when waitForAtLeast is provided", async () => {
      const mockSpans = createMockSpans(5);
      searchSpansWithFiltersSpy.mockResolvedValue(mockSpans);
      searchAndWaitForDoneSpy.mockResolvedValue(mockSpans);

      await client.searchSpans({
        waitForAtLeast: 5,
        waitForTimeout: 10,
      });

      expect(searchAndWaitForDoneSpy).toHaveBeenCalledWith(
        expect.any(Function),
        5,
        10000, // timeout converted to ms
        5000 // 5 second poll interval
      );
    });

    it("should throw SearchTimeoutError when insufficient results after timeout", async () => {
      const mockSpan = createMockSpans(1);
      searchSpansWithFiltersSpy.mockResolvedValue(mockSpan);
      searchAndWaitForDoneSpy.mockResolvedValue(mockSpan);

      await expect(
        client.searchSpans({
          waitForAtLeast: 10,
          waitForTimeout: 1,
        })
      ).rejects.toThrow(SearchTimeoutError);

      await expect(
        client.searchSpans({
          waitForAtLeast: 10,
          waitForTimeout: 1,
        })
      ).rejects.toThrow(/expected 10 spans, but only 1 were found/);
    });

    it("should use default timeout of 60 seconds", async () => {
      searchSpansWithFiltersSpy.mockResolvedValue([]);
      searchAndWaitForDoneSpy.mockResolvedValue([]);

      await expect(
        client.searchSpans({ waitForAtLeast: 100 })
      ).rejects.toThrow(/Timeout after 60 seconds/);
    });
  });

  describe("complete workflow integration", () => {
    it("should handle complex search with all options", async () => {
      const mockSpans = createMockSpans(3);

      searchAndWaitForDoneSpy.mockImplementation(async (searchFn) => {
        return await searchFn();
      });
      searchSpansWithFiltersSpy.mockResolvedValue(mockSpans);

      const result = await client.searchSpans({
        projectName: "custom-project",
        filterString: 'name = "test-span" and duration > 100',
        maxResults: 50,
        truncate: false,
        exclude: ["feedback_scores"],
        waitForAtLeast: 3,
        waitForTimeout: 10,
      });

      expect(result).toEqual(mockSpans);

      const callArgs = searchSpansWithFiltersSpy.mock.calls[0];
      expect(callArgs[1]).toBe("custom-project");
      expect(callArgs[2]).toHaveLength(2);
      expect(callArgs[3]).toBe(50);
      expect(callArgs[4]).toBe(false);
      expect(callArgs[5]).toEqual(["feedback_scores"]);

      expect(searchAndWaitForDoneSpy).toHaveBeenCalled();
    });
  });

  describe("error handling", () => {
    it("should propagate API errors", async () => {
      const apiError = new Error("API request failed");
      searchSpansWithFiltersSpy.mockRejectedValue(apiError);

      await expect(client.searchSpans()).rejects.toThrow("API request failed");
    });

    it("should handle network timeouts", async () => {
      const networkError = new Error("Network timeout");
      searchSpansWithFiltersSpy.mockRejectedValue(networkError);

      await expect(client.searchSpans()).rejects.toThrow("Network timeout");
    });
  });

  describe("edge cases", () => {
    it("should handle empty results gracefully", async () => {
      searchSpansWithFiltersSpy.mockResolvedValue([]);

      const result = await client.searchSpans();

      expect(result).toEqual([]);
    });

    it("should handle large result sets", async () => {
      const mockSpans = createMockSpans(1000);
      searchSpansWithFiltersSpy.mockResolvedValue(mockSpans);

      const result = await client.searchSpans({ maxResults: 1000 });

      expect(result).toHaveLength(1000);
    });

    it("should preserve all span fields", async () => {
      const completeSpan = createMockSpan({
        id: "span-1",
        traceId: "trace-1",
        name: "Complete Span",
        type: "llm",
        metadata: { version: "1.0", author: "test" },
        tags: ["tag1", "tag2"],
        feedbackScores: [{ name: "quality", value: 0.9, source: "ui" }],
      });

      searchSpansWithFiltersSpy.mockResolvedValue([completeSpan]);

      const result = await client.searchSpans();

      expect(result[0]).toMatchObject({
        id: "span-1",
        traceId: "trace-1",
        name: "Complete Span",
        type: "llm",
        metadata: { version: "1.0", author: "test" },
        tags: ["tag1", "tag2"],
      });
    });
  });
});
