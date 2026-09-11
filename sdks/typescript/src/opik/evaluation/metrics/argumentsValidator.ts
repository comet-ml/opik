import { z } from "zod";
import { BaseMetric } from "./BaseMetric";

/**
 * Check if all required arguments for a metric's score method exist in the scoring inputs
 *
 * @param metric The metric that will be executed
 * @param args The arguments object to validate
 * @returns void, throws an error if validation fails
 */
export function validateRequiredArguments(
  metric: BaseMetric,
  args: Record<string, unknown>
): void {
  if (!args || typeof args !== "object") {
    throw new Error("Arguments must be an object");
  }

  // Handle the schema based on its type
  const validationSchema = metric.validationSchema;

  const enhancedSchema = validationSchema.extend(
    Object.fromEntries(
      Object.entries(validationSchema.shape).map(([key, schema]) => [
        key,
        (schema as z.ZodTypeAny).refine((val) => val !== undefined, {
          message: `${key} cannot be undefined`,
          path: [key],
        }),
      ])
    )
  );

  const parsedData = enhancedSchema.safeParse(args);

  if (!parsedData.success) {
    const missedKeys = parsedData.error.issues
      .map((issue: z.ZodIssue) => issue.path[0])
      .filter(Boolean) as Array<string | number>;
    const uniqueMissedKeys = [...new Set(missedKeys)];

    throw new Error(getMissingArgumentsMessage(metric, args, uniqueMissedKeys));
  }
}

/**
 * Get a formatted error message for missing arguments
 *
 * @param metric The metric that was attempted to be executed
 * @param args The arguments that were available
 * @param requiredArgs The list of required argument names
 * @returns A user-friendly error message
 */
export function getMissingArgumentsMessage(
  metric: BaseMetric,
  args: Record<string, unknown>,
  missedArgs: Array<string | number>
): string {
  const availableArgs = Object.keys(args);
  const missingArgs = missedArgs.filter((arg) => !(arg in args));

  return `Metric '${metric.name}' is skipped, missing required arguments: ${missingArgs.join(", ")}. Available arguments: ${availableArgs.join(", ")}.`;
}
