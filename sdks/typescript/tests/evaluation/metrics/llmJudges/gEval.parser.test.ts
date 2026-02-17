import { describe, it, expect } from "vitest";
import {
  parseModelOutputString,
  parseProviderResponse,
} from "@/evaluation/metrics/llmJudges/gEval/parser";
import { MetricComputationError } from "@/evaluation/metrics/errors";

describe("GEval Parser", () => {
  const metricName = "g_eval_metric";

  describe("parseModelOutputString", () => {
    describe("Valid JSON parsing", () => {
      it("should parse score 0 and normalize to 0.0", () => {
        const input = JSON.stringify({ score: 0, reason: "Poor quality" });
        const result = parseModelOutputString(input, metricName);

        expect(result.name).toBe(metricName);
        expect(result.value).toBe(0.0);
        expect(result.reason).toBe("Poor quality");
      });

      it("should parse score 10 and normalize to 1.0", () => {
        const input = JSON.stringify({ score: 10, reason: "Excellent" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBe(1.0);
        expect(result.reason).toBe("Excellent");
      });

      it("should parse score 5 and normalize to 0.5", () => {
        const input = JSON.stringify({ score: 5, reason: "Average" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBe(0.5);
      });

      it("should parse score 7 and normalize to 0.7", () => {
        const input = JSON.stringify({ score: 7, reason: "Good" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBeCloseTo(0.7);
      });

      it("should handle decimal scores within 0-10 range", () => {
        const input = JSON.stringify({ score: 3.5, reason: "Below average" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBeCloseTo(0.35);
      });
    });

    describe("JSON extraction from wrapped content", () => {
      it("should extract JSON from text with prefix", () => {
        const input =
          'Analysis: {"score": 8, "reason": "High quality output"}';
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBeCloseTo(0.8);
        expect(result.reason).toBe("High quality output");
      });

      it("should extract JSON from text with suffix", () => {
        const input = '{"score": 3, "reason": "Below average"} - Complete.';
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBeCloseTo(0.3);
      });
    });

    describe("Error handling - Invalid scores", () => {
      it("should throw for score greater than 10", () => {
        const input = JSON.stringify({ score: 11, reason: "Invalid" });

        expect(() => parseModelOutputString(input, metricName)).toThrow(
          MetricComputationError
        );
      });

      it("should throw for negative score", () => {
        const input = JSON.stringify({ score: -1, reason: "Invalid" });

        expect(() => parseModelOutputString(input, metricName)).toThrow(
          MetricComputationError
        );
      });

      it("should throw for non-numeric score", () => {
        const input = JSON.stringify({ score: "high", reason: "Invalid" });

        expect(() => parseModelOutputString(input, metricName)).toThrow(
          MetricComputationError
        );
      });

      it("should throw for missing score", () => {
        const input = JSON.stringify({ reason: "No score" });

        expect(() => parseModelOutputString(input, metricName)).toThrow(
          MetricComputationError
        );
      });

      it("should throw for null score", () => {
        const input = JSON.stringify({ score: null, reason: "Null" });

        expect(() => parseModelOutputString(input, metricName)).toThrow(
          MetricComputationError
        );
      });
    });

    describe("Error handling - Invalid JSON", () => {
      it("should throw for non-JSON content", () => {
        expect(() =>
          parseModelOutputString("Not JSON", metricName)
        ).toThrow(MetricComputationError);
      });

      it("should throw for empty string", () => {
        expect(() => parseModelOutputString("", metricName)).toThrow(
          MetricComputationError
        );
      });

      it("should throw for malformed JSON", () => {
        expect(() =>
          parseModelOutputString('{"score": 5, "reason":', metricName)
        ).toThrow(MetricComputationError);
      });
    });

    describe("Reason handling", () => {
      it("should handle missing reason as empty string", () => {
        const input = JSON.stringify({ score: 5 });
        const result = parseModelOutputString(input, metricName);

        expect(result.reason).toBe("");
      });

      it("should convert non-string reason to string", () => {
        const input = JSON.stringify({ score: 5, reason: 123 });
        const result = parseModelOutputString(input, metricName);

        expect(result.reason).toBe("123");
      });
    });

    describe("Wrong key name", () => {
      it("should throw when score key is named differently", () => {
        const input = JSON.stringify({
          g_eval_score: 1.8,
          reason: "Score exceeds valid range.",
        });

        expect(() => parseModelOutputString(input, metricName)).toThrow(
          MetricComputationError
        );
      });
    });

    describe("Boundary scores", () => {
      it("should parse float score 10.0 and normalize to 1.0", () => {
        const input = JSON.stringify({ score: 10.0, reason: "Perfect" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBe(1.0);
      });

      it("should parse float score 0.0 and normalize to 0.0", () => {
        const input = JSON.stringify({ score: 0.0, reason: "Terrible" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBe(0.0);
      });

      it("should parse score 1 and normalize to 0.1", () => {
        const input = JSON.stringify({ score: 1, reason: "Very low" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBeCloseTo(0.1);
      });

      it("should parse score 9 and normalize to 0.9", () => {
        const input = JSON.stringify({ score: 9, reason: "Very high" });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBeCloseTo(0.9);
      });
    });

    describe("Extra fields", () => {
      it("should ignore extra fields in JSON", () => {
        const input = JSON.stringify({
          score: 7,
          reason: "Good",
          confidence: 0.95,
          extra: "data",
        });
        const result = parseModelOutputString(input, metricName);

        expect(result.value).toBeCloseTo(0.7);
        expect(result.reason).toBe("Good");
      });
    });

    describe("Custom metric names", () => {
      it("should use provided metric name", () => {
        const input = JSON.stringify({ score: 5, reason: "Test" });
        const result = parseModelOutputString(input, "custom_name");

        expect(result.name).toBe("custom_name");
      });
    });
  });

  describe("parseProviderResponse", () => {
    describe("Logprobs weighted averaging", () => {
      it("should compute weighted average from logprobs", () => {
        const response = {
          text: JSON.stringify({ score: 7, reason: "Good quality" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "7",
                    logprob: -0.5,
                    top_logprobs: [
                      { token: "7", logprob: -0.5 },
                      { token: "8", logprob: -1.0 },
                      { token: "6", logprob: -2.0 },
                    ],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.name).toBe(metricName);
        expect(result.value).toBeGreaterThan(0);
        expect(result.value).toBeLessThanOrEqual(1);
        expect(result.reason).toBe("Good quality");
      });

      it("should handle single logprob entry", () => {
        const response = {
          text: JSON.stringify({ score: 9, reason: "Excellent" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "9",
                    logprob: -0.1,
                    top_logprobs: [{ token: "9", logprob: -0.1 }],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.9, 1);
      });

      it("should ignore non-decimal tokens in logprobs", () => {
        const response = {
          text: JSON.stringify({ score: 5, reason: "Average" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "5",
                    logprob: -0.3,
                    top_logprobs: [
                      { token: "5", logprob: -0.3 },
                      { token: "abc", logprob: -1.0 },
                      { token: ".", logprob: -2.0 },
                    ],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.5, 1);
      });

      it("should ignore tokens with score > 10", () => {
        const response = {
          text: JSON.stringify({ score: 8, reason: "Good" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "8",
                    logprob: -0.3,
                    top_logprobs: [
                      { token: "8", logprob: -0.3 },
                      { token: "15", logprob: -1.0 },
                    ],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.8, 1);
      });
    });

    describe("Fallback to text parsing", () => {
      it("should fall back when no providerMetadata", () => {
        const response = {
          text: JSON.stringify({ score: 6, reason: "Decent" }),
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.6);
        expect(result.reason).toBe("Decent");
      });

      it("should fall back when logprobs content is too short", () => {
        const response = {
          text: JSON.stringify({ score: 4, reason: "Below average" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.4);
      });

      it("should fall back when logprobs is null", () => {
        const response = {
          text: JSON.stringify({ score: 3, reason: "Low" }),
          providerMetadata: {
            openai: {
              logprobs: null,
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.3);
      });

      it("should use token candidate when no valid logprobs", () => {
        const response = {
          text: JSON.stringify({ score: 7, reason: "Good" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "7",
                    logprob: -0.3,
                    top_logprobs: [{ token: "abc", logprob: -0.3 }],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.7);
      });
    });

    describe("Error handling", () => {
      it("should throw when response has no text", () => {
        const response = {};

        expect(() => parseProviderResponse(response, metricName)).toThrow(
          MetricComputationError
        );
      });

      it("should throw when response is null", () => {
        expect(() => parseProviderResponse(null, metricName)).toThrow(
          MetricComputationError
        );
      });

      it("should throw when token candidate is non-decimal and no valid logprobs", () => {
        const response = {
          text: JSON.stringify({ score: 7, reason: "Good" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "abc",
                    logprob: -0.3,
                    top_logprobs: [{ token: "xyz", logprob: -0.3 }],
                  },
                ],
              ],
            },
          },
        };

        expect(() => parseProviderResponse(response, metricName)).toThrow(
          MetricComputationError
        );
      });
    });

    describe("Logprobs edge cases", () => {
      it("should handle logprob value of 0.0 (probability 1.0)", () => {
        const response = {
          text: JSON.stringify({ score: 10, reason: "Perfect" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "10",
                    logprob: 0.0,
                    top_logprobs: [{ token: "10", logprob: 0.0 }],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(1.0);
      });

      it("should handle empty top_logprobs array and use token candidate", () => {
        const response = {
          text: JSON.stringify({ score: 4, reason: "Below average" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "4",
                    logprob: -0.5,
                    top_logprobs: [],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.4);
      });

      it("should skip logprob entries with null logprob values", () => {
        const response = {
          text: JSON.stringify({ score: 6, reason: "Decent" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "6",
                    logprob: -0.3,
                    top_logprobs: [
                      { token: "6", logprob: null },
                      { token: "7", logprob: -1.0 },
                    ],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.7, 1);
      });

      it("should handle score token '0' in logprobs correctly", () => {
        const response = {
          text: JSON.stringify({ score: 0, reason: "Very poor" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "0",
                    logprob: -0.1,
                    top_logprobs: [
                      { token: "0", logprob: -0.1 },
                      { token: "1", logprob: -3.0 },
                    ],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeGreaterThanOrEqual(0);
        expect(result.value).toBeLessThan(0.1);
      });

      it("should handle missing top_logprobs field and use token candidate", () => {
        const response = {
          text: JSON.stringify({ score: 3, reason: "Low" }),
          providerMetadata: {
            openai: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "3",
                    logprob: -0.5,
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.3);
      });
    });

    describe("Provider detection", () => {
      it("should work with any provider key in providerMetadata", () => {
        const response = {
          text: JSON.stringify({ score: 5, reason: "Average" }),
          providerMetadata: {
            anthropic: {
              logprobs: [
                [
                  { token: '{"', logprob: -0.01 },
                  { token: "score", logprob: -0.01 },
                  { token: '":', logprob: -0.01 },
                  {
                    token: "5",
                    logprob: -0.1,
                    top_logprobs: [{ token: "5", logprob: -0.1 }],
                  },
                ],
              ],
            },
          },
        };

        const result = parseProviderResponse(response, metricName);

        expect(result.value).toBeCloseTo(0.5, 1);
      });
    });
  });
});
