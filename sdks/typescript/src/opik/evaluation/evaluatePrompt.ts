import type { LanguageModel } from "ai";
import {
  OpikMessage,
  OpikBaseModel,
  resolveModel,
  SupportedModelId,
} from "./models";
import { EvaluationResult, EvaluationTask } from "./types";
import { evaluate, EvaluateOptions } from "./evaluate";
import { PromptType } from "@/prompt/types";
import {
  applyTemplateVariablesToMessage,
  formatMessagesAsString,
} from "./utils/formatMessages";

/**
 * Options for evaluating prompt templates against a dataset.
 * Extends EvaluateOptions but replaces 'task' with prompt-specific fields.
 */
export interface EvaluatePromptOptions extends Omit<EvaluateOptions, "task"> {
  /** Message templates with {{placeholders}} to be formatted with dataset variables */
  messages: OpikMessage[];

  /** Model to use for generation. Can be model ID string, LanguageModel instance, or OpikBaseModel instance. Defaults to gpt-4o */
  model?: SupportedModelId | LanguageModel | OpikBaseModel;

  /** Template engine type for variable substitution. Defaults to mustache */
  templateType?: PromptType;

  /** Temperature setting for model generation (0.0-2.0). Controls randomness. Lower values make output more focused and deterministic. */
  temperature?: number;

  /** Random seed for reproducible model outputs. Useful for testing and ensuring consistent results. */
  seed?: number;
}

/**
 * Evaluates prompt templates by formatting messages with dataset variables and generating LLM responses.
 *
 * This is a convenience wrapper around the `evaluate` function that handles prompt template formatting
 * and model invocation automatically. It formats message templates with dataset item variables using
 * the specified template engine (Mustache or Jinja2), generates responses using the provided model,
 * and evaluates results using the specified metrics.
 *
 * @param options - Configuration options for prompt evaluation
 * @returns Promise resolving to evaluation results with experiment metadata
 *
 * @example
 * ```typescript
 * import { evaluatePrompt } from 'opik/evaluation';
 * import { Equals } from 'opik/evaluation/metrics';
 *
 * const dataset = await client.getDataset('my-dataset');
 *
 * // Using model ID string with temperature and seed for reproducibility
 * const result1 = await evaluatePrompt({
 *   dataset,
 *   messages: [
 *     { role: 'user', content: 'Translate to {{language}}: {{text}}' }
 *   ],
 *   model: 'gpt-4o', // or omit to use default model
 *   temperature: 0.7,
 *   seed: 42,
 *   scoringMetrics: [new Equals()],
 * });
 *
 * // Using pre-configured LanguageModel instance
 * import { openai } from '@ai-sdk/openai';
 * const customModel = openai('gpt-4o', { structuredOutputs: true });
 * const result2 = await evaluatePrompt({
 *   dataset,
 *   messages: [
 *     { role: 'user', content: 'Translate to {{language}}: {{text}}' }
 *   ],
 *   model: customModel,
 *   scoringMetrics: [new Equals()],
 * });
 * ```
 */
export async function evaluatePrompt(
  options: EvaluatePromptOptions
): Promise<EvaluationResult> {
  // Validate required parameters
  if (!options.dataset) {
    throw new Error("Dataset is required for prompt evaluation");
  }

  if (!options.messages || options.messages.length === 0) {
    throw new Error("Messages array is required and cannot be empty");
  }

  // Validate experimentConfig type
  if (
    options.experimentConfig !== undefined &&
    (typeof options.experimentConfig !== "object" ||
      options.experimentConfig === null ||
      Array.isArray(options.experimentConfig))
  ) {
    throw new Error(
      "experimentConfig must be a plain object, not an array or primitive value"
    );
  }

  // Resolve model (string → OpikBaseModel, undefined → default)
  const model = resolveModel(options.model);

  // Build experiment config with defaults
  const experimentConfig = {
    ...options.experimentConfig,
    prompt_template: options.messages,
    model: model.modelName,
    ...(options.temperature !== undefined && {
      temperature: options.temperature,
    }),
    ...(options.seed !== undefined && { seed: options.seed }),
  };

  // Build task function that wraps prompt formatting
  const task = _buildPromptEvaluationTask(
    model,
    options.messages,
    options.templateType ?? PromptType.MUSTACHE,
    {
      temperature: options.temperature,
      seed: options.seed,
    }
  );

  // Delegate to existing evaluate function
  return evaluate({
    dataset: options.dataset,
    task,
    scoringMetrics: options.scoringMetrics,
    experimentName: options.experimentName,
    projectName: options.projectName,
    experimentConfig,
    prompts: options.prompts,
    client: options.client,
    nbSamples: options.nbSamples,
    scoringKeyMapping: options.scoringKeyMapping,
  });
}

/**
 * Builds an evaluation task that formats prompt templates and generates LLM responses.
 *
 * This helper creates a task function that:
 * 1. Formats message templates with dataset item variables
 * 2. Invokes the model with formatted messages
 * 3. Extracts and returns the response
 *
 * @param model - The model to use for generation
 * @param messages - Message templates with placeholders
 * @param templateType - Template engine type (mustache or jinja2)
 * @param modelOptions - Optional model generation parameters (temperature, seed)
 * @returns Evaluation task function
 */
function _buildPromptEvaluationTask(
  model: OpikBaseModel,
  messages: OpikMessage[],
  templateType: PromptType,
  modelOptions?: { temperature?: number; seed?: number }
): EvaluationTask<Record<string, unknown>> {
  return async (datasetItem: Record<string, unknown>) => {
    // Apply template variables to each message
    const messagesWithVariables: OpikMessage[] = messages.map((message) =>
      applyTemplateVariablesToMessage(message, datasetItem, templateType)
    );

    // Generate response from model with optional temperature and seed
    const response = await model.generateProviderResponse(
      messagesWithVariables,
      modelOptions
    );

    // Extract text from provider response
    const outputText = extractResponseText(response);

    // Convert messages array to human-readable string for metrics
    // This ensures compatibility with metrics that expect string input
    const inputText = formatMessagesAsString(messagesWithVariables);

    return {
      input: inputText,
      output: outputText,
    };
  };
}

/**
 * Extracts text content from a provider-specific response object.
 *
 * Handles various response formats from different LLM providers:
 * - Vercel AI SDK: { text: string }
 * - Generic: { content: string }
 * - Objects: JSON stringified
 * - Primitives: String conversion
 *
 * @param response - Provider-specific response object
 * @returns Extracted text content
 */
function extractResponseText(response: unknown): string {
  // Handle Vercel AI SDK response structure
  if (response && typeof response === "object") {
    if ("text" in response && typeof response.text === "string") {
      return response.text;
    }
    if ("content" in response && typeof response.content === "string") {
      return response.content;
    }
    return JSON.stringify(response);
  }

  return String(response);
}
