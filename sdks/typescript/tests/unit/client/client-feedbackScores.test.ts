import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { Opik, FeedbackScoreData } from "opik";

interface FeedbackScoreTestConfig {
  entityType: "trace" | "span";
  logMethod: (client: Opik) => (scores: FeedbackScoreData[]) => void;
  getBatchQueueSpy: (client: Opik) => MockInstance;
}

function createFeedbackScoreTests(config: FeedbackScoreTestConfig): void {
  const { entityType, logMethod, getBatchQueueSpy } = config;
  const entityId = `${entityType}-1`;
  const altEntityId1 = `${entityType}-2`;
  const altEntityId2 = `${entityType}-3`;

  describe(`OpikClient log${entityType === "trace" ? "Traces" : "Spans"}FeedbackScores`, () => {
    let client: Opik;
    let batchQueueSpy: MockInstance;

    beforeEach(() => {
      client = new Opik({ projectName: "test-project" });
      batchQueueSpy = getBatchQueueSpy(client);
    });

    afterEach(() => {
      batchQueueSpy.mockRestore();
    });

    it(`should add scores to ${entityType} feedback batch queue`, () => {
      logMethod(client)([
        { id: entityId, name: "quality", value: 0.9 },
        { id: altEntityId1, name: "relevance", value: 0.8 },
      ]);

      expect(batchQueueSpy).toHaveBeenCalledTimes(2);
      expect(batchQueueSpy).toHaveBeenNthCalledWith(1, {
        id: entityId,
        name: "quality",
        value: 0.9,
        projectName: "test-project",
        source: "sdk",
      });
      expect(batchQueueSpy).toHaveBeenNthCalledWith(2, {
        id: altEntityId1,
        name: "relevance",
        value: 0.8,
        projectName: "test-project",
        source: "sdk",
      });
    });

    it("should use client default projectName when not provided", () => {
      logMethod(client)([{ id: entityId, name: "accuracy", value: 0.95 }]);

      expect(batchQueueSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          projectName: "test-project",
        })
      );
    });

    it("should use provided projectName when specified", () => {
      logMethod(client)([
        {
          id: entityId,
          name: "accuracy",
          value: 0.95,
          projectName: "custom-project",
        },
      ]);

      expect(batchQueueSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          projectName: "custom-project",
        })
      );
    });

    it(`should handle multiple scores for same ${entityType}`, () => {
      logMethod(client)([
        { id: entityId, name: "quality", value: 0.9 },
        { id: entityId, name: "relevance", value: 0.8 },
        { id: entityId, name: "accuracy", value: 0.95 },
      ]);

      expect(batchQueueSpy).toHaveBeenCalledTimes(3);
      const calls = batchQueueSpy.mock.calls;
      expect(calls.every((call) => call[0].id === entityId)).toBe(true);
    });

    it(`should handle multiple scores for different ${entityType}s`, () => {
      logMethod(client)([
        { id: entityId, name: "quality", value: 0.9 },
        { id: altEntityId1, name: "quality", value: 0.7 },
        { id: altEntityId2, name: "quality", value: 0.85 },
      ]);

      expect(batchQueueSpy).toHaveBeenCalledTimes(3);
      const calls = batchQueueSpy.mock.calls;
      expect(calls[0][0].id).toBe(entityId);
      expect(calls[1][0].id).toBe(altEntityId1);
      expect(calls[2][0].id).toBe(altEntityId2);
    });

    it("should set source as sdk", () => {
      logMethod(client)([{ id: entityId, name: "quality", value: 0.9 }]);

      expect(batchQueueSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          source: "sdk",
        })
      );
    });

    it("should handle empty array without error", () => {
      expect(() => {
        logMethod(client)([]);
      }).not.toThrow();

      expect(batchQueueSpy).not.toHaveBeenCalled();
    });

    it("should pass optional fields when provided", () => {
      logMethod(client)([
        {
          id: entityId,
          name: "quality",
          value: 0.9,
          categoryName: "high",
          reason: "Excellent response",
        },
      ]);

      expect(batchQueueSpy).toHaveBeenCalledWith({
        id: entityId,
        name: "quality",
        value: 0.9,
        categoryName: "high",
        reason: "Excellent response",
        projectName: "test-project",
        source: "sdk",
      });
    });

    describe("edge cases", () => {
      it("should handle decimal score values correctly", () => {
        logMethod(client)([
          { id: entityId, name: "precision-1", value: 0.123456 },
          { id: altEntityId1, name: "precision-2", value: 0.999999 },
          { id: altEntityId2, name: "precision-3", value: 0.0 },
        ]);

        expect(batchQueueSpy).toHaveBeenCalledTimes(3);
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          1,
          expect.objectContaining({ value: 0.123456 })
        );
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          2,
          expect.objectContaining({ value: 0.999999 })
        );
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          3,
          expect.objectContaining({ value: 0.0 })
        );
      });

      it("should handle boundary score values", () => {
        logMethod(client)([
          { id: entityId, name: "boundary-min", value: 0.0 },
          { id: altEntityId1, name: "boundary-max", value: 1.0 },
        ]);

        expect(batchQueueSpy).toHaveBeenCalledTimes(2);
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          1,
          expect.objectContaining({ value: 0.0 })
        );
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          2,
          expect.objectContaining({ value: 1.0 })
        );
      });

      it("should handle special characters in reason field", () => {
        const specialReason =
          'Contains "quotes", newlines,\nand unicode: 日本語 🎉';

        logMethod(client)([
          {
            id: entityId,
            name: "special-chars",
            value: 0.5,
            reason: specialReason,
          },
        ]);

        expect(batchQueueSpy).toHaveBeenCalledWith(
          expect.objectContaining({
            reason: specialReason,
          })
        );
      });

      it("should handle very long reason strings", () => {
        const longReason = "This is a detailed explanation. ".repeat(50).trim();

        logMethod(client)([
          {
            id: entityId,
            name: "long-reason",
            value: 0.75,
            reason: longReason,
          },
        ]);

        expect(batchQueueSpy).toHaveBeenCalledWith(
          expect.objectContaining({
            reason: longReason,
          })
        );
      });

      it("should handle multiple sequential batch calls", () => {
        // First batch
        logMethod(client)([{ id: entityId, name: "batch-1", value: 0.5 }]);

        // Second batch
        logMethod(client)([{ id: entityId, name: "batch-2", value: 0.6 }]);

        // Third batch
        logMethod(client)([{ id: entityId, name: "batch-3", value: 0.7 }]);

        expect(batchQueueSpy).toHaveBeenCalledTimes(3);
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          1,
          expect.objectContaining({ name: "batch-1", value: 0.5 })
        );
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          2,
          expect.objectContaining({ name: "batch-2", value: 0.6 })
        );
        expect(batchQueueSpy).toHaveBeenNthCalledWith(
          3,
          expect.objectContaining({ name: "batch-3", value: 0.7 })
        );
      });
    });
  });
}

createFeedbackScoreTests({
  entityType: "trace",
  logMethod: (client) => client.logTracesFeedbackScores.bind(client),
  getBatchQueueSpy: (client) =>
    vi.spyOn(client.traceFeedbackScoresBatchQueue, "create"),
});

createFeedbackScoreTests({
  entityType: "span",
  logMethod: (client) => client.logSpansFeedbackScores.bind(client),
  getBatchQueueSpy: (client) =>
    vi.spyOn(client.spanFeedbackScoresBatchQueue, "create"),
});
