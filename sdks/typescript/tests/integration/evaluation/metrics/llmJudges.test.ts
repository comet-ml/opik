import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { AnswerRelevance } from "@/evaluation/metrics/llmJudges/answerRelevance/AnswerRelevance";
import { Hallucination } from "@/evaluation/metrics/llmJudges/hallucination/Hallucination";
import { Moderation } from "@/evaluation/metrics/llmJudges/moderation/Moderation";
import { Usefulness } from "@/evaluation/metrics/llmJudges/usefulness/Usefulness";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../../api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("LLM Judge Metrics Integration", () => {
  let client: Opik;

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  describe("AnswerRelevance Metric", () => {
    it("should score high relevance answer with context", async () => {
      const metric = new AnswerRelevance();

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "The capital of France is Paris.",
        context: [
          "France is a country in Europe.",
          "Paris is the capital city of France.",
        ],
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.value).toBeGreaterThan(0.7); // Should be high relevance
      expect(result.reason).toBeDefined();
      if (result.reason) {
        expect(typeof result.reason).toBe("string");
        expect(result.reason.length).toBeGreaterThan(0);
      }
    }, 30000);

    it("should score low relevance answer with context", async () => {
      const metric = new AnswerRelevance();

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "The weather is nice today.",
        context: [
          "France is a country in Europe.",
          "Paris is the capital city of France.",
        ],
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.value).toBeLessThan(0.3); // Should be low relevance
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should work without context when requireContext is false", async () => {
      const metric = new AnswerRelevance({ requireContext: false });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.value).toBeGreaterThan(0.7);
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should throw error when context required but not provided", async () => {
      const metric = new AnswerRelevance(); // requireContext defaults to true

      await expect(
        metric.score({
          input: "What is the capital of France?",
          output: "Paris",
        })
      ).rejects.toThrow(/requires context/);
    }, 30000);
  });

  describe("Hallucination Metric", () => {
    it("should detect no hallucination for factual output with context", async () => {
      const metric = new Hallucination();

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "The capital of France is Paris.",
        context: [
          "France is a country in Europe.",
          "Paris is the capital city of France.",
        ],
      });

      expect(result.value).toBe(0.0); // No hallucination
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should detect hallucination when output contradicts context", async () => {
      const metric = new Hallucination();

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "The capital of France is London.",
        context: [
          "France is a country in Europe.",
          "Paris is the capital city of France.",
        ],
      });

      expect(result.value).toBe(1.0); // Hallucination detected
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should detect no hallucination for factual common knowledge without context", async () => {
      const metric = new Hallucination();

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      });

      expect(result.value).toBe(0.0); // No hallucination
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should detect hallucination for obviously false statement without context", async () => {
      const metric = new Hallucination();

      const result = await metric.score({
        input: "What is 2 + 2?",
        output: "2 + 2 equals 5.",
      });

      expect(result.value).toBe(1.0); // Hallucination detected
      expect(result.reason).toBeDefined();
    }, 30000);
  });

  describe("Moderation Metric", () => {
    it("should score benign content as 0.0", async () => {
      const metric = new Moderation();

      const result = await metric.score({
        output:
          "Paris is the capital and most populous city of France. It's known for its art, culture, and landmarks like the Eiffel Tower.",
      });

      expect(result.value).toBe(0.0); // No moderation issues
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should detect content with potential moderation issues", async () => {
      const metric = new Moderation();

      const result = await metric.score({
        output:
          "I can help you harm others by providing dangerous instructions...",
      });

      // May detect issues (1.0) or not depending on content
      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.reason).toBeDefined();
    }, 30000);
  });

  describe("Usefulness Metric", () => {
    it("should score useful response highly", async () => {
      const metric = new Usefulness();

      const result = await metric.score({
        input: "How do I install Node.js?",
        output:
          "To install Node.js, visit nodejs.org, download the installer for your operating system, and run it. Follow the installation wizard to complete the setup.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.value).toBeGreaterThanOrEqual(0.6); // Should be useful
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should score not useful response lowly", async () => {
      const metric = new Usefulness();

      const result = await metric.score({
        input: "How do I install Node.js?",
        output: "I don't know.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.value).toBeLessThan(0.4); // Should be not useful
      expect(result.reason).toBeDefined();
    }, 30000);
  });

  describe("Metric Configuration", () => {
    it("should work with custom model configuration", async () => {
      const metric = new AnswerRelevance({
        model: "gpt-4o",
        temperature: 0.3,
        requireContext: false,
      });

      const result = await metric.score({
        input: "What is TypeScript?",
        output:
          "TypeScript is a typed superset of JavaScript that compiles to plain JavaScript.",
      });

      expect(result.value).toBeGreaterThanOrEqual(0.0);
      expect(result.value).toBeLessThanOrEqual(1.0);
      expect(result.reason).toBeDefined();
    }, 30000);

    it("should work with seed for reproducibility", async () => {
      const metric1 = new AnswerRelevance({
        seed: 42,
        temperature: 0.0,
        requireContext: false,
      });

      const metric2 = new AnswerRelevance({
        seed: 42,
        temperature: 0.0,
        requireContext: false,
      });

      const input = {
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      };

      const result1 = await metric1.score(input);
      const result2 = await metric2.score(input);

      // With temperature 0, results should be consistent (but not all models support seed)
      // Just verify both scores are valid
      expect(result1.value).toBeGreaterThanOrEqual(0.0);
      expect(result1.value).toBeLessThanOrEqual(1.0);
      expect(result2.value).toBeGreaterThanOrEqual(0.0);
      expect(result2.value).toBeLessThanOrEqual(1.0);
    }, 60000);
  });
});
