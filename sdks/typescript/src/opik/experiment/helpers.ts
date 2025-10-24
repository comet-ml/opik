import type { Prompt } from "@/prompt/Prompt";
import { ExperimentConfigError } from "@/errors/experiment";

/**
 * Type for prompt version link sent to backend
 */
export interface PromptVersionLink {
  id: string;
}

/**
 * Builds experiment metadata and prompt version links from experiment config and prompts.
 *
 * This function validates inputs and combines experiment configuration with prompt information.
 * It ensures data integrity by failing fast on invalid inputs rather than silently ignoring them.
 *
 * **Validation Rules:**
 * - `experimentConfig` must be a plain object if provided
 * - Cannot specify both `prompts` parameter AND `experimentConfig.prompts`
 * - If `prompts` array is provided, it cannot be empty
 *
 * **Behavior:**
 * 1. Validates experimentConfig is a plain object (throws if invalid)
 * 2. Checks for conflicts between prompts parameter and config.prompts (throws if conflict)
 * 3. Deep copies config to prevent mutations
 * 4. Embeds prompt templates under 'prompts' key in config
 * 5. Extracts prompt version IDs for backend linking
 *
 * @param experimentConfig - Optional experiment configuration object
 * @param prompts - Optional array of Prompt objects to link
 * @returns Tuple of [metadata, promptVersions] where both can be undefined
 *
 * @throws {ExperimentConfigError} When experimentConfig is not a valid object
 * @throws {ExperimentConfigError} When both prompts parameter and config.prompts exist
 *
 * @example
 * ```typescript
 * // Valid usage
 * const [metadata, versions] = buildMetadataAndPromptVersions(
 *   { model: 'gpt-4', temperature: 0.7 },
 *   [prompt1, prompt2]
 * );
 * // Result:
 * // metadata: {
 * //   model: 'gpt-4',
 * //   temperature: 0.7,
 * //   prompts: { 'prompt1': 'template1', 'prompt2': 'template2' }
 * // }
 * // versions: [{ id: 'version-id-1' }, { id: 'version-id-2' }]
 *
 * // Invalid - will throw
 * buildMetadataAndPromptVersions(
 *   { prompts: { custom: 'template' } },  // Already has prompts
 *   [prompt1]  // Conflict!
 * );
 * // Throws: ExperimentConfigError with clear guidance
 * ```
 */
export function buildMetadataAndPromptVersions(
  experimentConfig?: Record<string, unknown>,
  prompts?: Prompt[]
): [Record<string, unknown> | undefined, PromptVersionLink[] | undefined] {
  // Validate experimentConfig is a plain object
  if (experimentConfig !== undefined) {
    if (
      typeof experimentConfig !== "object" ||
      experimentConfig === null ||
      Array.isArray(experimentConfig)
    ) {
      throw ExperimentConfigError.invalidConfigType(typeof experimentConfig);
    }
  }

  // Check for conflicts early - fail fast
  if (prompts && prompts.length > 0 && experimentConfig?.prompts) {
    throw ExperimentConfigError.promptsConflict();
  }

  // Handle empty inputs
  if (!experimentConfig && (!prompts || prompts.length === 0)) {
    return [undefined, undefined];
  }

  // Deep copy config to avoid mutations
  const config: Record<string, unknown> = experimentConfig
    ? structuredClone(experimentConfig)
    : {};

  // Process prompts if provided
  let promptVersions: PromptVersionLink[] | undefined = undefined;

  if (prompts && prompts.length > 0) {
    promptVersions = [];
    const promptsConfig: Record<string, string> = {};

    for (const prompt of prompts) {
      // Extract version ID for backend linking
      promptVersions.push({ id: prompt.versionId });

      // Embed prompt template in config
      promptsConfig[prompt.name] = prompt.prompt;
    }

    config.prompts = promptsConfig;
  }

  // Return undefined for both if config is still empty
  if (Object.keys(config).length === 0) {
    return [undefined, undefined];
  }

  return [config, promptVersions];
}
