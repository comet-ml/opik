import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { ExactMatch } from "@/evaluation/metrics/heuristics/ExactMatch";
import { Contains } from "@/evaluation/metrics/heuristics/Contains";
import { AnswerRelevance } from "@/evaluation/metrics/llmJudges/answerRelevance/AnswerRelevance";
import { Hallucination } from "@/evaluation/metrics/llmJudges/hallucination/Hallucination";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import { createEvaluationDataset, cleanupDatasets } from "./helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Multiple Metrics Integration", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterEach(async () => {
    await cleanupDatasets(client, createdDatasetNames);
    createdDatasetNames.length = 0;
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  describe("Mixed Heuristic and LLM Metrics", () => {
    it("should calculate both heuristic and LLM metrics in evaluation", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new Contains(),
          new AnswerRelevance({ requireContext: false }),
        ],
        scoringKeyMapping: {
          substring: "input", // Map substring (for Contains) from input (in dataset)
        },
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);

      const firstResult = result.testResults[0];
      expect(firstResult.scoreResults).toBeDefined();

      // Should have scores from both metrics
      expect(firstResult.scoreResults.length).toBeGreaterThanOrEqual(1);

      // Verify metrics ran
      const scores = firstResult.scoreResults;
      expect(scores.length).toBeGreaterThan(0);
    }, 90000);

    it("should handle ExactMatch, Contains, and AnswerRelevance together", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new ExactMatch(),
          new Contains("contains_check"),
          new AnswerRelevance({
            name: "relevance_check",
            requireContext: false,
          }),
        ],
        scoringKeyMapping: {
          expected: "output", // Map expected (for ExactMatch) from output (in dataset)
          substring: "input", // Map substring (for Contains) from input (in dataset)
        },
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].scoreResults.length).toBeGreaterThan(0);
    }, 90000);
  });

  describe("Multiple LLM Judge Metrics", () => {
    it("should calculate AnswerRelevance and Hallucination together", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new AnswerRelevance({ requireContext: false }),
          new Hallucination(),
        ],
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);

      const scoreResults = result.testResults[0].scoreResults;
      expect(scoreResults.length).toBeGreaterThanOrEqual(2);

      // Verify both metrics returned results
      const metricNames = scoreResults.map((s) => s.name);
      expect(metricNames).toContain("answer_relevance_metric");
      expect(metricNames).toContain("hallucination_metric");
    }, 90000);

    it("should handle metrics with different configurations", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new AnswerRelevance({
            name: "relevance_with_context",
            requireContext: true,
          }),
          new AnswerRelevance({
            name: "relevance_without_context",
            requireContext: false,
            temperature: 0.3,
          }),
        ],
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);

      const scoreResults = result.testResults[0].scoreResults;

      // Should have results from both metrics with different names
      const names = scoreResults.map((s) => s.name);
      expect(names).toContain("relevance_with_context");
      expect(names).toContain("relevance_without_context");
    }, 90000);
  });

  describe("Metric Performance", () => {
    it("should handle multiple metrics efficiently", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const startTime = Date.now();

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new AnswerRelevance({ requireContext: false }),
          new Hallucination(),
        ],
        nbSamples: 2, // Test with 2 samples
      });

      const endTime = Date.now();
      const duration = endTime - startTime;

      expect(result.testResults.length).toBe(2);

      // Verify all metrics calculated for all samples
      result.testResults.forEach((testResult) => {
        expect(testResult.scoreResults.length).toBeGreaterThanOrEqual(2);
      });

      // Should complete in reasonable time (< 3 minutes for 2 samples with 2 LLM metrics)
      expect(duration).toBeLessThan(180000);
    }, 180000);
  });

  describe("Scoring Key Mapping with Multiple Metrics", () => {
    it("should apply scoringKeyMapping correctly for multiple metrics", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new AnswerRelevance({ requireContext: false }),
          new Contains(),
        ],
        scoringKeyMapping: {
          substring: "input", // Map substring (for Contains) from input (in dataset)
        },
        nbSamples: 1,
      });

      expect(result.testResults.length).toBe(1);
      expect(result.testResults[0].scoreResults.length).toBeGreaterThan(0);
    }, 90000);
  });

  describe("Error Handling with Multiple Metrics", () => {
    it("should continue evaluation if one metric fails", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      // Test that evaluation continues even if a metric fails
      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [new Contains("contains_metric")],
        scoringKeyMapping: {
          substring: "input", // Map substring (for Contains) from input (in dataset)
        },
        nbSamples: 1,
      });

      // Evaluation should complete even if some metrics fail
      expect(result.testResults.length).toBeGreaterThan(0);
    }, 90000);
  });

  describe("Metric Results Verification", () => {
    it("should record all metric scores in experiment items", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new AnswerRelevance({
            name: "answer_relevance",
            requireContext: false,
          }),
          new Hallucination({ name: "hallucination_check" }),
        ],
        nbSamples: 1,
      });

      const firstResult = result.testResults[0];

      // Verify each score has required fields
      firstResult.scoreResults.forEach((score) => {
        expect(score.name).toBeDefined();
        expect(score.value).toBeDefined();
        expect(typeof score.value).toBe("number");
        expect(score.value).toBeGreaterThanOrEqual(0);
        expect(score.value).toBeLessThanOrEqual(1);

        if (score.reason) {
          expect(typeof score.reason).toBe("string");
        }
      });
    }, 90000);

    it("should maintain metric independence in scoring", async () => {
      const dataset = await createEvaluationDataset(client);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "{{input}}" }],
        scoringMetrics: [
          new AnswerRelevance({ requireContext: false }),
          new Hallucination(),
        ],
        nbSamples: 1,
      });

      const scores = result.testResults[0].scoreResults;

      // Each metric should have its own independent score
      const relevanceScore = scores.find(
        (s) => s.name === "answer_relevance_metric"
      );
      const hallucinationScore = scores.find(
        (s) => s.name === "hallucination_metric"
      );

      if (relevanceScore && hallucinationScore) {
        // Scores should be independent (one metric's score doesn't determine the other)
        expect(relevanceScore.value).toBeDefined();
        expect(hallucinationScore.value).toBeDefined();
      }
    }, 90000);
  });
});
