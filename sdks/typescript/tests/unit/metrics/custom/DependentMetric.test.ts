import { describe, it, expect, vi } from "vitest";
import { BaseMetric, EvaluationScoreResult } from "opik";
import { z } from "zod";

const validationSchema = z.object({
  output: z.unknown(),
});

class DependentMetric extends BaseMetric {
  constructor(
    private readonly service: { getScore: () => number },
    name = "dependent_metric",
    trackMetric = false
  ) {
    super(name, trackMetric);
  }

  public validationSchema = validationSchema;

  score(): EvaluationScoreResult {
    return {
      name: this.name,
      value: this.service.getScore(),
      reason: `Dependent score: ${this.service.getScore()}`,
    };
  }
}

describe("DependentMetric", () => {
  it("should work with injected dependencies", () => {
    const mockService = {
      getScore: vi.fn().mockReturnValue(0.42),
    };

    const metric = new DependentMetric(mockService);
    const result = metric.score();

    expect(mockService.getScore).toHaveBeenCalledTimes(2); // Called twice in the score method

    expect(result.value).toBe(0.42);
    expect(result.reason).toBe("Dependent score: 0.42");
    expect(result.name).toBe("dependent_metric");
  });

  it("should use the provided name", () => {
    const mockService = { getScore: () => 0.5 };
    const metric = new DependentMetric(mockService, "custom_name");
    const result = metric.score();

    expect(result.name).toBe("custom_name");
  });

  it("should handle different return values from the service", () => {
    const mockService = {
      getScore: vi.fn().mockReturnValueOnce(0.1).mockReturnValueOnce(0.2),
    };

    const metric = new DependentMetric(mockService);
    const result = metric.score();

    expect(result.value).toBe(0.1);
    expect(result.reason).toBe("Dependent score: 0.2");
  });
});
