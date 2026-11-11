import { z } from "zod";
import { EvaluationScoreResult } from "../../types";
import { BaseMetric } from "../BaseMetric";

const validationSchema = z.object({
  output: z.unknown(),
  expected: z.unknown(),
});
type Input = z.infer<typeof validationSchema>;

function isComparableContainer(
  value: unknown,
): value is Record<PropertyKey, unknown> {
  if (value === null || typeof value !== "object") {
    return false;
  }

  const prototype = Object.getPrototypeOf(value);
  return (
    Array.isArray(value) || prototype === Object.prototype || prototype === null
  );
}

function enumerableOwnKeys(value: object): (string | symbol)[] {
  return Reflect.ownKeys(value).filter((key) =>
    Object.prototype.propertyIsEnumerable.call(value, key),
  );
}

type Comparison = { output: unknown; expected: unknown } | { exit: object };

function valuesMatch(
  output: unknown,
  expected: unknown,
  caseSensitive: boolean,
): boolean {
  const pending: Comparison[] = [{ output, expected }];
  const ancestors = new WeakSet<object>();

  for (
    let comparison = pending.pop();
    comparison !== undefined;
    comparison = pending.pop()
  ) {
    if ("exit" in comparison) {
      ancestors.delete(comparison.exit);
      continue;
    }

    const { output, expected } = comparison;
    if (output === expected) {
      continue;
    }

    if (
      !caseSensitive &&
      typeof output === "string" &&
      typeof expected === "string" &&
      output.toLowerCase() === expected.toLowerCase()
    ) {
      continue;
    }

    if (
      !isComparableContainer(output) ||
      !isComparableContainer(expected) ||
      Array.isArray(output) !== Array.isArray(expected) ||
      (Array.isArray(output) && output.length !== expected.length) ||
      ancestors.has(output)
    ) {
      return false;
    }

    const keys = enumerableOwnKeys(output);
    if (keys.length !== enumerableOwnKeys(expected).length) {
      return false;
    }

    // Release each ancestor after its children so shared values can be compared again.
    ancestors.add(output);
    pending.push({ exit: output });
    for (const key of keys) {
      if (!Object.prototype.propertyIsEnumerable.call(expected, key)) {
        return false;
      }
      pending.push({ output: output[key], expected: expected[key] });
    }
  }

  return true;
}

/**
 * Checks whether two values match. Arrays and plain objects are compared by
 * their own enumerable properties, including symbol keys.
 */
export class ExactMatch extends BaseMetric {
  /**
   * Creates a new ExactMatch metric
   * @param name Optional name for the metric (defaults to "exact_match")
   * @param trackMetric Whether to track the metric
   * @param caseSensitive Whether strings, including nested values, are matched case-sensitively (defaults to true)
   */
  constructor(
    name = "exact_match",
    trackMetric = true,
    private readonly caseSensitive = true,
  ) {
    super(name, trackMetric);
  }

  public validationSchema = validationSchema;

  /**
   * Calculates a score based on exact match between output and expected
   * @param input Actual output to evaluate, must include `output` and `expected` properties
   * @returns Score result (1.0 for match, 0.0 for no match)
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { output, expected } = input;
    const score = valuesMatch(output, expected, this.caseSensitive) ? 1.0 : 0.0;

    return {
      name: this.name,
      value: score,
      reason: `Exact match: ${score === 1.0 ? "Match" : "No match"}`,
    };
  }
}
