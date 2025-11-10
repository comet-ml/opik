import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { Opik } from "opik";
import { MockInstance } from "vitest";
import { SearchTimeoutError } from "@/errors";
import * as searchHelpers from "@/utils/searchHelpers";
import { createMockTraces, createMockTrace } from "../utils/searchHelpersMocks";

// Mock the search helpers module
vi.mock("@/utils/searchHelpers", async () => {
  const actual = await vi.importActual<typeof searchHelpers>(
    "@/utils/searchHelpers"
  );
  return {
    ...actual,
    searchTracesWithFilters: vi.fn(),
    searchAndWaitForDone: vi.fn(),
  };
});

describe("OpikClient searchTraces", () => {
  let client: Opik;
  let searchTracesWithFiltersSpy: MockInstance<
    typeof searchHelpers.searchTracesWithFilters
  >;
  let searchAndWaitForDoneSpy: MockInstance<
    typeof searchHelpers.searchAndWaitForDone
  >;

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });

    searchTracesWithFiltersSpy = vi
      .spyOn(searchHelpers, "searchTracesWithFilters")
      .mockResolvedValue([]);

    searchAndWaitForDoneSpy = vi
      .spyOn(searchHelpers, "searchAndWaitForDone")
      .mockImplementation(async (fn) => await fn());
  });

  afterEach(() => {
    searchTracesWithFiltersSpy.mockRestore();
    searchAndWaitForDoneSpy.mockRestore();
  });

  describe("basic search functionality", () => {
    it("should search traces with default config values", async () => {
      const mockTraces = createMockTraces(2);
      searchTracesWithFiltersSpy.mockResolvedValue(mockTraces);

      const result = await client.searchTraces();

      expect(result).toEqual(mockTraces);
      expect(searchTracesWithFiltersSpy).toHaveBeenCalledWith(
        client.api,
        "test-project",
        null,
        1000,
        true
      );
    });

    it("should override default values with provided options", async () => {
      searchTracesWithFiltersSpy.mockResolvedValue([]);

      await client.searchTraces({
        projectName: "custom-project",
        maxResults: 50,
        truncate: false,
      });

      const callArgs = searchTracesWithFiltersSpy.mock.calls[0];
      expect(callArgs[1]).toBe("custom-project");
      expect(callArgs[3]).toBe(50);
      expect(callArgs[4]).toBe(false);
    });
  });

  describe("OQL filter parsing", () => {
    it("should parse OQL filter string and pass to API", async () => {
      searchTracesWithFiltersSpy.mockResolvedValue([]);

      await client.searchTraces({
        filterString: 'name = "test" and duration > 100',
      });

      const callArgs = searchTracesWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters).toBeDefined();
      expect(filters).toHaveLength(2);
      expect(filters![0]).toMatchObject({ field: "name", operator: "=" });
      expect(filters![1]).toMatchObject({ field: "duration", operator: ">" });
    });

    it("should handle filters with metadata and feedback_scores keys", async () => {
      searchTracesWithFiltersSpy.mockResolvedValue([]);

      await client.searchTraces({
        filterString:
          'metadata.version = "1.0" and feedback_scores."quality" > 0.8',
      });

      const callArgs = searchTracesWithFiltersSpy.mock.calls[0];
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
        client.searchTraces({ filterString: "invalid ===" })
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
      const mockTraces = createMockTraces(1);
      searchTracesWithFiltersSpy.mockResolvedValue(mockTraces);

      const result = await client.searchTraces();

      expect(result).toEqual(mockTraces);
      expect(searchAndWaitForDoneSpy).not.toHaveBeenCalled();
    });

    it("should use polling when waitForAtLeast is provided", async () => {
      const mockTraces = createMockTraces(5);
      searchTracesWithFiltersSpy.mockResolvedValue(mockTraces);
      searchAndWaitForDoneSpy.mockResolvedValue(mockTraces);

      await client.searchTraces({
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
      const mockTrace = createMockTraces(1);
      searchTracesWithFiltersSpy.mockResolvedValue(mockTrace);
      searchAndWaitForDoneSpy.mockResolvedValue(mockTrace);

      await expect(
        client.searchTraces({
          waitForAtLeast: 10,
          waitForTimeout: 1,
        })
      ).rejects.toThrow(SearchTimeoutError);

      await expect(
        client.searchTraces({
          waitForAtLeast: 10,
          waitForTimeout: 1,
        })
      ).rejects.toThrow(/expected 10 traces, but only 1 were found/);
    });

    it("should use default timeout of 60 seconds", async () => {
      searchTracesWithFiltersSpy.mockResolvedValue([]);
      searchAndWaitForDoneSpy.mockResolvedValue([]);

      await expect(
        client.searchTraces({ waitForAtLeast: 100 })
      ).rejects.toThrow(/Timeout after 60 seconds/);
    });
  });

  describe("complete workflow integration", () => {
    it("should handle complex search with all options", async () => {
      const mockTraces = createMockTraces(3);

      searchAndWaitForDoneSpy.mockImplementation(async (searchFn) => {
        return await searchFn();
      });
      searchTracesWithFiltersSpy.mockResolvedValue(mockTraces);

      const result = await client.searchTraces({
        projectName: "custom-project",
        filterString: 'status = "active" and duration > 100',
        maxResults: 50,
        truncate: false,
        waitForAtLeast: 3,
        waitForTimeout: 10,
      });

      expect(result).toEqual(mockTraces);

      const callArgs = searchTracesWithFiltersSpy.mock.calls[0];
      expect(callArgs[1]).toBe("custom-project");
      expect(callArgs[2]).toHaveLength(2);
      expect(callArgs[3]).toBe(50);
      expect(callArgs[4]).toBe(false);

      expect(searchAndWaitForDoneSpy).toHaveBeenCalled();
    });
  });

  describe("error handling", () => {
    it("should propagate API errors", async () => {
      const apiError = new Error("API request failed");
      searchTracesWithFiltersSpy.mockRejectedValue(apiError);

      await expect(client.searchTraces()).rejects.toThrow("API request failed");
    });

    it("should handle network timeouts", async () => {
      const networkError = new Error("Network timeout");
      searchTracesWithFiltersSpy.mockRejectedValue(networkError);

      await expect(client.searchTraces()).rejects.toThrow("Network timeout");
    });
  });

  describe("edge cases", () => {
    it("should handle empty results gracefully", async () => {
      searchTracesWithFiltersSpy.mockResolvedValue([]);

      const result = await client.searchTraces();

      expect(result).toEqual([]);
    });

    it("should handle large result sets", async () => {
      const mockTraces = createMockTraces(1000);
      searchTracesWithFiltersSpy.mockResolvedValue(mockTraces);

      const result = await client.searchTraces({ maxResults: 1000 });

      expect(result).toHaveLength(1000);
    });

    it("should preserve all trace fields", async () => {
      const completeTrace = createMockTrace({
        id: "trace-1",
        name: "Complete Trace",
        metadata: { version: "1.0", author: "test" },
        tags: ["tag1", "tag2"],
        feedbackScores: [{ name: "quality", value: 0.9, source: "ui" }],
      });

      searchTracesWithFiltersSpy.mockResolvedValue([completeTrace]);

      const result = await client.searchTraces();

      expect(result[0]).toMatchObject({
        id: "trace-1",
        name: "Complete Trace",
        metadata: { version: "1.0", author: "test" },
        tags: ["tag1", "tag2"],
      });
    });
  });
});
