import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { logger } from "@/utils/logger";
import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import {
  deserializeEvaluators,
  resolveExecutionPolicy,
  resolveItemExecutionPolicy,
} from "@/evaluation/suite/suiteHelpers";
import type { EvaluatorItemPublic } from "@/rest_api/api/types/EvaluatorItemPublic";
import type { ExecutionPolicyPublic } from "@/rest_api/api/types/ExecutionPolicyPublic";
import type { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";

vi.mock("@/evaluation/suite_evaluators/LLMJudge", () => {
  return {
    LLMJudge: {
      fromConfig: vi.fn(),
    },
  };
});

describe("Suite helper functions", () => {
  let loggerWarnSpy: MockInstance;

  beforeEach(() => {
    vi.clearAllMocks();

    vi.mocked(LLMJudge.fromConfig).mockImplementation(
      (config: Record<string, unknown>, options?: { model?: string }) =>
        ({
          name: (config as Record<string, unknown>).name || "llm_judge",
          assertions: (
            ((config as Record<string, unknown>).schema as Array<{
              name: string;
            }>) || []
          ).map((s: { name: string }) => s.name),
          modelName:
            options?.model ||
            (
              (config as Record<string, unknown>).model as Record<
                string,
                unknown
              >
            )?.name ||
            "gpt-5-nano",
        }) as unknown as LLMJudge
    );

    loggerWarnSpy = vi.spyOn(logger, "warn");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("deserializeEvaluators", () => {
    it("should return deserialized LLMJudge instances from evaluator metadata", () => {
      const evaluators: EvaluatorItemPublic[] = [
        {
          name: "relevance-judge",
          type: "llm_judge" as const,
          config: {
            name: "relevance-judge",
            schema: [{ name: "relevance" }, { name: "coherence" }],
            model: { name: "gpt-4o" },
          },
        },
      ];

      const result = deserializeEvaluators(evaluators);

      expect(result).toHaveLength(1);
      expect(LLMJudge.fromConfig).toHaveBeenCalledWith(
        {
          name: "relevance-judge",
          schema: [{ name: "relevance" }, { name: "coherence" }],
          model: { name: "gpt-4o" },
        },
        undefined
      );
      expect(result[0]).toEqual({
        name: "relevance-judge",
        assertions: ["relevance", "coherence"],
        modelName: "gpt-4o",
      });
    });

    it("should return [] when evaluators array is empty", () => {
      const result = deserializeEvaluators([]);

      expect(result).toEqual([]);
    });

    it("should return [] when no llm_judge evaluators", () => {
      const evaluators: EvaluatorItemPublic[] = [
        {
          name: "code-eval",
          type: "code_metric" as const,
          config: { language: "python" },
        },
      ];

      const result = deserializeEvaluators(evaluators);

      expect(result).toEqual([]);
    });

    it("should pass model override to fromConfig when evaluatorModel is provided", () => {
      const evaluators: EvaluatorItemPublic[] = [
        {
          name: "judge-1",
          type: "llm_judge" as const,
          config: {
            name: "judge-1",
            schema: [{ name: "accuracy" }],
            model: { name: "gpt-4o" },
          },
        },
      ];

      const result = deserializeEvaluators(evaluators, "claude-sonnet-4");

      expect(LLMJudge.fromConfig).toHaveBeenCalledWith(
        {
          name: "judge-1",
          schema: [{ name: "accuracy" }],
          model: { name: "gpt-4o" },
        },
        { model: "claude-sonnet-4" }
      );
      expect(result).toHaveLength(1);
      expect(result[0]).toEqual(
        expect.objectContaining({
          modelName: "claude-sonnet-4",
        })
      );
    });

    it("should log warning for unsupported evaluator types", () => {
      const evaluators: EvaluatorItemPublic[] = [
        {
          name: "code-eval",
          type: "code_metric" as const,
          config: { language: "python" },
        },
        {
          name: "llm-eval",
          type: "llm_judge" as const,
          config: {
            name: "llm-eval",
            schema: [{ name: "quality" }],
          },
        },
      ];

      const result = deserializeEvaluators(evaluators);

      expect(result).toHaveLength(1);
      expect(loggerWarnSpy).toHaveBeenCalledWith(
        "Unsupported evaluator type: code_metric. Skipping."
      );
    });
  });

  describe("resolveExecutionPolicy", () => {
    it("should return policy from provided values", () => {
      const policy: ExecutionPolicyPublic = {
        runsPerItem: 5,
        passThreshold: 3,
      };

      const result = resolveExecutionPolicy(policy);

      expect(result).toEqual({ runsPerItem: 5, passThreshold: 3 });
    });

    it("should return defaults when undefined", () => {
      const result = resolveExecutionPolicy(undefined);

      expect(result).toEqual({ runsPerItem: 1, passThreshold: 1 });
    });

    it("should use per-field fallback to defaults when partial policy is present", () => {
      const policy: ExecutionPolicyPublic = { runsPerItem: 3 };

      const result = resolveExecutionPolicy(policy);

      expect(result).toEqual({ runsPerItem: 3, passThreshold: 1 });
    });
  });

  describe("resolveItemExecutionPolicy", () => {
    const defaultPolicy = { runsPerItem: 3, passThreshold: 2 };

    it("should return default policy when item policy is undefined", () => {
      const result = resolveItemExecutionPolicy(undefined, defaultPolicy);

      expect(result).toEqual({ runsPerItem: 3, passThreshold: 2 });
    });

    it("should return item values when fully specified", () => {
      const itemPolicy: ExecutionPolicyWrite = {
        runsPerItem: 5,
        passThreshold: 4,
      };

      const result = resolveItemExecutionPolicy(itemPolicy, defaultPolicy);

      expect(result).toEqual({ runsPerItem: 5, passThreshold: 4 });
    });

    it("should fall back to default for missing runsPerItem", () => {
      const itemPolicy: ExecutionPolicyWrite = { passThreshold: 4 };

      const result = resolveItemExecutionPolicy(itemPolicy, defaultPolicy);

      expect(result).toEqual({ runsPerItem: 3, passThreshold: 4 });
    });

    it("should fall back to default for missing passThreshold", () => {
      const itemPolicy: ExecutionPolicyWrite = { runsPerItem: 5 };

      const result = resolveItemExecutionPolicy(itemPolicy, defaultPolicy);

      expect(result).toEqual({ runsPerItem: 5, passThreshold: 2 });
    });
  });
});
