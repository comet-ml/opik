import { BaseMetric } from "./BaseMetric";

/**
 * Check if all required arguments for a metric's score method exist in the scoring inputs
 *
 * @param metric The metric that will be executed
 * @param args The arguments object to validate
 * @param requiredArgs List of required argument names for this metric
 * @returns true if all required arguments exist, false otherwise
 */
export function validateRequiredArguments(
  metric: BaseMetric,
  args: Record<string, unknown>
): void {
  if (!args || typeof args !== "object") {
    throw new Error("Arguments must be an object");
  }

  // TODO: implement metric required args
  const requiredArgs: string[] = [];

  if (!requiredArgs.every((argName) => argName in args)) {
    throw new Error(getMissingArgumentsMessage(metric, args, requiredArgs));
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
  requiredArgs: string[]
): string {
  const availableArgs = Object.keys(args);
  const missingArgs = requiredArgs.filter((arg) => !(arg in args));

  return `Metric '${metric.name}' is missing required arguments: ${missingArgs.join(", ")}. Available arguments: ${availableArgs.join(", ")}.`;
}
