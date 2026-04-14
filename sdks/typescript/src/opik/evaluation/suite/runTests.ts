import type { TestSuite } from "./TestSuite";
import type { TestSuiteResult } from "./types";
import type { EvaluationTask } from "../types";
import type { Prompt } from "@/prompt/Prompt";
import { evaluateTestSuite } from "./evaluateTestSuite";
import { buildSuiteResult } from "./suiteResultConstructor";
import { validateTaskResult } from "./suiteHelpers";

export interface RunTestsOptions {
  /** The test suite to run against */
  testSuite: TestSuite;
  /** The task function to evaluate — receives each item's data and must return `{ input, output }` */
  task: EvaluationTask;
  /** Optional name for the experiment */
  experimentName?: string;
  /** Optional project to associate the experiment with */
  projectName?: string;
  /** Optional configuration stored on the experiment */
  experimentConfig?: Record<string, unknown>;
  /** Optional prompts to link with the experiment */
  prompts?: Prompt[];
  /** Optional tags to associate with the experiment */
  experimentTags?: string[];
  /** Optional model name override for LLMJudge evaluators */
  model?: string;
}

/**
 * Run a test suite evaluation against a task function.
 *
 * This is the primary entry point for running test suites. It executes the task
 * against every item in the suite, scores results using the suite's configured
 * evaluators, and returns pass/fail results.
 *
 * @example
 * ```ts
 * import { runTests, TestSuite } from "opik";
 *
 * const result = await runTests({
 *   testSuite: suite,
 *   task: async (item) => ({
 *     input: item.input,
 *     output: await myLLM(item.input),
 *   }),
 * });
 *
 * console.log(result.allItemsPassed, result.passRate);
 * ```
 */
export async function runTests(
  options: RunTestsOptions
): Promise<TestSuiteResult> {
  const { testSuite, task, model, experimentTags, ...rest } = options;

  const validatedTask: EvaluationTask = async (item) => {
    const result = await task(item);
    return validateTaskResult(result);
  };

  const evalResult = await evaluateTestSuite({
    dataset: testSuite.dataset,
    task: validatedTask,
    client: testSuite.client,
    evaluatorModel: model,
    tags: experimentTags,
    ...rest,
  });

  return buildSuiteResult(evalResult);
}
