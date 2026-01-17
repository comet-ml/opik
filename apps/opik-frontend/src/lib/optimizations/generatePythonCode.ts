/**
 * Python Code Generator for Optimization Studio
 *
 * ⚠️ MAINTENANCE NOTE: This code generator duplicates logic from the server-side
 * MetricFactory and OptimizerFactory. When those factories change, this generator
 * MUST be updated to match.
 *
 * Server code locations to keep in sync:
 * - Metric generation: apps/opik-python-backend/src/opik_backend/studio/metrics.py
 *   - MetricFactory.build() and registered metric builders
 * - Optimizer generation: apps/opik-python-backend/src/opik_backend/studio/optimizers.py
 *   - OptimizerFactory.build() and parameter handling
 *
 * When updating this file, verify:
 * 1. Metric functions match MetricFactory implementations exactly
 * 2. Optimizer initialization matches OptimizerFactory.build() exactly
 * 3. Parameter handling (model_parameters vs optimizer_params) matches server logic
 * 4. Default values match server defaults
 */

import {
  OptimizationConfigFormType,
} from "@/components/pages-shared/optimizations/OptimizationConfigForm/schema";
import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
} from "@/types/optimizations";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { getProviderFromModel } from "@/lib/provider";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

/**
 * Converts a model name to LiteLLM format (provider/model-name)
 */
function convertModelToLiteLLM(model: PROVIDER_MODEL_TYPE): string {
  // If model already has provider prefix (e.g., "anthropic/claude-3-haiku"), return as-is
  if (model.includes("/")) {
    return model;
  }

  const provider = getProviderFromModel(model);

  // Map provider types to LiteLLM provider names
  const providerMap: Record<PROVIDER_TYPE, string> = {
    [PROVIDER_TYPE.OPEN_AI]: "openai",
    [PROVIDER_TYPE.ANTHROPIC]: "anthropic",
    [PROVIDER_TYPE.GEMINI]: "gemini",
    [PROVIDER_TYPE.OPEN_ROUTER]: "openrouter",
    [PROVIDER_TYPE.VERTEX_AI]: "vertex_ai",
    [PROVIDER_TYPE.CUSTOM]: "custom",
    [PROVIDER_TYPE.BEDROCK]: "bedrock",
    [PROVIDER_TYPE.OPIK_FREE]: "opik-free",
  };

  const liteLLMProvider = providerMap[provider] || "openai";

  // Handle special cases for Anthropic models
  if (provider === PROVIDER_TYPE.ANTHROPIC) {
    // Convert claude model names to proper format
    if (model.startsWith("claude-")) {
      return `${liteLLMProvider}/${model}`;
    }
    // Handle other anthropic formats
    return `${liteLLMProvider}/${model}`;
  }

  // Handle OpenAI models
  if (provider === PROVIDER_TYPE.OPEN_AI) {
    return `${liteLLMProvider}/${model}`;
  }

  // Handle Gemini models
  if (provider === PROVIDER_TYPE.GEMINI) {
    if (model.startsWith("gemini-") || model.startsWith("gemma-")) {
      return `${liteLLMProvider}/${model}`;
    }
    return `${liteLLMProvider}/${model}`;
  }

  // For custom providers, check if it already has a prefix
  if (provider === PROVIDER_TYPE.CUSTOM || provider === PROVIDER_TYPE.BEDROCK) {
    // Custom models might already have provider prefix in the model name
    return model;
  }

  // Default: add provider prefix
  return `${liteLLMProvider}/${model}`;
}

/**
 * Converts template syntax from {{variable}} (Mustache) to {variable} (Python)
 */
function convertTemplateSyntax(content: string): string {
  return content.replace(/\{\{\s*([^}]+?)\s*\}\}/g, "{$1}");
}

/**
 * Formats a Python value for code generation
 */
function formatPythonValue(value: unknown): string {
  if (value === null || value === undefined) {
    return "None";
  }
  if (typeof value === "string") {
    // Escape quotes and wrap in quotes
    const escaped = value.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
    return `"${escaped}"`;
  }
  if (typeof value === "number") {
    return String(value);
  }
  if (typeof value === "boolean") {
    return value ? "True" : "False";
  }
  if (Array.isArray(value)) {
    return `[${value.map(formatPythonValue).join(", ")}]`;
  }
  if (typeof value === "object") {
    const entries = Object.entries(value)
      .map(([k, v]) => `"${k}": ${formatPythonValue(v)}`)
      .join(", ");
    return `{${entries}}`;
  }
  return String(value);
}

/**
 * Generates metric function code based on metric type and parameters
 * Matches the server implementation using the same SDK classes
 *
 * ⚠️ MAINTENANCE: Keep in sync with MetricFactory in:
 * apps/opik-python-backend/src/opik_backend/studio/metrics.py
 *
 * When MetricFactory changes (new parameters, defaults, ScoreResult wrapping),
 * update this function to match.
 */
function generateMetricFunction(
  metricType: METRIC_TYPE,
  metricParams: Record<string, unknown>,
  model: string,
): string {
  const liteLLMModel = convertModelToLiteLLM(model as PROVIDER_MODEL_TYPE);

  switch (metricType) {
    case METRIC_TYPE.EQUALS: {
      // ⚠️ Matches MetricFactory._build_equals_metric()
      const referenceKey = metricParams.reference_key as string;
      const caseSensitive = metricParams.case_sensitive as boolean ?? false;
      return `def equals_metric(dataset_item, llm_output):
    equals_metric = Equals(case_sensitive=${caseSensitive ? "True" : "False"})
    reference = dataset_item.get("${referenceKey}", "")
    result = equals_metric.score(reference=reference, output=llm_output)
    
    # Add reason for compatibility
    if result.value == 1.0:
        reason = "Exact match: output equals reference"
    else:
        reason = "No match: output does not equal reference"
    
    return ScoreResult(value=result.value, name=result.name, reason=reason)`;
    }

    case METRIC_TYPE.LEVENSHTEIN: {
      // ⚠️ Matches MetricFactory._build_levenshtein_metric()
      const referenceKey = metricParams.reference_key as string;
      const caseSensitive = metricParams.case_sensitive as boolean ?? false;
      return `def levenshtein_metric(dataset_item, llm_output):
    levenshtein_metric = LevenshteinRatio(case_sensitive=${caseSensitive ? "True" : "False"})
    reference = dataset_item.get("${referenceKey}", "")
    result = levenshtein_metric.score(reference=reference, output=llm_output)
    
    reason = f"Similarity: {result.value * 100:.0f}%"
    return ScoreResult(value=result.value, name=result.name, reason=reason)`;
    }

    case METRIC_TYPE.G_EVAL: {
      // ⚠️ Matches MetricFactory._build_geval_metric()
      const taskIntroduction = metricParams.task_introduction as string ?? "";
      const evaluationCriteria = metricParams.evaluation_criteria as string ?? "";
      return `def geval_metric(dataset_item, llm_output):
    geval_metric = GEval(
        task_introduction="${taskIntroduction.replace(/"/g, '\\"')}",
        evaluation_criteria="${evaluationCriteria.replace(/"/g, '\\"')}",
        model="${liteLLMModel}"
    )
    return geval_metric.score(
        input=dataset_item,
        output=llm_output
    )`;
    }

    case METRIC_TYPE.JSON_SCHEMA_VALIDATOR: {
      // ⚠️ Matches MetricFactory._build_json_schema_validator_metric()
      // Note: Server uses schema_key (default "json_schema"), but frontend form uses reference_key
      // Using reference_key here to match what the form sends
      const schemaKey = (metricParams.reference_key as string) || (metricParams.schema_key as string) || "json_schema";
      return `def json_schema_validator_metric(dataset_item, llm_output):
    structured_metric = StructuredOutputCompliance(
        model="${liteLLMModel}",
        name="json_schema_validator"
    )
    schema = dataset_item.get("${schemaKey}")
    if not schema:
        return ScoreResult(
            value=0.0,
            name="json_schema_validator",
            reason=f"Missing schema in dataset item key '${schemaKey}'"
        )
    return structured_metric.score(
        output=llm_output,
        schema=schema
    )`;
    }

    default:
      return `def custom_metric(dataset_item, llm_output):
    # TODO: Implement your custom metric
    return 0.0`;
  }
}

/**
 * Generates optimizer initialization code
 *
 * ⚠️ MAINTENANCE: Keep in sync with OptimizerFactory.build() in:
 * apps/opik-python-backend/src/opik_backend/studio/optimizers.py
 *
 * Important details to match:
 * - model_parameters dict vs optimizer_params separation
 * - Default max_tokens handling (server adds default max_tokens if missing)
 * - Parameter passing: optimizer_class(model=model, model_parameters=model_params, **optimizer_params)
 *
 * When OptimizerFactory changes, update this function to match.
 */
function generateOptimizerCode(
  optimizerType: OPTIMIZER_TYPE,
  model: PROVIDER_MODEL_TYPE,
  modelParams: Record<string, unknown>,
  optimizerParams: Record<string, unknown>,
): string {
  const liteLLMModel = convertModelToLiteLLM(model);

  // Build optimizer parameters
  // ⚠️ Matches OptimizerFactory.build() parameter handling
  const params: string[] = [`model="${liteLLMModel}"`];

  // Build model_parameters dict (temperature, max_tokens, etc.)
  // Note: Server adds default max_tokens if missing (see OptimizerFactory line 71-72)
  // We don't add it here since it's a server-side optimization
  const modelParamsDict: Record<string, unknown> = {};
  if (modelParams.temperature !== undefined) {
    modelParamsDict.temperature = modelParams.temperature;
  }
  // Add any other model parameters
  Object.entries(modelParams).forEach(([key, value]) => {
    if (key !== "temperature" && value !== undefined && value !== null) {
      modelParamsDict[key] = value;
    }
  });

  // Add model_parameters if we have any
  // ⚠️ Server passes as: optimizer_class(model=model, model_parameters=model_params, **optimizer_params)
  if (Object.keys(modelParamsDict).length > 0) {
    const modelParamsStr = Object.entries(modelParamsDict)
      .map(([k, v]) => `"${k}": ${formatPythonValue(v)}`)
      .join(", ");
    params.push(`model_parameters={${modelParamsStr}}`);
  }

  // Add optimizer-specific parameters (not model parameters)
  // ⚠️ These are passed as **kwargs in server: **optimizer_params
  // Note: Server forces verbose=True (see optimizer_runner.py line 155)
  // We'll override any verbose setting to ensure it's True
  const hasVerboseParam = optimizerParams.hasOwnProperty("verbose");
  Object.entries(optimizerParams).forEach(([key, value]) => {
    if (key === "verbose") {
      // Always set verbose=True to match server behavior
      params.push("verbose=True");
    } else if (value !== undefined && value !== null) {
      params.push(`${key}=${formatPythonValue(value)}`);
    }
  });
  // If verbose wasn't in optimizerParams, add it
  if (!hasVerboseParam) {
    params.push("verbose=True");
  }

  switch (optimizerType) {
    case OPTIMIZER_TYPE.GEPA: {
      return `optimizer = GepaOptimizer(
    ${params.join(",\n    ")}
)`;
    }

    case OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE: {
      return `optimizer = HierarchicalReflectiveOptimizer(
    ${params.join(",\n    ")}
)`;
    }

    case OPTIMIZER_TYPE.EVOLUTIONARY: {
      return `optimizer = EvolutionaryOptimizer(
    ${params.join(",\n    ")}
)`;
    }

    default:
      return `optimizer = GepaOptimizer(
    ${params.join(",\n    ")}
)`;
  }
}

/**
 * Generates optimizer import statement
 */
function getOptimizerImport(optimizerType: OPTIMIZER_TYPE): string {
  switch (optimizerType) {
    case OPTIMIZER_TYPE.GEPA:
      return "GepaOptimizer";
    case OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE:
      return "HierarchicalReflectiveOptimizer";
    case OPTIMIZER_TYPE.EVOLUTIONARY:
      return "EvolutionaryOptimizer";
    default:
      return "GepaOptimizer";
  }
}

/**
 * Generates Python code from optimization form data
 */
export function generatePythonCode(
  formData: OptimizationConfigFormType,
  datasetName: string,
): string {
  const liteLLMModel = convertModelToLiteLLM(formData.modelName);

  // Build ChatPrompt messages
  // Check if we can use simple system/user format or need messages array
  const hasOnlySystemAndUser =
    formData.messages.length === 2 &&
    formData.messages.some((m) => m.role === LLM_MESSAGE_ROLE.system) &&
    formData.messages.some((m) => m.role === LLM_MESSAGE_ROLE.user);

  let promptMessages = "";
  if (hasOnlySystemAndUser && formData.messages.length === 2) {
    // Use simple system/user format
    const systemMsg = formData.messages.find(
      (m) => m.role === LLM_MESSAGE_ROLE.system,
    );
    const userMsg = formData.messages.find(
      (m) => m.role === LLM_MESSAGE_ROLE.user,
    );

    let systemContent = "";
    if (systemMsg) {
      if (typeof systemMsg.content === "string") {
        systemContent = convertTemplateSyntax(systemMsg.content);
      } else {
        systemContent = String(systemMsg.content);
      }
    }

    let userContent = "";
    if (userMsg) {
      if (typeof userMsg.content === "string") {
        userContent = convertTemplateSyntax(userMsg.content);
      } else {
        userContent = String(userMsg.content);
      }
    }

    const escapedSystem = systemContent.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
    const escapedUser = userContent.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

    if (systemContent && userContent) {
      promptMessages = `    system="${escapedSystem}",
    user="${escapedUser}"`;
    } else if (systemContent) {
      promptMessages = `    system="${escapedSystem}"`;
    } else if (userContent) {
      promptMessages = `    user="${escapedUser}"`;
    }
  } else {
    // Use messages array format
    const messages: string[] = [];
    formData.messages.forEach((msg) => {
      const role = msg.role;
      let content = "";
      if (typeof msg.content === "string") {
        content = convertTemplateSyntax(msg.content);
      } else if (Array.isArray(msg.content)) {
        // Handle multimodal content - convert to JSON string
        content = JSON.stringify(msg.content);
      } else {
        content = String(msg.content);
      }

      // Escape quotes in content
      const escapedContent = content.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
      messages.push(`        {"role": "${role}", "content": "${escapedContent}"}`);
    });

    promptMessages = `    messages=[
${messages.join(",\n")}
    ]`;
  }

  // Generate metric function
  const metricFunction = generateMetricFunction(
    formData.metricType,
    formData.metricParams as Record<string, unknown>,
    formData.modelName,
  );

  // Generate optimizer code
  const optimizerCode = generateOptimizerCode(
    formData.optimizerType,
    formData.modelName,
    formData.modelConfig as Record<string, unknown>,
    formData.optimizerParams as Record<string, unknown>,
  );

  const optimizerImport = getOptimizerImport(formData.optimizerType);

  // Determine metric function name
  const metricFunctionName =
    formData.metricType === METRIC_TYPE.EQUALS
      ? "equals_metric"
      : formData.metricType === METRIC_TYPE.LEVENSHTEIN
        ? "levenshtein_metric"
        : formData.metricType === METRIC_TYPE.G_EVAL
          ? "geval_metric"
          : "json_schema_validator_metric";

  // Determine which imports are needed based on metric type
  const metricImports: string[] = [];
  if (formData.metricType === METRIC_TYPE.EQUALS || formData.metricType === METRIC_TYPE.LEVENSHTEIN || formData.metricType === METRIC_TYPE.JSON_SCHEMA_VALIDATOR) {
    metricImports.push("from opik.evaluation.metrics.score_result import ScoreResult");
  }
  if (formData.metricType === METRIC_TYPE.EQUALS) {
    metricImports.push("from opik.evaluation.metrics import Equals");
  }
  if (formData.metricType === METRIC_TYPE.LEVENSHTEIN) {
    metricImports.push("from opik.evaluation.metrics import LevenshteinRatio");
  }
  if (formData.metricType === METRIC_TYPE.G_EVAL) {
    metricImports.push("from opik.evaluation.metrics import GEval");
  }
  if (formData.metricType === METRIC_TYPE.JSON_SCHEMA_VALIDATOR) {
    metricImports.push("from opik.evaluation.metrics import StructuredOutputCompliance");
  }

  // Build the complete Python code
  const importsSection = metricImports.length > 0 ? "\n" + metricImports.join("\n") : "";
  const pythonCode = `# Configure the SDK
import os
# Set your Opik API key
# os.environ["OPIK_API_KEY"] = "your-api-key-here"

import opik
from opik_optimizer import (
    ChatPrompt,
    ${optimizerImport},
)${importsSection}

# Define the prompt to optimize
prompt = ChatPrompt(
${promptMessages}
)

# Get the dataset to evaluate the prompt on
client = opik.Opik()
dataset = client.get_dataset(name="${datasetName}")

# Define the metric to evaluate the prompt on
${metricFunction}

# Run the optimization
${optimizerCode}

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=${metricFunctionName},
    n_samples=20,  # ⚠️ Matches server default (OPTSTUDIO_DATASET_SAMPLES, see config.py line 23)
    max_trials=10,  # ⚠️ Matches server default (OPTIMIZER_MAX_TRIALS, see config.py line 29)
)

result.display()
# Optimizer metadata (prompt, tools, version) is logged automatically.
`;

  return pythonCode;
}
